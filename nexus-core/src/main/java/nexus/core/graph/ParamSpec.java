package nexus.core.graph;

import java.util.List;

/**
 * A setting of a node, edited in the inspector (not wired). Values are plain JSON-friendly objects:
 * String, Double, Boolean.
 *
 * @param key          identifier
 * @param label        shown in the inspector
 * @param kind         which editor to show
 * @param defaultValue the initial value
 * @param min          for numbers
 * @param max          for numbers
 * @param choices      for {@link Kind#CHOICE}
 * @param help         a one-line explanation shown as a tooltip
 */
public record ParamSpec(String key, String label, Kind kind, Object defaultValue, double min, double max,
                        List<String> choices, String help) {

    public enum Kind { TEXT, MULTILINE, INTEGER, NUMBER, BOOLEAN, CHOICE, FILE, FOLDER }

    public static ParamSpec text(String key, String label, String def, String help) {
        return new ParamSpec(key, label, Kind.TEXT, def, 0, 0, List.of(), help);
    }

    public static ParamSpec multiline(String key, String label, String def, String help) {
        return new ParamSpec(key, label, Kind.MULTILINE, def, 0, 0, List.of(), help);
    }

    public static ParamSpec integer(String key, String label, int def, int min, int max, String help) {
        return new ParamSpec(key, label, Kind.INTEGER, (double) def, min, max, List.of(), help);
    }

    public static ParamSpec number(String key, String label, double def, double min, double max, String help) {
        return new ParamSpec(key, label, Kind.NUMBER, def, min, max, List.of(), help);
    }

    public static ParamSpec bool(String key, String label, boolean def, String help) {
        return new ParamSpec(key, label, Kind.BOOLEAN, def, 0, 0, List.of(), help);
    }

    public static ParamSpec choice(String key, String label, String def, List<String> choices, String help) {
        return new ParamSpec(key, label, Kind.CHOICE, def, 0, 0, List.copyOf(choices), help);
    }

    public static ParamSpec file(String key, String label, String help) {
        return new ParamSpec(key, label, Kind.FILE, "", 0, 0, List.of(), help);
    }

    public static ParamSpec folder(String key, String label, String help) {
        return new ParamSpec(key, label, Kind.FOLDER, "", 0, 0, List.of(), help);
    }

    /** Normalises a value for this parameter (numbers to Double, clamped; unknown choices rejected). */
    public Object coerce(Object value) {
        if (value == null) return defaultValue;
        return switch (kind) {
            case INTEGER -> {
                double d = Math.rint(toDouble(value));
                yield Math.max(min, Math.min(max, d));
            }
            case NUMBER -> Math.max(min, Math.min(max, toDouble(value)));
            case BOOLEAN -> value instanceof Boolean b ? b : Boolean.parseBoolean(value.toString());
            case CHOICE -> {
                String s = value.toString();
                if (!choices.contains(s)) throw new IllegalArgumentException("'" + s + "' is not one of " + choices);
                yield s;
            }
            default -> value.toString();
        };
    }

    private static double toDouble(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString().trim());
    }
}
