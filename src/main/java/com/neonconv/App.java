package com.neonconv;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.IOException;

/** Application launcher: loads the FXML, applies the neon stylesheet and starts the engine probe. */
public final class App extends Application {

    private MainController controller;

    @Override
    public void start(Stage stage) throws IOException {
        FXMLLoader loader = new FXMLLoader(App.class.getResource("main.fxml"));
        Parent root = loader.load();
        controller = loader.getController();
        controller.setHostServices(getHostServices());

        Scene scene = new Scene(root, 1200, 780, Color.web("#0D0E15"));
        scene.getStylesheets().add(NeonFx.stylesheet());

        stage.setTitle("MediaConverter x © mrverton");
        stage.setMinWidth(1020);
        stage.setMinHeight(700);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> {
            if (!controller.canClose()) {
                e.consume();
            }
        });
        stage.show();
        controller.onShown();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
