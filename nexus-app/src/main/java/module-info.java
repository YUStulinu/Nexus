/**
 * The NEXUS desktop application (JavaFX): the node editor, the palette and inspector, the live
 * node views, the run timeline and log.
 */
module nexus.app {
    // transitive: the exported panels and views show JavaFX, core and engine types in their API
    requires transitive javafx.controls;
    requires transitive nexus.core;
    requires transitive nexus.engines;
    requires nexus.games;
    requires transitive nexus.store;
    requires nexus.ml;
    requires java.logging;

    exports nexus.app;                      // the Application class, and Workspace (used by the exported panels)
    exports nexus.app.canvas;
    exports nexus.app.panels;
    exports nexus.app.views;
    exports nexus.app.util;

    uses nexus.core.registry.NodeLibrary;
}
