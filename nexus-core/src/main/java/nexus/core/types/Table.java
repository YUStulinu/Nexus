package nexus.core.types;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A small immutable table: named columns and rows of cells (shown as a grid in the UI). */
public record Table(List<String> columns, List<List<Object>> rows) {
    public Table {
        columns = List.copyOf(columns);
        var copy = new ArrayList<List<Object>>(rows.size());
        for (var r : rows) {
            if (r.size() != columns.size())
                throw new IllegalArgumentException("row of " + r.size() + " cells for " + columns.size() + " columns");
            copy.add(Collections.unmodifiableList(new ArrayList<>(r)));
        }
        rows = Collections.unmodifiableList(copy);
    }

    public static Builder builder(String... columns) {
        return new Builder(List.of(columns));
    }

    public int size() {
        return rows.size();
    }

    /** Tab-separated text, header first. */
    public String toText() {
        var sb = new StringBuilder(String.join("\t", columns));
        for (var r : rows) {
            sb.append('\n');
            for (int i = 0; i < r.size(); i++) {
                if (i > 0) sb.append('\t');
                Object v = r.get(i);
                sb.append(v instanceof Double d ? DataTypes.formatNumber(d) : String.valueOf(v));
            }
        }
        return sb.toString();
    }

    public static final class Builder {
        private final List<String> columns;
        private final List<List<Object>> rows = new ArrayList<>();

        private Builder(List<String> columns) {
            this.columns = columns;
        }

        public Builder row(Object... cells) {
            rows.add(List.of(cells));
            return this;
        }

        public Table build() {
            return new Table(columns, rows);
        }
    }
}
