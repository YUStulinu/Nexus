/**
 * Gambit inside NEXUS: Connect Four and Gomoku rules, the AlphaZero network (.gnet) evaluated
 * with the Vector API, Monte Carlo tree search, a perfect Connect Four solver, arenas with Elo,
 * and supervised self-play training through Gambit's own trainer.
 */
module nexus.games {
    requires transitive nexus.core;
    requires nexus.engines;
    requires jdk.incubator.vector;

    exports nexus.games.rules;
    exports nexus.games.net;
    exports nexus.games.search;
    exports nexus.games.play;
    exports nexus.games.train;
    exports nexus.games.nodes;

    provides nexus.core.registry.NodeLibrary with nexus.games.nodes.GameNodes;
}
