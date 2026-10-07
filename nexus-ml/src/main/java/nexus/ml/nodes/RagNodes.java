package nexus.ml.nodes;

import static nexus.core.graph.PortSpec.in;
import static nexus.core.graph.PortSpec.out;
import static nexus.core.types.DataTypes.TABLE;
import static nexus.core.types.DataTypes.TEXT;
import static nexus.core.types.DataTypes.TEXT_LIST;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import nexus.core.exec.NodeContext;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.ParamSpec;
import nexus.core.nodes.FileKeys;
import nexus.core.registry.NodeLibrary;
import nexus.core.types.DataType;
import nexus.core.types.DataTypes;
import nexus.core.types.Table;
import nexus.ml.bert.BertEncoder;
import nexus.ml.bert.EmbeddingCache;
import nexus.ml.bert.EmbeddingModels;
import nexus.ml.docs.Chunker;
import nexus.ml.docs.Chunker.Chunk;
import nexus.ml.docs.Document;
import nexus.ml.docs.DocumentLoader;
import nexus.ml.index.Bm25Index;
import nexus.ml.index.HnswIndex;
import nexus.ml.index.SearchIndex;

/**
 * Retrieval-augmented generation as nodes: load documents, cut them into passages, index the
 * passages (embeddings computed in Java + HNSW + BM25), search. The search node's numbered context
 * goes straight into the "Answer from context" language-model node.
 */
public final class RagNodes implements NodeLibrary {
    public static final String CATEGORY = "Documents and search";

    public static final DataType DOCUMENTS = DataTypes.register(new DataType("documents", "Documents", "#94d82d", List.class));
    public static final DataType CHUNKS = DataTypes.register(new DataType("chunks", "Passages", "#38d9a9", List.class));
    public static final DataType INDEX = DataTypes.register(new DataType("search-index", "Search index", "#9775fa", SearchIndex.class));

    static {
        DataTypes.registerConversion(DOCUMENTS, TEXT, v -> {
            var sb = new StringBuilder();
            for (var d : RagNodes.<Document>list(v)) sb.append("# ").append(d.title()).append("\n\n").append(d.text()).append("\n\n");
            return sb.toString().strip();
        });
        DataTypes.registerConversion(CHUNKS, TEXT_LIST, v -> RagNodes.<Chunk>list(v).stream().map(Chunk::text).toList());
        DataTypes.registerConversion(CHUNKS, TEXT, v -> String.join("\n\n", RagNodes.<Chunk>list(v).stream().map(Chunk::text).toList()));
    }

    public RagNodes() {
    }

    @SuppressWarnings("unchecked")
    static <T> List<T> list(Object v) {
        return (List<T>) v;
    }

    @Override
    public String name() {
        return CATEGORY;
    }

    @Override
    public List<NodeDefinition> definitions() {
        return List.of(load(), chunk(), build(), search());
    }

    static ParamSpec modelParam() {
        return ParamSpec.choice("model", "Embedding model", EmbeddingModels.DEFAULT, List.copyOf(EmbeddingModels.REPOS.keySet()),
                                "Computed in Java on the CPU; downloaded on first use");
    }

    static BertEncoder encoder(NodeContext ctx) throws Exception {
        String name = ctx.paramText("model");
        return EmbeddingModels.get(name.isBlank() ? EmbeddingModels.DEFAULT : name, (f, msg) -> ctx.progress(f, msg));
    }

    static NodeDefinition load() {
        return NodeDefinition.builder("docs.load", "Load documents", CATEGORY)
                .description("Reads PDFs (page by page), text, Markdown and HTML from a file or a whole folder.")
                .output(out("documents", "documents", DOCUMENTS)).output(out("summary", "summary", TABLE)).view("table")
                .param(ParamSpec.folder("folder", "Folder", "Every supported file in it (and its subfolders)"))
                .param(ParamSpec.file("file", "or a file", "A single document"))
                .contentKey(p -> FileKeys.of(String.valueOf(p.getOrDefault("folder", ""))) + "|" + FileKeys.of(String.valueOf(p.getOrDefault("file", ""))))
                .behavior(() -> ctx -> {
                    var paths = new ArrayList<Path>();
                    if (!ctx.paramText("folder").isBlank()) paths.addAll(DocumentLoader.files(Path.of(ctx.paramText("folder"))));
                    if (!ctx.paramText("file").isBlank()) paths.add(Path.of(ctx.paramText("file")));
                    if (paths.isEmpty()) throw new IllegalArgumentException("choose a folder or a file");
                    var docs = new ArrayList<Document>();
                    var table = Table.builder("document", "pages", "characters");
                    for (int i = 0; i < paths.size(); i++) {
                        ctx.checkCancelled();
                        ctx.progress((double) i / paths.size(), paths.get(i).getFileName().toString());
                        try {
                            var d = DocumentLoader.load(paths.get(i));
                            docs.add(d);
                            table.row(d.title(), (double) d.pages().size(), (double) d.characters());
                        } catch (java.io.IOException | RuntimeException e) {      // one unreadable file must not stop the rest
                            ctx.log("skipped " + paths.get(i).getFileName() + ": " + e.getMessage());
                        }
                    }
                    ctx.log(docs.size() + " documents, " + docs.stream().mapToInt(d -> d.pages().size()).sum() + " pages");
                    ctx.output("documents", List.copyOf(docs));
                    ctx.output("summary", table.build());
                });
    }

    static NodeDefinition chunk() {
        return NodeDefinition.builder("docs.chunk", "Split into passages", CATEGORY)
                .description("Cuts documents into passages along headings and sentences, with overlap.")
                .input(in("documents", "documents", DOCUMENTS))
                .output(out("chunks", "passages", CHUNKS)).output(out("table", "table", TABLE)).view("table")
                .param(ParamSpec.integer("maxTokens", "Tokens per passage", 220, 32, 500, "Budget per passage (the model reads at most 512)"))
                .param(ParamSpec.integer("overlap", "Overlap (sentences)", 1, 0, 5, "Sentences repeated between neighbouring passages"))
                .param(modelParam())
                .behavior(() -> ctx -> {
                    List<Document> docs = list(ctx.input("documents"));
                    var tok = encoder(ctx).tokenizer();
                    var chunks = new Chunker(ctx.paramInt("maxTokens"), ctx.paramInt("overlap"), tok::count).chunk(docs);
                    var table = Table.builder("#", "source", "heading", "tokens", "passage");
                    for (var c : chunks)
                        table.row((double) (c.id() + 1), c.citation(), c.heading(), (double) tok.count(c.text()),
                                  c.text().length() > 120 ? c.text().substring(0, 117) + "..." : c.text());
                    ctx.log(chunks.size() + " passages");
                    ctx.output("chunks", chunks);
                    ctx.output("table", table.build());
                });
    }

    static NodeDefinition build() {
        return NodeDefinition.builder("index.build", "Build search index", CATEGORY)
                .description("Embeds the passages (in Java, cached on disk) and indexes them for semantic and keyword search.")
                .input(in("chunks", "passages", CHUNKS))
                .output(out("index", "index", INDEX)).output(out("stats", "stats", TABLE)).view("table")
                .param(modelParam())
                .behavior(() -> ctx -> {
                    List<Chunk> chunks = list(ctx.input("chunks"));
                    var enc = encoder(ctx);
                    var cache = EmbeddingCache.forModel(enc.name());
                    var texts = chunks.stream().map(c -> "passage: " + c.embedText()).toList();
                    long t0 = System.nanoTime();
                    int hits0 = cache.hits();
                    float[][] vectors = cache.embedAll(enc, texts, n -> ctx.progress(0.95 * n / Math.max(1, texts.size()),
                                                                                      "embedding " + n + "/" + texts.size()));
                    long t1 = System.nanoTime();
                    ctx.checkCancelled();
                    ctx.progress(0.96, "indexing");
                    var hnsw = new HnswIndex(enc.dimension());
                    var bm25 = new Bm25Index();
                    for (int i = 0; i < chunks.size(); i++) {
                        hnsw.add(vectors[i]);
                        bm25.add(chunks.get(i).heading() + " " + chunks.get(i).text());
                    }
                    long t2 = System.nanoTime();
                    int reused = cache.hits() - hits0;
                    double embedS = (t1 - t0) / 1e9;
                    ctx.log("embedded " + (chunks.size() - reused) + " passages (" + reused + " from cache) in " + String.format("%.1f s", embedS));
                    ctx.output("index", new SearchIndex(chunks, hnsw, bm25, enc.name()));
                    ctx.output("stats", Table.builder("measure", "value")
                            .row("passages", (double) chunks.size())
                            .row("from cache", (double) reused)
                            .row("embedding s", Math.round(embedS * 100) / 100.0)
                            .row("passages/s", reused == chunks.size() ? 0.0 : Math.round((chunks.size() - reused) / embedS * 10) / 10.0)
                            .row("index ms", Math.round((t2 - t1) / 1e5) / 10.0)
                            .row("dimension", (double) enc.dimension()).build());
                });
    }

    static NodeDefinition search() {
        return NodeDefinition.builder("index.search", "Search", CATEGORY)
                .description("Finds the passages that best answer a question (hybrid: meaning + keywords).")
                .input(in("index", "index", INDEX)).input(in("query", "question", TEXT))
                .output(out("context", "context", TEXT)).output(out("hits", "hits", TABLE)).output(out("passages", "passages", TEXT_LIST))
                .view("search-hits")
                .param(ParamSpec.integer("k", "Results", 5, 1, 30, "How many passages"))
                .param(ParamSpec.choice("mode", "Mode", "hybrid", List.of("hybrid", "semantic", "keyword"),
                                        "Hybrid fuses meaning (embeddings) and words (BM25)"))
                .behavior(() -> ctx -> {
                    var index = ctx.input("index", SearchIndex.class);
                    String q = ctx.inputText("query");
                    if (q == null || q.isBlank()) throw new IllegalArgumentException("empty question");
                    var mode = SearchIndex.Mode.valueOf(ctx.paramText("mode").toUpperCase(java.util.Locale.ROOT));
                    long t0 = System.nanoTime();
                    float[] qv = mode == SearchIndex.Mode.KEYWORD ? null : EmbeddingModels.get(index.model(), null).embed("query: " + q);
                    var results = index.search(qv, q, ctx.paramInt("k"), mode);
                    double ms = (System.nanoTime() - t0) / 1e6;
                    ctx.log(String.format("%d results in %.1f ms", results.size(), ms));
                    var table = Table.builder("#", "source", "similarity", "semantic rank", "keyword rank", "passage");
                    for (int i = 0; i < results.size(); i++) {
                        var r = results.get(i);
                        String t = r.chunk().text();
                        table.row((double) (i + 1), r.chunk().citation(), Math.round(r.similarity() * 1000) / 1000.0,
                                  r.semanticRank() == 0 ? "-" : String.valueOf(r.semanticRank()),
                                  r.keywordRank() == 0 ? "-" : String.valueOf(r.keywordRank()),
                                  t.length() > 140 ? t.substring(0, 137) + "..." : t);
                    }
                    ctx.output("context", SearchIndex.context(results));
                    ctx.output("hits", table.build());
                    ctx.output("passages", results.stream().map(r -> r.chunk().text()).toList());
                });
    }
}
