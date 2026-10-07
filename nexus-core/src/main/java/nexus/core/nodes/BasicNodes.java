package nexus.core.nodes;

import static nexus.core.graph.PortSpec.in;
import static nexus.core.graph.PortSpec.optionalIn;
import static nexus.core.graph.PortSpec.out;
import static nexus.core.types.DataTypes.ANY;
import static nexus.core.types.DataTypes.NUMBER;
import static nexus.core.types.DataTypes.TABLE;
import static nexus.core.types.DataTypes.TEXT;
import static nexus.core.types.DataTypes.TEXT_LIST;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import nexus.core.graph.NodeDefinition;
import nexus.core.graph.ParamSpec;
import nexus.core.registry.NodeLibrary;
import nexus.core.types.Table;
import nexus.core.types.TextStream;
import nexus.core.util.Expression;
import nexus.core.util.TextUtil;

/** The general-purpose nodes: text and numbers, files, the web, and views. */
public final class BasicNodes implements NodeLibrary {
    @Override
    public String name() {
        return "Basics";
    }

    @Override
    public List<NodeDefinition> definitions() {
        return List.of(text(), number(), template(), join(), transform(), split(), joinList(), stats(), wordFrequencies(),
                       readFile(), writeFile(), fetchUrl(), formula(), delay(), preview());
    }

    static NodeDefinition text() {
        return NodeDefinition.builder("text.input", "Text", "Input")
                .description("A piece of text you type in.")
                .output(out("text", "text", TEXT))
                .param(ParamSpec.multiline("text", "Text", "Bună! Scrie aici textul tău.", "The text this node outputs"))
                .view("text")
                .behavior(() -> ctx -> ctx.output("text", ctx.paramText("text")));
    }

    static NodeDefinition number() {
        return NodeDefinition.builder("number.input", "Number", "Input")
                .description("A number you choose.")
                .output(out("value", "value", NUMBER))
                .param(ParamSpec.number("value", "Value", 42, -1e12, 1e12, "The number this node outputs"))
                .view("text")
                .behavior(() -> ctx -> ctx.output("value", ctx.paramNumber("value")));
    }

    static NodeDefinition template() {
        return NodeDefinition.builder("text.template", "Template", "Text")
                .description("Fills {a}, {b} and {c} in a template with the connected texts.")
                .input(optionalIn("a", "a", TEXT)).input(optionalIn("b", "b", TEXT)).input(optionalIn("c", "c", TEXT))
                .output(out("text", "text", TEXT))
                .param(ParamSpec.multiline("template", "Template", "Summarise this in three sentences:\n\n{a}",
                                           "Use {a}, {b} and {c} for the inputs"))
                .behavior(() -> ctx -> {
                    String t = ctx.paramText("template");
                    for (var k : List.of("a", "b", "c")) {
                        String v = ctx.inputText(k);
                        t = t.replace("{" + k + "}", v == null ? "" : v);
                    }
                    ctx.output("text", t);
                });
    }

    static NodeDefinition join() {
        return NodeDefinition.builder("text.join", "Join texts", "Text")
                .description("Concatenates two texts with a separator.")
                .input(in("a", "first", TEXT)).input(optionalIn("b", "second", TEXT))
                .output(out("text", "text", TEXT))
                .param(ParamSpec.text("separator", "Separator", "\\n\\n", "Escapes: \\n newline, \\t tab"))
                .behavior(() -> ctx -> {
                    String sep = ctx.paramText("separator").replace("\\n", "\n").replace("\\t", "\t");
                    String b = ctx.inputText("b");
                    ctx.output("text", b == null ? ctx.inputText("a") : ctx.inputText("a") + sep + b);
                });
    }

    static NodeDefinition transform() {
        var ops = List.of("UPPER CASE", "lower case", "Title Case", "trim", "sort lines", "unique lines", "reverse lines",
                          "remove diacritics", "collapse spaces");
        return NodeDefinition.builder("text.transform", "Transform text", "Text")
                .description("Case changes, sorting and cleaning.")
                .input(in("text", "text", TEXT))
                .output(out("text", "text", TEXT))
                .param(ParamSpec.choice("op", "Operation", "UPPER CASE", ops, "What to do to the text"))
                .behavior(() -> ctx -> {
                    String s = ctx.inputText("text");
                    String r = switch (ctx.paramText("op")) {
                        case "UPPER CASE" -> s.toUpperCase(Locale.ROOT);
                        case "lower case" -> s.toLowerCase(Locale.ROOT);
                        case "Title Case" -> TextUtil.titleCase(s);
                        case "trim" -> s.strip();
                        case "sort lines" -> String.join("\n", s.lines().sorted(String.CASE_INSENSITIVE_ORDER).toList());
                        case "unique lines" -> String.join("\n", s.lines().distinct().toList());
                        case "reverse lines" -> {
                            var l = new ArrayList<>(s.lines().toList());
                            Collections.reverse(l);
                            yield String.join("\n", l);
                        }
                        case "remove diacritics" -> java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
                        default -> s.replaceAll("[ \\t]+", " ");
                    };
                    ctx.output("text", r);
                });
    }

    static NodeDefinition split() {
        return NodeDefinition.builder("text.split", "Split text", "Text")
                .description("Cuts a text into lines, paragraphs or sentences.")
                .input(in("text", "text", TEXT))
                .output(out("parts", "parts", TEXT_LIST)).output(out("count", "count", NUMBER))
                .param(ParamSpec.choice("mode", "Into", "sentences", List.of("lines", "paragraphs", "sentences", "words"), "How to cut"))
                .view("list")
                .behavior(() -> ctx -> {
                    String s = ctx.inputText("text");
                    List<String> parts = switch (ctx.paramText("mode")) {
                        case "lines" -> s.lines().filter(l -> !l.isBlank()).toList();
                        case "paragraphs" -> TextUtil.paragraphs(s);
                        case "words" -> TextUtil.words(s);
                        default -> TextUtil.sentences(s);
                    };
                    ctx.output("parts", parts);
                    ctx.output("count", (double) parts.size());
                });
    }

    static NodeDefinition joinList() {
        return NodeDefinition.builder("list.join", "Join list", "Text")
                .description("Glues a list of texts back into one text.")
                .input(in("parts", "parts", TEXT_LIST))
                .output(out("text", "text", TEXT))
                .param(ParamSpec.text("separator", "Separator", "\\n", "Escapes: \\n newline, \\t tab"))
                .param(ParamSpec.bool("numbered", "Numbered", false, "Prefix each part with 1., 2., ..."))
                .behavior(() -> ctx -> {
                    @SuppressWarnings("unchecked")
                    List<String> parts = (List<String>) ctx.input("parts");
                    String sep = ctx.paramText("separator").replace("\\n", "\n").replace("\\t", "\t");
                    var out = new ArrayList<String>();
                    for (int i = 0; i < parts.size(); i++) out.add(ctx.paramBool("numbered") ? (i + 1) + ". " + parts.get(i) : parts.get(i));
                    ctx.output("text", String.join(sep, out));
                });
    }

    static NodeDefinition stats() {
        return NodeDefinition.builder("text.stats", "Text statistics", "Analysis")
                .description("Characters, words, sentences, paragraphs and reading time.")
                .input(in("text", "text", TEXT))
                .output(out("table", "table", TABLE)).output(out("words", "words", NUMBER))
                .view("table")
                .behavior(() -> ctx -> {
                    String s = ctx.inputText("text");
                    var words = TextUtil.words(s);
                    double avg = words.stream().mapToInt(String::length).average().orElse(0);
                    long unique = words.stream().map(w -> w.toLowerCase(Locale.ROOT)).distinct().count();
                    var t = Table.builder("measure", "value")
                            .row("characters", (double) s.length())
                            .row("words", (double) words.size())
                            .row("unique words", (double) unique)
                            .row("sentences", (double) TextUtil.sentences(s).size())
                            .row("paragraphs", (double) TextUtil.paragraphs(s).size())
                            .row("average word length", Math.round(avg * 100) / 100.0)
                            .row("reading time (min, 230 wpm)", Math.round(words.size() / 230.0 * 10) / 10.0)
                            .build();
                    ctx.output("table", t);
                    ctx.output("words", (double) words.size());
                });
    }

    static NodeDefinition wordFrequencies() {
        return NodeDefinition.builder("text.words", "Word frequencies", "Analysis")
                .description("The most frequent words, without common Romanian and English stop words.")
                .input(in("text", "text", TEXT))
                .output(out("table", "table", TABLE))
                .param(ParamSpec.integer("top", "Top", 15, 1, 500, "How many words"))
                .param(ParamSpec.integer("minLength", "Minimum length", 3, 1, 30, "Ignore shorter words"))
                .param(ParamSpec.bool("stopWords", "Skip stop words", true, "Ignore words like și, de, the, and"))
                .view("table")
                .behavior(() -> ctx -> {
                    var counts = new HashMap<String, Integer>();
                    int min = ctx.paramInt("minLength");
                    boolean skip = ctx.paramBool("stopWords");
                    for (var w : TextUtil.words(ctx.inputText("text"))) {
                        String k = w.toLowerCase(Locale.ROOT);
                        if (k.length() < min || (skip && TextUtil.STOP_WORDS.contains(k))) continue;
                        counts.merge(k, 1, Integer::sum);
                    }
                    int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                    var b = Table.builder("word", "count", "share %");
                    counts.entrySet().stream()
                          .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                          .limit(ctx.paramInt("top"))
                          .forEach(e -> b.row(e.getKey(), (double) e.getValue(), Math.round(1000.0 * e.getValue() / Math.max(total, 1)) / 10.0));
                    ctx.output("table", b.build());
                });
    }

    static NodeDefinition readFile() {
        return NodeDefinition.builder("file.read", "Read text file", "Files")
                .description("Reads a UTF-8 text file (.txt, .md, .csv, ...).")
                .output(out("text", "text", TEXT)).output(out("name", "file name", TEXT))
                .param(ParamSpec.file("path", "File", "The file to read"))
                .notCacheable()
                .behavior(() -> ctx -> {
                    String p = ctx.paramText("path");
                    if (p.isBlank()) throw new IllegalArgumentException("choose a file");
                    var path = Path.of(p);
                    ctx.output("text", Files.readString(path, StandardCharsets.UTF_8));
                    ctx.output("name", path.getFileName().toString());
                });
    }

    static NodeDefinition writeFile() {
        return NodeDefinition.builder("file.write", "Write text file", "Files")
                .description("Saves the incoming text to a file.")
                .input(in("text", "text", TEXT))
                .output(out("path", "path", TEXT))
                .param(ParamSpec.text("path", "File", "nexus-output.txt", "Where to write (relative to the working folder)"))
                .param(ParamSpec.bool("append", "Append", false, "Add to the end instead of replacing"))
                .notCacheable()
                .behavior(() -> ctx -> {
                    var path = Path.of(ctx.paramText("path")).toAbsolutePath();
                    if (path.getParent() != null) Files.createDirectories(path.getParent());
                    if (ctx.paramBool("append"))
                        Files.writeString(path, ctx.inputText("text"), StandardCharsets.UTF_8,
                                          java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    else Files.writeString(path, ctx.inputText("text"), StandardCharsets.UTF_8);
                    ctx.log("wrote " + path);
                    ctx.output("path", path.toString());
                });
    }

    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                                                     .connectTimeout(Duration.ofSeconds(10)).build();

    static NodeDefinition fetchUrl() {
        return NodeDefinition.builder("web.fetch", "Fetch web page", "Files")
                .description("Downloads a web page and keeps its readable text.")
                .output(out("text", "text", TEXT)).output(out("title", "title", TEXT))
                .param(ParamSpec.text("url", "URL", "https://ro.wikipedia.org/wiki/Inteligen%C8%9B%C4%83_artificial%C4%83", "http(s) address"))
                .param(ParamSpec.bool("raw", "Keep HTML", false, "Output the page source instead of its text"))
                .notCacheable()
                .behavior(() -> ctx -> {
                    ctx.progress(-1, "downloading");
                    var req = HttpRequest.newBuilder(URI.create(ctx.paramText("url"))).timeout(Duration.ofSeconds(30))
                                         .header("User-Agent", "NEXUS/0.1").GET().build();
                    var resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() >= 400) throw new IllegalStateException("HTTP " + resp.statusCode());
                    String html = resp.body();
                    var m = java.util.regex.Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
                    ctx.output("title", m.find() ? TextUtil.htmlToText(m.group(1)) : ctx.paramText("url"));
                    ctx.output("text", ctx.paramBool("raw") ? html : TextUtil.htmlToText(html));
                });
    }

    static NodeDefinition formula() {
        return NodeDefinition.builder("math.formula", "Formula", "Numbers")
                .description("Computes an expression of a, b and c, e.g. sqrt(a*a + b*b).")
                .input(optionalIn("a", "a", NUMBER)).input(optionalIn("b", "b", NUMBER)).input(optionalIn("c", "c", NUMBER))
                .output(out("value", "value", NUMBER))
                .param(ParamSpec.text("expression", "Expression", "a * 2 + 1", "+ - * / % ^, sqrt abs exp log sin cos min max round ..."))
                .behavior(() -> ctx -> {
                    var vars = new LinkedHashMap<String, Double>();
                    for (var k : List.of("a", "b", "c")) if (ctx.isConnected(k)) vars.put(k, (Double) ctx.input(k));
                    ctx.output("value", Expression.evaluate(ctx.paramText("expression"), vars));
                });
    }

    static NodeDefinition delay() {
        return NodeDefinition.builder("util.delay", "Delay", "Utility")
                .description("Waits, then passes its input through - useful to watch branches run in parallel.")
                .input(in("in", "in", ANY))
                .output(out("out", "out", ANY))
                .param(ParamSpec.number("seconds", "Seconds", 2, 0, 600, "How long to wait"))
                .notCacheable()
                .behavior(() -> ctx -> {
                    double total = ctx.paramNumber("seconds");
                    long steps = Math.max(1, Math.round(total * 20));
                    for (long i = 1; i <= steps; i++) {
                        Thread.sleep((long) (total * 1000 / steps));
                        ctx.checkCancelled();
                        ctx.progress((double) i / steps, String.format(Locale.ROOT, "%.1fs", total * i / steps));
                    }
                    Object v = ctx.input("in");
                    ctx.output("out", v instanceof TextStream ts ? ts.await() : v);
                });
    }

    static NodeDefinition preview() {
        return NodeDefinition.builder("view.preview", "Preview", "Output")
                .description("Shows whatever reaches it: text (live, while it streams), tables, lists, numbers.")
                .input(in("value", "value", ANY))
                .view("preview")
                .behavior(() -> ctx -> {
                    Object v = ctx.input("value");
                    if (v instanceof TextStream ts) {
                        ts.subscribe(piece -> ctx.emit("text", piece)).join();
                    } else {
                        ctx.emit("value", v);
                    }
                });
    }
}
