/**
 * The NEXUS desktop application (JavaFX): the node editor, the palette and inspector, the live
 * node views, the run timeline and log.
 */
module nexus.app {
    requires javafx.controls;
    requires nexus.core;
    requires nexus.engines;
    requires nexus.games;
    requires nexus.store;
    requires nexus.ml;
    requires java.logging;

    exports nexus.app to javafx.graphics;
    exports nexus.app.canvas;
    exports nexus.app.panels;
    exports nexus.app.views;
    exports nexus.app.util;

    uses nexus.core.registry.NodeLibrary;
}
