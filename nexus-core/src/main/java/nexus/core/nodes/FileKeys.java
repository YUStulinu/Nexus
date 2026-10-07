package nexus.core.nodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Cheap content keys for files and folders: paths, sizes and modification times (no reading). */
public final class FileKeys {
    private FileKeys() {
    }

    public static String of(String path) {
        if (path == null || path.isBlank()) return "none";
        var p = Path.of(path);
        try {
            if (Files.isDirectory(p)) {
                var sb = new StringBuilder();
                try (Stream<Path> s = Files.walk(p)) {
                    s.filter(Files::isRegularFile).sorted().forEach(f -> sb.append(entry(f)));
                }
                return Integer.toHexString(sb.toString().hashCode()) + ":" + sb.length();
            }
            return entry(p);
        } catch (IOException e) {
            return "missing:" + path;
        }
    }

    private static String entry(Path f) {
        try {
            return f + "|" + Files.size(f) + "|" + Files.getLastModifiedTime(f).toMillis() + ";";
        } catch (IOException e) {
            return f + "|missing;";
        }
    }
}
