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
import javafx.stage.DirectoryChooser;
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
import java.util.Set;

public class MainController
{
    @FXML
    private StackPane canvasPlaceholder;

    private Thread renderThread;

    private OpenGLRenderer currentRenderer;
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
        fileChooser.setTitle("Alege un fisier 3D");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Modele 3D", "*.gltf", "*.glb", "*.obj")
        );
        Stage stage = (Stage) canvasPlaceholder.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);
        if (selectedFile != null)
        {
            initEngineIfNeeded();
            currentRenderer.queueModelLoad(selectedFile.getAbsolutePath());
        }
    }

    @FXML
    private void handleSectionUpload(ActionEvent event)
    {
        DirectoryChooser dirChooser = new DirectoryChooser();
        dirChooser.setTitle("Alege Folderul Sectiunii Arheologice");
        Stage stage = (Stage) canvasPlaceholder.getScene().getWindow();
        File selectedDir = dirChooser.showDialog(stage);
        if (selectedDir != null) {
            initEngineIfNeeded();
            currentRenderer.queueSectionLoad(selectedDir);
        }
    }

    @FXML
    private void handleResetScene(ActionEvent event) {
        if (currentRenderer != null) {
            for (SceneObject obj : currentRenderer.objects) {
                if (obj.parent != null) continue;

                obj.position.set(0, 0, 0);
                obj.rotation.set(0, 0, 0);

                if (obj.isSectionBox) {
                    obj.scale = 0.35f;
                } else {
                    obj.scale = 1.0f;
                }
            }
        }
    }

    @FXML
    private void handleClearScene(ActionEvent event) {
        if (currentRenderer != null) {
            currentRenderer.objects.clear();
            currentRenderer.clearSelection();
            selectionPopup.hide();
            SessionDatabase.clear();
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
        alert.setHeaderText("Instructiuni de manipulare");
        alert.setContentText("• CTRL + Click: Selecteaza mai multe fragmente simultan.\n\n"
                + "• Click Stanga + Tragere: Roteste obiectul selectat.\n\n"
                + "• Click Dreapta + Tragere: Muta obiectul.\n\n"
                + "• Rotita Mouse: Scaleaza obiectul.\n\n"
                + "• Click pe fundal: Pastreaza selectia curenta.");
        alert.showAndWait();
    }

    @FXML
    private void handleExit(ActionEvent event) {
        Platform.exit();
        System.exit(0);
    }

    private void initEngineIfNeeded()
    {
        if (renderThread != null && renderThread.isAlive() && currentRenderer != null) return;

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
                currentRenderer.registerClick((int) mappedX, (int) mappedY, event.isControlDown());
            }

            lastMouseX = event.getX();
            lastMouseY = event.getY();
        });

        imageView.setOnMouseDragged(event -> {
            double deltaX = event.getX() - lastMouseX;
            double deltaY = event.getY() - lastMouseY;

            if (currentRenderer != null && !currentRenderer.getSelectedObjectIds().isEmpty()) {
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
            if (currentRenderer != null && !currentRenderer.getSelectedObjectIds().isEmpty()) {
                currentRenderer.scaleSelectedObject((float) event.getDeltaY() * 0.005f);
            }
        });

        renderThread = new Thread(currentRenderer);
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void onSelectionChanged(Set<Integer> objectIds)
    {
        Set<Integer> filteredIds = new java.util.HashSet<>();
        for (int id : objectIds) {
            if (currentRenderer != null && !currentRenderer.isSectionObject(id)) {
                filteredIds.add(id);
            }
        }
        if (filteredIds.isEmpty())
        {
            selectionPopup.hide();
            return;
        }

        VBox content = new VBox(8);
        content.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10; -fx-border-color: #555; -fx-border-width: 1; -fx-border-radius: 3; -fx-background-radius: 3;");

        Label label = new Label(filteredIds.size() == 1 ? "Obiect #" + filteredIds.iterator().next() : filteredIds.size() + " Fragmente Selectate");
        label.setStyle("-fx-text-fill: white; -fx-font-weight: bold;");
        Button closeBtn = new Button("X");
        closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> {
            selectionPopup.hide();
            if (currentRenderer != null) currentRenderer.clearSelection();
        });
        closeBtn.setOnMouseEntered(e -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;"));
        closeBtn.setOnMouseExited(e -> closeBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #aaaaaa; -fx-font-weight: bold; -fx-cursor: hand;"));
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(label, spacer, closeBtn);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPrefWidth(180);
        content.getChildren().add(header);

        if (filteredIds.size() == 1) {
            int singleId = filteredIds.iterator().next();

            Button openWindowButton = new Button("Deschide fereastra");
            openWindowButton.setMaxWidth(Double.MAX_VALUE);
            openWindowButton.setOnAction(e -> {
                selectionPopup.hide();
                openObjectWindow(singleId);
            });

            Button deselectButton = new Button("Deselecteaza");
            deselectButton.setMaxWidth(Double.MAX_VALUE);
            deselectButton.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-cursor: hand;");
            deselectButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) currentRenderer.clearSelection();
            });

            Button deleteButton = new Button("Sterge obiectul");
            deleteButton.setMaxWidth(Double.MAX_VALUE);
            deleteButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-cursor: hand;");
            deleteButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) currentRenderer.queueDeleteObject(singleId);
                Stage dedicatedWindow = openObjectWindows.remove(singleId);
                if (dedicatedWindow != null) dedicatedWindow.close();
            });

            content.getChildren().addAll(openWindowButton, deselectButton, deleteButton);
        }
        else {
            Button generateButton = new Button("Genereaza Vas");
            generateButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
            generateButton.setMaxWidth(Double.MAX_VALUE);
            generateButton.setOnAction(e -> {
                try {
                    for (int id : filteredIds) {
                        if (!SessionDatabase.hasSection(id)) {
                            throw new IllegalStateException("Fragmentul cu ID-ul #" + id + " nu are sectiunea definita!");
                        }
                    }
                    selectionPopup.hide();
                    simulateAIRestoration(filteredIds);

                } catch (IllegalStateException ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Eroare de Validare");
                    alert.setHeaderText("Actiune respinsa: Sectiuni lipsa");
                    alert.setContentText(ex.getMessage() + "\n\nTe rugam sa deschizi fereastra dedicata pentru acest obiect si sa salvezi o sectiune.");
                    alert.showAndWait();
                }
            });

            Button deselectButton = new Button("Deselecteaza");
            deselectButton.setMaxWidth(Double.MAX_VALUE);
            deselectButton.setStyle("-fx-background-color: #6c757d; -fx-text-fill: white; -fx-cursor: hand;");
            deselectButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) currentRenderer.clearSelection();
            });

            Button deleteAllButton = new Button("Sterge Selectia");
            deleteAllButton.setMaxWidth(Double.MAX_VALUE);
            deleteAllButton.setStyle("-fx-background-color: #d9534f; -fx-text-fill: white; -fx-cursor: hand;");
            deleteAllButton.setOnAction(e -> {
                selectionPopup.hide();
                if (currentRenderer != null) {
                    for (int id : filteredIds) {
                        currentRenderer.queueDeleteObject(id);
                        Stage dedicatedWindow = openObjectWindows.remove(id);
                        if (dedicatedWindow != null) dedicatedWindow.close();
                    }
                }
            });

            content.getChildren().addAll(generateButton, deselectButton, deleteAllButton);
        }

        selectionPopup.getContent().setAll(content);
        Stage ownerWindow = (Stage) canvasPlaceholder.getScene().getWindow();
        selectionPopup.show(ownerWindow, lastClickScreenX, lastClickScreenY);
    }

    private void simulateAIRestoration(Set<Integer> objectIds) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Comunicare AI Backend");
        alert.setHeaderText("Trimitere date catre AI");
        alert.setContentText("Datele extrase pentru cele " + objectIds.size() + " fragmente sunt complete.\n\nSimulam procesarea... Te rugam sa selectezi modelul 3D pe care l-ar fi generat AI-ul pentru a fi afisat.");
        alert.showAndWait();

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Receptie Model AI - Alege Vaza Restaurata");
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Modele 3D", "*.gltf", "*.glb", "*.obj")
        );
        Stage stage = (Stage) canvasPlaceholder.getScene().getWindow();
        File selectedFile = fileChooser.showOpenDialog(stage);

        if (selectedFile != null)
        {
            openRestoredVaseWindow(selectedFile.getAbsolutePath());
        }
    }

    private void openRestoredVaseWindow(String modelPath) {
        int viewSize = 600;
        WritableImage frameBufferImage = new WritableImage(viewSize, viewSize);
        ImageView imageView = new ImageView(frameBufferImage);
        imageView.setFitWidth(viewSize);
        imageView.setFitHeight(viewSize);
        imageView.setPreserveRatio(true);

        SingleObjectRenderer vaseRenderer = new SingleObjectRenderer(modelPath, frameBufferImage, viewSize, viewSize);

        double[] lastX = {0};
        double[] lastY = {0};
        imageView.setOnMousePressed(event -> {
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnMouseDragged(event -> {
            vaseRenderer.rotate((float) (event.getX() - lastX[0]), (float) (event.getY() - lastY[0]));
            lastX[0] = event.getX();
            lastY[0] = event.getY();
        });
        imageView.setOnScroll(event -> vaseRenderer.scale((float) event.getDeltaY() * 0.005f));

        VBox root = new VBox(imageView);
        root.setStyle("-fx-background-color: #2b2b2b;");

        Thread renderThread = new Thread(vaseRenderer);
        renderThread.setDaemon(true);
        renderThread.start();

        Stage vaseStage = new Stage();
        vaseStage.setTitle("Rezultat Reconstructie AI");
        vaseStage.setScene(new Scene(root, viewSize, viewSize));
        vaseStage.setOnCloseRequest(e -> renderThread.interrupt());
        vaseStage.show();
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

        objectRenderer.setOnTopViewCaptured(image -> openTopViewPreview(objectId, image));

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

        Label fileLabel = new Label("Fisier: " + new File(target.getSourcePath()).getName());
        fileLabel.setStyle("-fx-text-fill: white; -fx-font-weight: bold; -fx-font-size: 14px;");

        Label posLabel = new Label(String.format("Pozitie  —  X: %.2f  |  Y: %.2f  |  Z: %.2f",
                target.position.x, target.position.y, target.position.z));
        posLabel.setStyle("-fx-text-fill: #cccccc;");

        Label rotLabel = new Label(String.format("Rotatie  —  X: %.1f°  |  Y: %.1f°  |  Z: %.1f°",
                Math.toDegrees(target.rotation.x), Math.toDegrees(target.rotation.y), Math.toDegrees(target.rotation.z)));
        rotLabel.setStyle("-fx-text-fill: #cccccc;");

        VBox infoBox = new VBox(6, fileLabel, posLabel, rotLabel);
        infoBox.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        Button computeButton = new Button("Calculeaza curbura");
        computeButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        computeButton.setMaxWidth(Double.MAX_VALUE);

        CheckBox extCheck = new CheckBox("Curbura interioara");
        extCheck.setDisable(true);
        extCheck.setStyle("-fx-text-fill: white;");
        extCheck.setOnAction(e -> objectRenderer.setShowExterior(extCheck.isSelected()));

        CheckBox intCheck = new CheckBox("Curbura exterioara");
        intCheck.setDisable(true);
        intCheck.setStyle("-fx-text-fill: white;");
        intCheck.setOnAction(e -> objectRenderer.setShowInterior(intCheck.isSelected()));

        Label widthLabel = new Label("Latime estimata: Nedeterminat");
        widthLabel.setStyle("-fx-text-fill: #ffcc00; -fx-font-weight: bold;");

        computeButton.setOnAction(e -> {
            computeButton.setText("Se calculeaza...");
            computeButton.setDisable(true);
            objectRenderer.requestComputeCurvature();
        });

        objectRenderer.setOnCurvatureComputed(result -> {
            computeButton.setText("Curbura calculata");
            extCheck.setDisable(false);
            intCheck.setDisable(false);

            float distance = result.exteriorPlanePoint.distance(result.interiorPlanePoint);
            widthLabel.setText(String.format("Latime estimata: %.3f unitati", distance));
        });

        Slider yawSlider = new Slider(0, 360, 0);
        Slider pitchSlider = new Slider(-89, 89, 0);
        Slider offsetSlider = new Slider(-2.5, 2.5, 0);

        yawSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionYaw((float) Math.toRadians(newVal.doubleValue())));
        pitchSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionPitch((float) Math.toRadians(newVal.doubleValue())));
        offsetSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setCrossSectionOffset(newVal.floatValue()));

        Button cutButton = new Button("Decupeaza si Previzualizeaza");
        cutButton.setStyle("-fx-background-color: #28a745; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        cutButton.setMaxWidth(Double.MAX_VALUE);
        cutButton.setOnAction(e -> {
            objectRenderer.requestComputeCrossSection();
            objectRenderer.requestTopViewCapture();
        });

        Label l1 = new Label("Sectiune 2D orizontala"); l1.setStyle("-fx-text-fill: white;");
        Label l2 = new Label("Sectiune verticala"); l2.setStyle("-fx-text-fill: white;");
        Label l3 = new Label("Pozitie plan"); l3.setStyle("-fx-text-fill: white;");

        VBox curvatureControls = new VBox(8, computeButton, extCheck, intCheck, widthLabel);
        curvatureControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        VBox crossSectionControls = new VBox(8,
                l1, yawSlider,
                l2, pitchSlider,
                l3, offsetSlider,
                cutButton);
        crossSectionControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        Button centerOfMassButton = new Button("Calculeaza centrul de greutate");
        centerOfMassButton.setStyle("-fx-background-color: #218838; -fx-text-fill: white; -fx-font-weight: bold; -fx-cursor: hand;");
        centerOfMassButton.setMaxWidth(Double.MAX_VALUE);

        Label centerOfMassLabel = new Label();
        centerOfMassLabel.setStyle("-fx-text-fill: #cccccc;");

        centerOfMassButton.setOnAction(e -> objectRenderer.requestComputeCenterOfMass());
        objectRenderer.setOnCenterOfMassComputed(point -> centerOfMassLabel.setText(String.format(
                "Centru de greutate — X: %.3f | Y: %.3f | Z: %.3f", point.x, point.y, point.z)));

        VBox centerOfMassControls = new VBox(8, centerOfMassButton, centerOfMassLabel);
        centerOfMassControls.setStyle("-fx-padding: 15; -fx-background-color: #383838; -fx-background-radius: 5;");

        VBox controls = new VBox(15, infoBox, curvatureControls, crossSectionControls, centerOfMassControls);
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
            if (currentRenderer != null) {
                currentRenderer.clearSelection();
            }
        });
        objectStage.show();

        openObjectWindows.put(objectId, objectStage);
    }

    private void openTopViewPreview(int objectId, WritableImage image)
    {
        ImageView previewView = new ImageView(image);
        previewView.setFitWidth(400);
        previewView.setFitHeight(400);
        previewView.setPreserveRatio(true);

        Stage previewStage = new Stage();
        previewStage.setTitle("Previzualizare Sectiune");

        Button saveToDbButton = new Button("Salveaza pentru AI");
        saveToDbButton.setStyle("-fx-background-color: #0078D7; -fx-text-fill: white; -fx-font-weight: bold;");
        saveToDbButton.setOnAction(e -> {
            SessionDatabase.saveSection(objectId, image);
            previewStage.close();
        });

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

        HBox buttons = new HBox(10, saveToDbButton, discardButton);
        buttons.setAlignment(Pos.CENTER);
        buttons.setStyle("-fx-padding: 10;");

        VBox root = new VBox(previewView, buttons);
        root.setStyle("-fx-background-color: #2b2b2b;");
        previewStage.setScene(new Scene(root, 420, 460));
        previewStage.show();
    }
}