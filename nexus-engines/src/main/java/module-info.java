/**
 * The external engines NEXUS drives - Ember (LLM inference, C++/CUDA), Kindling (a Romanian GPT,
 * Python), Anvil (training, Python/CUDA), Gambit (AlphaZero, C#) - supervised as child processes,
 * plus the node libraries that use them.
 */
module nexus.engines {
    requires transitive nexus.core;
    requires java.net.http;

    exports nexus.engines;
    exports nexus.engines.llm;

    provides nexus.core.registry.NodeLibrary with nexus.engines.llm.LlmNodes;
}
