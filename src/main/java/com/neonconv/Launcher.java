package com.neonconv;

/**
 * Entry point for the shaded jar. JavaFX refuses to start from a main class that extends
 * {@link javafx.application.Application} when it is launched from the classpath, so the jar
 * manifest points here instead.
 */
public final class Launcher {

    private Launcher() { }

    public static void main(String[] args) {
        App.main(args);
    }
}
