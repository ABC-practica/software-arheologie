package org.project;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.project.engine.OpenGLRenderer;

import java.io.File;

public class MainController
{
    @FXML
    private StackPane canvasPlaceholder;

    private Thread renderThread;

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
        canvasPlaceholder.getChildren().clear();
        WritableImage frameBufferImage = new WritableImage(800, 600);
        ImageView imageView = new ImageView(frameBufferImage);
        imageView.fitWidthProperty().bind(canvasPlaceholder.widthProperty());
        imageView.fitHeightProperty().bind(canvasPlaceholder.heightProperty());
        imageView.setPreserveRatio(true);
        canvasPlaceholder.getChildren().add(imageView);

        if (renderThread != null && renderThread.isAlive()) {
            renderThread.interrupt();
        }
        try
        {
            renderThread.sleep(500);
        }
        catch (InterruptedException e)
        {
            e.printStackTrace();
        }

        OpenGLRenderer renderer = new OpenGLRenderer(modelPath, frameBufferImage);
        renderThread = new Thread(renderer);
        renderThread.setDaemon(true);
        renderThread.start();
    }
    private void setUpCameraMovement(OpenGLRenderer renderer)
    {

    }
}