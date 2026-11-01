package com.neonconv;

import javafx.animation.AnimationTimer;
import javafx.geometry.Pos;
import javafx.scene.effect.BlurType;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;

/**
 * A row of glowing bars that dance while {@link #setActive(boolean) active} and settle to a quiet
 * baseline otherwise. The {@link AnimationTimer} only runs while there is something to animate, so
 * an idle window costs nothing. Bars are animated with {@code scaleY}, which never triggers layout.
 */
public final class WaveVisualizer extends HBox {

    private static final int BARS = 30;
    private static final double BAR_WIDTH = 4;
    private static final double MAX_HEIGHT = 34;
    private static final double REST_SCALE = 0.10;

    private final Rectangle[] bars = new Rectangle[BARS];
    private final double[] phase = new double[BARS];
    private final double[] speed = new double[BARS];

    private boolean active;
    private double level;   // 0 = flat, 1 = full motion; eased towards the target

    private final AnimationTimer timer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            double t = now / 1_000_000_000.0;
            level += ((active ? 1.0 : 0.0) - level) * 0.07;

            for (int i = 0; i < BARS; i++) {
                double wave = 0.5 + 0.5 * Math.sin(t * speed[i] + phase[i]);
                double swell = 0.55 + 0.45 * Math.sin(t * 0.9 + i * 0.35);
                double scale = REST_SCALE + level * (1 - REST_SCALE) * wave * swell;
                bars[i].setScaleY(Math.max(REST_SCALE, Math.min(1.0, scale)));
            }
            if (!active && level < 0.005) {
                for (Rectangle bar : bars) {
                    bar.setScaleY(REST_SCALE);
                }
                stop();
            }
        }
    };

    public WaveVisualizer() {
        setSpacing(3);
        setAlignment(Pos.CENTER_LEFT);
        setMinHeight(MAX_HEIGHT);
        setPrefHeight(MAX_HEIGHT);
        setMaxHeight(MAX_HEIGHT);
        setMouseTransparent(true);

        for (int i = 0; i < BARS; i++) {
            double mix = i / (double) (BARS - 1);
            Color color = NeonFx.CYAN.interpolate(NeonFx.PINK, mix);
            Rectangle bar = new Rectangle(BAR_WIDTH, MAX_HEIGHT, color);
            bar.setArcWidth(BAR_WIDTH);
            bar.setArcHeight(BAR_WIDTH);
            bar.setScaleY(REST_SCALE);
            bar.setEffect(new DropShadow(BlurType.GAUSSIAN, color.deriveColor(0, 1, 1, 0.8), 7, 0.25, 0, 0));
            bars[i] = bar;
            phase[i] = i * 0.9;
            speed[i] = 2.2 + (i * 37 % 11) * 0.45;   // deterministic, irregular speeds
        }
        getChildren().addAll(bars);
    }

    public void setActive(boolean value) {
        active = value;
        if (value) {
            timer.start();
        }
    }
}
