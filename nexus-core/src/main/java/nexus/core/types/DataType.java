package nexus.core.types;

import java.util.Objects;

/**
 * The type of the values that travel along a wire. Ports declare a type, and the editor only lets
 * an output connect to an input whose type accepts it (see {@link DataTypes#canConnect}).
 *
 * @param id        stable identifier, used in saved graphs
 * @param name      shown in the UI
 * @param color     the port and wire colour, as "#rrggbb"
 * @param javaClass the class of the values (for checking what a node produces)
 */
public record DataType(String id, String name, String color, Class<?> javaClass) {
    public DataType {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
        Objects.requireNonNull(color);
        Objects.requireNonNull(javaClass);
    }

    @Override
    public String toString() {
        return id;
    }
}
