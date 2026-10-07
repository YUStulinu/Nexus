/**
 * Machine learning in plain Java: a BERT sentence encoder (multilingual-e5) computed with the
 * Vector API from safetensors weights memory-mapped through the Foreign Function & Memory API, its
 * SentencePiece-Unigram tokenizer, an HNSW vector index, BM25 keyword search with reciprocal rank
 * fusion, and document ingestion (PDF, text, Markdown, HTML).
 */
module nexus.ml {
    requires transitive nexus.core;
    requires jdk.incubator.vector;
    requires org.apache.pdfbox;
    requires org.apache.commons.logging;   // used by PDFBox, an automatic module that cannot declare it
    requires java.net.http;

    exports nexus.ml.tensor;
    exports nexus.ml.text;
    exports nexus.ml.bert;
    exports nexus.ml.index;
    exports nexus.ml.docs;
    exports nexus.ml.nodes;

    provides nexus.core.registry.NodeLibrary with nexus.ml.nodes.RagNodes;
}
