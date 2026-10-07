package nexus.ml.bert;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Finds embedding models on disk, downloads them from Hugging Face the first time they are needed,
 * and keeps one loaded encoder per model for the whole application (loading maps 470 MB and
 * converts the weights to float, which is worth doing once).
 *
 * Models live in {@code ~/.nexus/models/<name>} (or {@code $NEXUS_HOME/models}).
 */
public final class EmbeddingModels {
    public static final String DEFAULT = "multilingual-e5-small";

    /** Known models: local name -> Hugging Face repository. */
    public static final Map<String, String> REPOS = Map.of(
            "multilingual-e5-small", "intfloat/multilingual-e5-small");

    private static final List<String> FILES = List.of("config.json", "tokenizer.json", "model.safetensors");
    private static final Map<String, BertEncoder> LOADED = new ConcurrentHashMap<>();

    private EmbeddingModels() {
    }

    public static Path home() {
        String env = System.getenv("NEXUS_HOME");
        return env != null && !env.isBlank() ? Path.of(env) : Path.of(System.getProperty("user.home"), ".nexus");
    }

    public static Path dir(String name) {
        return home().resolve("models").resolve(name);
    }

    public static boolean installed(String name) {
        var d = dir(name);
        return FILES.stream().allMatch(f -> Files.isRegularFile(d.resolve(f)));
    }

    /**
     * The shared encoder for {@code name}, downloading it if needed.
     *
     * @param progress (fraction, message) while downloading or loading; may be null
     */
    public static BertEncoder get(String name, BiConsumer<Double, String> progress) throws IOException, InterruptedException {
        var e = LOADED.get(name);
        if (e != null) return e;
        synchronized (EmbeddingModels.class) {
            e = LOADED.get(name);
            if (e != null) return e;
            if (!installed(name)) download(name, progress);
            if (progress != null) progress.accept(-1.0, "loading " + name);
            e = new BertEncoder(dir(name));
            LOADED.put(name, e);
            return e;
        }
    }

    static void download(String name, BiConsumer<Double, String> progress) throws IOException, InterruptedException {
        String repo = REPOS.get(name);
        if (repo == null) throw new IOException("model '" + name + "' is not installed in " + dir(name) + " and has no known download");
        var d = dir(name);
        Files.createDirectories(d);
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        for (int i = 0; i < FILES.size(); i++) {
            String f = FILES.get(i);
            if (Files.isRegularFile(d.resolve(f))) continue;
            var req = HttpRequest.newBuilder(URI.create("https://huggingface.co/" + repo + "/resolve/main/" + f)).GET().build();
            var resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new IOException("download of " + f + " failed: HTTP " + resp.statusCode());
            long total = resp.headers().firstValueAsLong("content-length").orElse(-1);
            var tmp = d.resolve(f + ".part");
            try (InputStream in = resp.body(); var out = Files.newOutputStream(tmp)) {
                byte[] buf = new byte[1 << 16];
                long done = 0, lastReport = 0;
                for (int n; (n = in.read(buf)) > 0; ) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null && done - lastReport > (1 << 20)) {
                        lastReport = done;
                        double within = total > 0 ? (double) done / total : 0;
                        progress.accept((i + within) / FILES.size(),
                                        "downloading " + f + " " + (done >> 20) + (total > 0 ? "/" + (total >> 20) : "") + " MB");
                    }
                }
            }
            Files.move(tmp, d.resolve(f), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
