package com.neonconv;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseEvent;
import javafx.scene.paint.Color;
import javafx.util.Duration;

/**
 * Motion helpers. JavaFX CSS has no transitions or keyframes, so every "smooth" glow, press and
 * pulse effect is driven from here, while colours and shapes stay in style.css.
 */
final class NeonFx {

    static final Color CYAN = Color.web("#00F5FF");
    static final Color PINK = Color.web("#FF0055");
    static final Color GREEN = Color.web("#00FF66");

    private NeonFx() { }

    static String stylesheet() {
        return NeonFx.class.getResource("/com/neonconv/style.css").toExternalForm();
    }

    /** Gives every {@code .neon-button} and {@code .convert-button} below {@code root} a smooth hover/press glow. */
    static void autoGlow(Parent root) {
        for (Node node : root.lookupAll(".neon-button")) {
            if (node instanceof ButtonBase button) {
                boolean small = button.getStyleClass().contains("small");
                new Glow(button, colorFor(button), small ? 3 : 6, small ? 12 : 18, small ? 7 : 10);
            }
        }
        for (Node node : root.lookupAll(".convert-button")) {
            if (node instanceof ButtonBase button) {
                new Glow(button, PINK, 24, 48, 16);
            }
        }
    }

    private static Color colorFor(Node node) {
        if (node.getStyleClass().contains("pink")) {
            return PINK;
        }
        if (node.getStyleClass().contains("green")) {
            return GREEN;
        }
        return CYAN;
    }

    /** Eased glow on hover, tighter glow plus a slight shrink while pressed. */
    private static final class Glow {
        private final Node node;
        private final DropShadow shadow;
        private final double idle;
        private final double hover;
        private final double press;
        private Timeline timeline;

        Glow(Node node, Color color, double idle, double hover, double press) {
            this.node = node;
            this.idle = idle;
            this.hover = hover;
            this.press = press;
            this.shadow = new DropShadow(BlurType.GAUSSIAN, color, idle, 0.12, 0, 0);

            node.setEffect(node.isDisabled() ? null : shadow);
            node.disableProperty().addListener((o, was, disabled) -> node.setEffect(disabled ? null : shadow));

            node.addEventHandler(MouseEvent.MOUSE_ENTERED, e -> to(hover, 0.28, 1.0));
            node.addEventHandler(MouseEvent.MOUSE_EXITED, e -> to(idle, 0.12, 1.0));
            node.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> to(press, 0.45, 0.97));
            node.addEventHandler(MouseEvent.MOUSE_RELEASED, e ->
                    to(node.isHover() ? hover : idle, node.isHover() ? 0.28 : 0.12, 1.0));
        }

        private void to(double radius, double spread, double scale) {
            if (timeline != null) {
                timeline.stop();
            }
            Interpolator ease = Interpolator.EASE_BOTH;
            timeline = new Timeline(new KeyFrame(Duration.millis(170),
                    new KeyValue(shadow.radiusProperty(), radius, ease),
                    new KeyValue(shadow.spreadProperty(), spread, ease),
                    new KeyValue(node.scaleXProperty(), scale, ease),
                    new KeyValue(node.scaleYProperty(), scale, ease)));
            timeline.play();
        }
    }

    /** Endless breathing glow (radius + opacity) used for the drop-zone border. */
    static final class Pulse {
        private final DropShadow shadow;
        private final Timeline timeline;

        Pulse(Node target, Color color, double minRadius, double maxRadius, Duration halfPeriod) {
            shadow = new DropShadow(BlurType.GAUSSIAN, color, minRadius, 0.2, 0, 0);
            target.setEffect(shadow);
            Interpolator ease = Interpolator.EASE_BOTH;
            timeline = new Timeline(
                    new KeyFrame(Duration.ZERO,
                            new KeyValue(shadow.radiusProperty(), minRadius, ease),
                            new KeyValue(target.opacityProperty(), 0.55, ease)),
                    new KeyFrame(halfPeriod,
                            new KeyValue(shadow.radiusProperty(), maxRadius, ease),
                            new KeyValue(target.opacityProperty(), 1.0, ease)));
            timeline.setAutoReverse(true);
            timeline.setCycleCount(Animation.INDEFINITE);
            timeline.play();
        }

        void setColor(Color color) { shadow.setColor(color); }

        void setRate(double rate) { timeline.setRate(rate); }
    }
}
