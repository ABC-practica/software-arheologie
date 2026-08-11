package org.project;

import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws IOException
    {
        FXMLLoader splashLoader = new FXMLLoader(getClass().getResource("/splash.fxml"));
        Parent splashRoot = splashLoader.load();
        Stage splashStage = new Stage();
        splashStage.initStyle(StageStyle.UNDECORATED);
        splashStage.setScene(new Scene(splashRoot));
        splashStage.show();

        PauseTransition delay = new PauseTransition(Duration.seconds(2));
        delay.setOnFinished(event -> {
            splashStage.close();
            try
            {
                showMainWindow(primaryStage);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        });
        delay.play();
    }

    private void showMainWindow(Stage primaryStage) throws IOException
    {
        FXMLLoader mainLoader = new FXMLLoader(getClass().getResource("/main.fxml"));
        Parent mainRoot = mainLoader.load();
        Scene scene = new Scene(mainRoot);
        primaryStage.setTitle("Vizualizator Arheologic");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args)
    {
        launch(args);
    }
}