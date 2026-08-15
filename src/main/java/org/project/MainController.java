package org.project;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import org.project.engine.*;

import java.io.File;
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
        Button openWindowButton = new Button("Open dedicate window");
        openWindowButton.setOnAction(e -> openObjectWindow(objectId));

        VBox content = new VBox(8, label, openWindowButton);
        content.setStyle("-fx-background-color: #2b2b2b; -fx-padding: 10; -fx-border-color: #555; -fx-border-width: 1;");
        label.setStyle("-fx-text-fill: white;");

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

        Slider angleSlider = new Slider(0, 180, 0);
        Slider offsetSlider = new Slider(-2.5, 2.5, 0);
        angleSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setPlaneAngle((float) Math.toRadians(newVal.doubleValue())));
        offsetSlider.valueProperty().addListener((obs, oldVal, newVal) ->
                objectRenderer.setPlaneOffset(newVal.floatValue()));

        Button computeButton = new Button("Calculează curbura");
        computeButton.setOnAction(e -> objectRenderer.requestComputeCurvature());

        VBox controls = new VBox(6,
                new Label("Unghi plan"), angleSlider,
                new Label("Poziție plan"), offsetSlider,
                computeButton);
        controls.setStyle("-fx-padding: 10;");

        VBox root = new VBox(imageView, controls);

        Thread objectRenderThread = new Thread(objectRenderer);
        objectRenderThread.setDaemon(true);
        objectRenderThread.start();

        Stage objectStage = new Stage();
        objectStage.setTitle("Obiect #" + objectId);
        objectStage.setScene(new Scene(root, viewSize, viewSize + 160));
        objectStage.setOnCloseRequest(e -> {
            objectRenderThread.interrupt();
            openObjectWindows.remove(objectId);
        });
        objectStage.show();

        openObjectWindows.put(objectId, objectStage);
    }
}