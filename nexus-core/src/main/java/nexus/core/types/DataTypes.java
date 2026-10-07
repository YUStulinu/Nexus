package nexus.core.types;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The registry of data types and of the conversions between them.
 *
 * Two ports connect when the types are equal, when the input accepts {@link #ANY}, or when a
 * conversion is registered (e.g. a {@link TextStream} into a {@link #TEXT} input: the engine waits
 * for the stream to finish and delivers the whole text; a number into a text input: its decimal
 * representation). Libraries register their own types and conversions at startup.
 */
public final class DataTypes {
    private DataTypes() {
    }

    private static final Map<String, DataType> TYPES = new LinkedHashMap<>();
    private static final Map<String, Function<Object, Object>> CONVERSIONS = new LinkedHashMap<>();

    public static final DataType ANY = register(new DataType("any", "Any", "#adb5bd", Object.class));
    public static final DataType TEXT = register(new DataType("text", "Text", "#f59f00", String.class));
    public static final DataType TEXT_STREAM = register(new DataType("text-stream", "Text stream", "#ff922b", TextStream.class));
    public static final DataType NUMBER = register(new DataType("number", "Number", "#4dabf7", Double.class));
    public static final DataType BOOLEAN = register(new DataType("boolean", "Boolean", "#e64980", Boolean.class));
    public static final DataType TEXT_LIST = register(new DataType("text-list", "List of texts", "#fab005", List.class));
    public static final DataType TABLE = register(new DataType("table", "Table", "#20c997", Table.class));

    static {
        registerConversion(TEXT_STREAM, TEXT, v -> ((TextStream) v).await());
        registerConversion(TEXT, TEXT_STREAM, v -> TextStream.of((String) v));
        registerConversion(NUMBER, TEXT, v -> formatNumber((Double) v));
        registerConversion(BOOLEAN, TEXT, String::valueOf);
        registerConversion(TEXT_LIST, TEXT, v -> String.join("\n", castList(v)));
        registerConversion(TEXT, TEXT_LIST, v -> List.of((String) v));
        registerConversion(TABLE, TEXT, v -> ((Table) v).toText());
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object v) {
        return (List<String>) v;
    }

    public static synchronized DataType register(DataType t) {
        var prev = TYPES.putIfAbsent(t.id(), t);
        return prev != null ? prev : t;
    }

    public static synchronized void registerConversion(DataType from, DataType to, Function<Object, Object> f) {
        CONVERSIONS.put(from.id() + "->" + to.id(), f);
    }

    public static synchronized DataType byId(String id) {
        var t = TYPES.get(id);
        if (t == null) throw new IllegalArgumentException("unknown data type '" + id + "'");
        return t;
    }

    public static synchronized Collection<DataType> all() {
        return List.copyOf(TYPES.values());
    }

    /** Whether an output of type {@code from} may be wired into an input of type {@code to}. */
    public static synchronized boolean canConnect(DataType from, DataType to) {
        return from.equals(to) || to.equals(ANY) || from.equals(ANY) || CONVERSIONS.containsKey(from.id() + "->" + to.id());
    }

    /** Converts a value produced as {@code from} for an input declared as {@code to}. */
    public static Object convert(Object value, DataType from, DataType to) {
        if (value == null || from.equals(to) || to.equals(ANY)) return value;
        Function<Object, Object> f;
        synchronized (DataTypes.class) {
            f = CONVERSIONS.get(from.id() + "->" + to.id());
        }
        if (f == null) {
            if (from.equals(ANY) && to.javaClass().isInstance(value)) return value;
            if (from.equals(ANY)) return convertByValue(value, to);
            throw new IllegalArgumentException("no conversion from " + from.id() + " to " + to.id());
        }
        return f.apply(value);
    }

    /** For values coming out of an ANY port: convert by their runtime class. */
    private static Object convertByValue(Object value, DataType to) {
        for (var t : all()) {
            if (!t.equals(ANY) && t.javaClass().isInstance(value)) {
                if (t.equals(to)) return value;
                Function<Object, Object> f;
                synchronized (DataTypes.class) {
                    f = CONVERSIONS.get(t.id() + "->" + to.id());
                }
                if (f != null) return f.apply(value);
            }
        }
        if (to.equals(TEXT)) return String.valueOf(value);
        throw new IllegalArgumentException("cannot convert a " + value.getClass().getSimpleName() + " to " + to.id());
    }

    public static String formatNumber(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
        return String.valueOf(v);
    }
}
