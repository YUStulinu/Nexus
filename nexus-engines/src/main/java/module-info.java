/**
 * The external engines NEXUS drives - Ember (LLM inference, C++/CUDA), Kindling (a Romanian GPT,
 * Python), Anvil (training, Python/CUDA), Gambit (AlphaZero, C#) - supervised as child processes,
 * plus the node libraries that use them.
 */
module nexus.engines {
    requires transitive nexus.core;
    requires java.net.http;
    requires jdk.management;

    exports nexus.engines;
    exports nexus.engines.llm;
    exports nexus.engines.system;
    exports nexus.engines.train;

    provides nexus.core.registry.NodeLibrary with nexus.engines.llm.LlmNodes, nexus.engines.train.TrainingNodes;
}
