package org.project;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.project.engine.*;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.geometry.Rectangle2D;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainController
{
    @FXML
    private StackPane canvasPlaceholder;

    private Thread renderThread;

    private OpenGLRenderer currentRenderer;
    private int nextObjectId = 1;
    private double lastMouseX = 0;
    private double lastMouseY = 0;
    private double lastClickScreenX = 0;
    private double lastClickScreenY = 0;
    private final Popup selectionPopup = new Popup();
    private final Map<Integer, Stage> openObjectWindows = new HashMap<>();

    @FXML
    private void handleFileUpload(ActionEvent event)
    {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Alege un fișier 3D");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Modele 3D", "*.gltf", "*.glb", "*.obj")
        );
        Stage stage = (Stage) canvasPlaceholder.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);
        if (selectedFile != null)
        {
            startRenderEngine(selectedFile.getAbsolutePath());
        }
    }

    @FXML
    private void handleResetScene(ActionEvent event) {
        if (currentRenderer != null) {
            for (int i = 0; i < currentRenderer.objects.size(); i++) {
                SceneObject obj = currentRenderer.objects.get(i);
                obj.position.set(i * 3.5f, 0, 0);
                obj.rotation.set((float) Math.toRadians(-90.0f), 0, 0);
                obj.scale = 1.0f;
            }
        }
    }

    @FXML
    private void handleClearScene(ActionEvent event) {
        if (currentRenderer != null) {
            currentRenderer.objects.clear();
            selectionPopup.hide();
            for (Stage stage : openObjectWindows.values()) {
                stage.close();
            }
            openObjectWindows.clear();
        }
    }

    @FXML
    private void handleShowControls(ActionEvent event) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Control Vizualizator 3D");
        alert.setHeaderText("Instrucțiuni de manipulare");
        alert.setContentText("• Click Stanga + Tragere pe ecran: Roteste obiectul selectat (Rotate).\n\n"
                + "• Click Dreapta + Tragere pe ecran: Muta obiectul (Pan).\n\n"
                + "• Rotita Mouse: Mareste sau micsoreaza obiectul (Zoom).\n\n"
                + "• Click pe model: Selecteaza modelul (se va evidentia cu rosu).\n\n"
                + "• Click pe fundalul gri: Pastreaza selectia curenta pentru a putea trage mouse-ul mai usor in spatiu.");
        alert.showAndWait();
    }

    @FXML
    private void handleExit(ActionEvent event) {
        Platform.exit();
        System.exit(0);
    }

    private void startRenderEngine(String modelPath)
    {
        if (renderThread != null && renderThread.isAlive())
        {
            if (currentRenderer != null)
            {
                currentRenderer.queueModelLoad(modelPath);
            }
            return;
        }

        canvasPlaceholder.getChildren().clear();
        WritableImage frameBufferImage = new WritableImage(800, 600);
        ImageView imageView = new ImageView(frameBufferImage);

        imageView.fitWidthProperty().bind(canvasPlaceholder.widthProperty());
        imageView.fitHeightProperty().bind(canvasPlaceholder.heightProperty());
        imageView.setPreserveRatio(true);
        canvasPlaceholder.getChildren().add(imageView);

        currentRenderer = new OpenGLRenderer(frameBufferImage);
        currentRenderer.setOnSelectionChanged(this::onSelectionChanged);

        imageView.setOnMousePressed(event -> {
            double viewW = imageView.getLayoutBounds().getWidth();
            double viewH = imageView.getLayoutBounds().getHeight();

            double scale = Math.min(viewW / 800.0, viewH / 600.0);
            double actualW = 800.0 * scale;
            double actualH = 600.0 * scale;

            double offsetX = (viewW - actualW) / 2.0;
            double offsetY = (viewH - actualH) / 2.0;

            double mappedX = (event.getX() - offsetX) / scale;
            double mappedY = (event.getY() - offsetY) / scale;

            if (mappedX >= 0 && mappedX <= 800 && mappedY >= 0 && mappedY <= 600) {
                lastClickScreenX = event.getScreenX();
                lastClickScreenY = event.getScreenY();
                currentRenderer.registerClick((int) mappedX, (int) mappedY);
            }

            lastMouseX = event.getX();
            lastMouseY = event.getY();
        });

        imageView.setOnMouseDragged(event -> {
            double deltaX = event.getX() - lastMouseX;
            double deltaY = event.getY() - lastMouseY;

            if (currentRenderer.getSelectedObjectId() != -1) {
                if (event.isPrimaryButtonDown()) {
                    currentRenderer.rotateSelectedObject((float) deltaX, (float) deltaY);
                } else if (event.isSecondaryButtonDown()) {
                    currentRenderer.moveSelectedObject((float) deltaX, (float) deltaY);
                }
            }

            lastMouseX = event.getX();
            lastMouseY = event.getY();
        });

        imageView.setOnScroll(event -> {
            if (currentRenderer != null && currentRenderer.getSelectedObjectId() != -1) {
                currentRenderer.scaleSelectedObject((float) event.getDeltaY() * 0.005f);
            }
        });

        renderThread = new Thread(currentRenderer);
        renderThread.setDaemon(true);
        renderThread.start();

        currentRenderer.queueModelLoad(modelPath);
    }

    private void onSelectionChanged(int objectId)
    {
        if (objectId == -1)
        {
            selectionPopup.hide();
            return;
        }

        Label label = new Label("Object #" + objectId);
        label.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        Button closeBtn = new Button("X");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> selectionPopup.hide());
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-cursor: hand;"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(label, spacer, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefWidth(180);

        Button openWindowButton = new Button("Open dedicated window");
        openWindowButton.setMaxWidth(Double.MAX_VALUE);
        openWindowButton.setOnAction(e -> {
            selectionPopup.hide();
            openObjectWindow(objectId);
        });

        Button deleteButton = new Button("Delete Object");
        deleteButton.setMaxWidth(Double.MAX_VALUE);
        deleteButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-cursor: hand;");
        deleteButton.setOnAction(e -> {
            selectionPopup.hide();
            if (currentRenderer != null) {
                currentRenderer.queueDeleteObject(objectId);
            }
            Stage dedicatedWindow = openObjectWindows.remove(objectId);
            if (dedicatedWindow != null) {
                dedicatedWindow.close();
            }
        });
        VBox content = new VBox(8, header, openWindowButton, deleteButton);
        content.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10; -fx-border-color: #555; -fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3;");
        selectionPopup.getContent().setAll(content);
        Stage ownerWindow = (Stage) canvasPlaceholder.getScene().getWindow();
        selectionPopup.show(ownerWindow, lastClickScreenX, lastClickScreenY);
    }

    private void openObjectWindow(int objectId)
    {
        Stage existing = openObjectWindows.get(objectId);
        if (existing != null)
        {
            existing.toFront();
            existing.requestFocus();
            return;
        }

        SceneObject target = null;
        for (SceneObject obj : currentRenderer.objects)
        {
            if (obj.getId() == objectId)
            {
                target = obj;
                break;
            }
        }
        if (target == null) return;

        int viewSize = 500;
        WritableImage frameBufferImage = new WritableImage(viewSize, viewSize);
        ImageView imageView = new ImageView(frameBufferImage);
        imageView.setFitWidth(viewSize);
        imageView.setFitHeight(viewSize);
        imageView.setPreserveRatio(true);

        SingleObjectRenderer objectRenderer = new SingleObjectRenderer(target.getSourcePath(), frameBufferImage, viewSize, viewSize);

        double[] lastX = {0};
        double[] lastY = {0};
        imageView.setOnMousePressed(event -> {
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnMouseDragged(event -> {
            objectRenderer.rotate((float) (event.getX() - lastX[0]), (float) (event.getY() - lastY[0]));
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnScroll(event -> objectRenderer.scale((float) event.getDeltaY() * 0.005f));

        Button computeButton = new Button("Calculeaza curbura");
        computeButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        computeButton.setMaxWidth(Double.MAX_VALUE);
        computeButton.setOnAction(e -> objectRenderer.requestComputeCurvature());

        /*Label equationsLabel = new Label("Apasă \"Calculeaza curbura\" pentru rezultat.");
        equationsLabel.setStyle("-fx-font-family: monospace; -fx-text-fill: #cccccc;");
        equationsLabel.setWrapText(true);
        objectRenderer.setOnCurvatureComputed(result -> equationsLabel.setText(String.format(
                "Exterior: %.2fx + %.2fy + %.2fz + %.2f = 0%nInterior: %.2fx + %.2fy + %.2fz + %.2f = 0",
                result.exteriorPlaneNormal.x, result.exteriorPlaneNormal.y, result.exteriorPlaneNormal.z,
                -result.exteriorPlaneNormal.dot(result.exteriorPlanePoint),
                result.interiorPlaneNormal.x, result.interiorPlaneNormal.y, result.interiorPlaneNormal.z,
                -result.interiorPlaneNormal.dot(result.interiorPlanePoint))));*/

        Slider yawSlider = new Slider(0, 360, 0);
        Slider pitchSlider = new Slider(-89, 89, 0);
        Slider offsetSlider = new Slider(-2.5, 2.5, 0);
        Slider thicknessSlider = new Slider(0, 1.5, 0.0);

        objectRenderer.setCrossSectionThickness(0.0f);

        yawSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionYaw((float) Math.toRadians(newVal.doubleValue())));
        pitchSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionPitch((float) Math.toRadians(newVal.doubleValue())));
        offsetSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionOffset(newVal.floatValue()));
        thicknessSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionThickness(newVal.floatValue()));

        Button cutButton = new Button("Decupeaza sectiune");
        cutButton.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        cutButton.setMaxWidth(Double.MAX_VALUE);
        cutButton.setOnAction(e -> objectRenderer.requestComputeCrossSection());

        Button captureTopViewButton = new Button("Vedere de sus a sectiunii");
        captureTopViewButton.setStyle("-fx-background-color: #6f42c1; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        captureTopViewButton.setMaxWidth(Double.MAX_VALUE);
        objectRenderer.setOnTopViewCaptured(this::openTopViewPreview);
        captureTopViewButton.setOnAction(e -> objectRenderer.requestTopViewCapture());

        Label l1 = new Label("Secțiune 2D — unghi orizontal"); l1.setStyle("-fx-text-fill: white;");
        Label l2 = new Label("Unghi vertical"); l2.setStyle("-fx-text-fill: white;");
        Label l3 = new Label("Poziție plan"); l3.setStyle("-fx-text-fill: white;");
        Label l4 = new Label("Grosime plan"); l4.setStyle("-fx-text-fill: white;");

        ///
        VBox curvatureControls = new VBox(8, computeButton);
        curvatureControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        VBox crossSectionControls = new VBox(8,
                l1, yawSlider,
                l2, pitchSlider,
                l3, offsetSlider,
                l4, thicknessSlider,
                cutButton, captureTopViewButton);
        crossSectionControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        VBox controls = new VBox(15, curvatureControls, crossSectionControls);
        controls.setStyle("-fx-padding: 15; -fx-background-color: #2b2b2b;");

        ScrollPane scrollPane = new ScrollPane(controls);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: #2b2b2b; -fx-border-color: #2b2b2b;");

        VBox root = new VBox(imageView, scrollPane);
        root.setStyle("-fx-background-color: #2b2b2b;");

        Thread objectRenderThread = new Thread(objectRenderer);
        objectRenderThread.setDaemon(true);
        objectRenderThread.start();

        Stage objectStage = new Stage();
        objectStage.setTitle("Analiza Obiect #" + objectId);

        Scene scene = new Scene(root, viewSize, 850);
        objectStage.setScene(scene);

        Rectangle2D screenBounds = Screen.getPrimary().getVisualBounds();
        if (850 > screenBounds.getHeight()) {
            objectStage.setHeight(screenBounds.getHeight() - 40);
        }

        objectStage.setOnCloseRequest(e -> {
            objectRenderThread.interrupt();
            openObjectWindows.remove(objectId);
        });
        objectStage.show();

        openObjectWindows.put(objectId, objectStage);
    }

    private void openTopViewPreview(WritableImage image)
    {
        ImageView previewView = new ImageView(image);
        previewView.setFitWidth(400);
        previewView.setFitHeight(400);
        previewView.setPreserveRatio(true);

        Stage previewStage = new Stage();
        previewStage.setTitle("Previzualizare sectiune");

        Button saveButton = new Button("Salveaza pe disc");
        saveButton.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        saveButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Salveaza imaginea sectiunii");
            fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imagine PNG", "*.png"));
            fileChooser.setInitialFileName("sectiune.png");
            File file = fileChooser.showSaveDialog(previewStage);
            if (file != null)
            {
                try
                {
                    ImageExporter.savePng(image, file);
                    previewStage.close();
                }
                catch (IOException ex)
                {
                    ex.printStackTrace();
                }
            }
        });

        Button discardButton = new Button("Renunta");
        discardButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        discardButton.setOnAction(e -> previewStage.close());

        HBox buttons = new HBox(10, saveButton, discardButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setStyle("-fx-padding: 10;");

        VBox root = new VBox(previewView, buttons);
        root.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(root, 420, 460));
        previewStage.show();
    }
}