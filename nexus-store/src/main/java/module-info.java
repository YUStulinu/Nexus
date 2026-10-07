/**
 * Persistence for NEXUS: Tessera, the crash-safe key-value store written in C, bound through the
 * Foreign Function &amp; Memory API (with a plain-file fallback), and on top of it the versions of
 * workflows and the history of their runs.
 */
module nexus.store {
    requires transitive nexus.core;

    exports nexus.store;
}
