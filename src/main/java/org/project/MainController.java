package org.project;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.project.engine.*;

import java.io.File;

public class MainController
{
    @FXML
    private StackPane canvasPlaceholder;

    private Thread renderThread;

    private OpenGLRenderer currentRenderer;
    private int nextObjectId = 1;
    private double lastMouseX = 0;
    private double lastMouseY = 0;

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
    private void setUpCameraMovement(OpenGLRenderer renderer)
    {

    }
}