package com.neonconv;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.Duration;

import java.util.Locale;

/**
 * Modal, borderless dialog that matches the app theme. Replaces {@code javafx.scene.control.Alert},
 * whose chrome cannot be themed consistently across platforms.
 */
final class NeonDialog {

    enum Kind { DANGER, SUCCESS, INFO }

    private NeonDialog() { }

    /**
     * @param buttons labels; the first one is the primary (accent coloured, right-most) button
     * @return index of the clicked button, or {@code -1} when dismissed with Esc
     */
    static int show(Window owner, Kind kind, String title, String message, String... buttons) {
        Stage stage = new Stage(StageStyle.TRANSPARENT);
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.initModality(Modality.APPLICATION_MODAL);

        int[] result = {-1};

        Label heading = new Label(title);
        heading.getStyleClass().add("dialog-title");

        Label body = new Label(message);
        body.getStyleClass().add("dialog-message");
        body.setWrapText(true);
        body.setMaxWidth(440);
        body.setMinHeight(Region.USE_PREF_SIZE);

        String accent = switch (kind) {
            case DANGER -> "pink";
            case SUCCESS -> "green";
            case INFO -> "cyan";
        };
        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER_RIGHT);
        for (int i = 0; i < buttons.length; i++) {
            Button button = new Button(buttons[i]);
            button.getStyleClass().addAll("neon-button", i == 0 ? accent : "ghost");
            int index = i;
            button.setOnAction(e -> {
                result[0] = index;
                stage.close();
            });
            actions.getChildren().add(0, button);   // first label ends up right-most
        }

        VBox card = new VBox(18, heading, body, actions);
        card.getStyleClass().addAll("neon-dialog", kind.name().toLowerCase(Locale.ROOT));
        card.setMaxWidth(500);

        StackPane root = new StackPane(card);
        root.getStyleClass().add("dialog-root");
        root.setPadding(new Insets(44));

        Scene scene = new Scene(root, Color.TRANSPARENT);
        scene.getStylesheets().add(NeonFx.stylesheet());
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                stage.close();
            }
        });
        stage.setScene(scene);

        // the card can be dragged around, since the window has no title bar
        double[] grab = new double[2];
        card.setOnMousePressed(e -> {
            grab[0] = e.getScreenX() - stage.getX();
            grab[1] = e.getScreenY() - stage.getY();
        });
        card.setOnMouseDragged(e -> {
            stage.setX(e.getScreenX() - grab[0]);
            stage.setY(e.getScreenY() - grab[1]);
        });

        card.setOpacity(0);
        card.setScaleX(0.94);
        card.setScaleY(0.94);
        stage.setOnShown(e -> {
            if (owner != null) {
                stage.setX(owner.getX() + (owner.getWidth() - stage.getWidth()) / 2);
                stage.setY(owner.getY() + (owner.getHeight() - stage.getHeight()) / 2);
            }
            Interpolator ease = Interpolator.EASE_OUT;
            new Timeline(new KeyFrame(Duration.millis(220),
                    new KeyValue(card.opacityProperty(), 1, ease),
                    new KeyValue(card.scaleXProperty(), 1, ease),
                    new KeyValue(card.scaleYProperty(), 1, ease))).play();
        });

        stage.showAndWait();
        return result[0];
    }
}
