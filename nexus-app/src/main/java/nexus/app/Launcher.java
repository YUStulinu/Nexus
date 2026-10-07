package nexus.app;

import javafx.application.Application;

/**
 * Entry point of the packaged application, where the jars run on the class path: JavaFX insists
 * that a class-path main class must not extend Application itself.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(NexusApp.class, args);
    }
}
