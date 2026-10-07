package nexus.app.canvas;

import java.util.Map;
import javafx.scene.paint.Color;

/** Header colours per palette category (unknown categories get a stable colour from their name). */
public final class Colors {
    private Colors() {
    }

    private static final Map<String, String> CATEGORY = Map.ofEntries(
            Map.entry("Input", "#2f9e44"),
            Map.entry("Text", "#e67700"),
            Map.entry("Analysis", "#0c8599"),
            Map.entry("Numbers", "#1971c2"),
            Map.entry("Files", "#5f3dc4"),
            Map.entry("Utility", "#495057"),
            Map.entry("Output", "#c2255c"),
            Map.entry("Language models", "#d9480f"),
            Map.entry("Documents", "#6741d9"),
            Map.entry("Search", "#1864ab"),
            Map.entry("Games", "#2b8a3e"),
            Map.entry("Training", "#a61e4d"),
            Map.entry("System", "#343a40"));

    private static final String[] FALLBACK = {"#5c7cfa", "#f76707", "#12b886", "#ae3ec9", "#fab005", "#15aabf", "#e8590c"};

    public static Color categoryColor(String category) {
        String c = CATEGORY.get(category);
        if (c == null) c = FALLBACK[Math.floorMod(category.hashCode(), FALLBACK.length)];
        return Color.web(c);
    }
}
