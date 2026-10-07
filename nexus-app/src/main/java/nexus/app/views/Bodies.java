package nexus.app.views;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javafx.collections.FXCollections;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.beans.property.ReadOnlyObjectWrapper;
import nexus.core.exec.NodeStatus;
import nexus.core.types.DataTypes;
import nexus.core.types.Table;
import nexus.core.types.TextStream;
import nexus.core.util.TextUtil;

/** The built-in node bodies and the registry that maps a view name to a body. */
public final class Bodies {
    private Bodies() {
    }

    private static final Map<String, Supplier<NodeBody>> FACTORIES = new ConcurrentHashMap<>();

    static {
        register("text", TextSummary::new);
        register("table", TableBody::new);
        register("list", ListBody::new);
        register("preview", PreviewBody::new);
    }

    /** Lets other modules add views (chat, game board, charts...). */
    public static void register(String view, Supplier<NodeBody> factory) {
        FACTORIES.put(view, factory);
    }

    public static NodeBody create(String view) {
        return FACTORIES.getOrDefault(view, TextSummary::new).get();
    }

    /** Text for any value, the way the views show it. */
    public static String describe(Object v) {
        if (v == null) return "∅";
        if (v instanceof Double d) return DataTypes.formatNumber(d);
        if (v instanceof TextStream ts) return ts.textSoFar();
        if (v instanceof Table t) return t.toText();
        if (v instanceof List<?> l) {
            var sb = new StringBuilder();
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(i + 1).append(". ").append(describe(l.get(i)));
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    // ---- a one-to-three-line summary of the first output ---------------------------------------------

    static final class TextSummary extends NodeBody {
        private final Label label = new Label();

        TextSummary() {
            label.getStyleClass().add("body-muted");
            label.setWrapText(true);
            label.setText("not run yet");
            getChildren().add(label);
        }

        @Override
        public double preferredHeight() {
            return 44;
        }

        @Override
        protected void layoutChildren() {
            label.resizeRelocate(0, 0, getWidth(), getHeight());
        }

        @Override
        public void reset() {
            label.getStyleClass().setAll("body-muted");
            label.setText("…");
        }

        @Override
        public void status(NodeStatus status, String message) {
            if (status == NodeStatus.ERROR) {
                label.getStyleClass().setAll("error-text");
                label.setText(message);
            } else if (status == NodeStatus.SKIPPED || status == NodeStatus.CANCELLED) {
                label.getStyleClass().setAll("body-muted");
                label.setText(message);
            }
        }

        @Override
        public void outputs(Map<String, Object> outputs) {
            if (outputs.isEmpty()) return;
            Object first = outputs.values().iterator().next();
            label.getStyleClass().setAll("body-text");
            label.setText(TextUtil.preview(describe(first), 140));
        }
    }

    // ---- a table ------------------------------------------------------------------------------------------

    static final class TableBody extends NodeBody {
        private final TableView<List<Object>> table = new TableView<>();

        TableBody() {
            table.setPlaceholder(new Label("not run yet"));
            table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
            table.setFocusTraversable(false);
            getChildren().add(table);
        }

        @Override
        public double preferredHeight() {
            return 170;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            table.resizeRelocate(0, 0, getWidth(), getHeight());
        }

        @Override
        public void outputs(Map<String, Object> outputs) {
            for (var v : outputs.values())
                if (v instanceof Table t) {
                    show(t);
                    return;
                }
        }

        void show(Table t) {
            table.getColumns().clear();
            for (int i = 0; i < t.columns().size(); i++) {
                final int col = i;
                var c = new TableColumn<List<Object>, String>(t.columns().get(i));
                c.setCellValueFactory(row -> new ReadOnlyObjectWrapper<>(describe(row.getValue().get(col))));
                c.setSortable(false);
                table.getColumns().add(c);
            }
            table.setItems(FXCollections.observableArrayList(t.rows()));
        }
    }

    // ---- a list of texts ------------------------------------------------------------------------------------

    static final class ListBody extends NodeBody {
        private final ListView<String> list = new ListView<>();

        ListBody() {
            list.setPlaceholder(new Label("not run yet"));
            list.setFocusTraversable(false);
            getChildren().add(list);
        }

        @Override
        public double preferredHeight() {
            return 150;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            list.resizeRelocate(0, 0, getWidth(), getHeight());
        }

        @Override
        public void outputs(Map<String, Object> outputs) {
            for (var v : outputs.values())
                if (v instanceof List<?> l) {
                    list.setItems(FXCollections.observableArrayList(l.stream().map(Bodies::describe).toList()));
                    return;
                }
        }
    }

    // ---- the Preview node: live text, tables, lists, big numbers ------------------------------------------------

    static final class PreviewBody extends NodeBody {
        private final TextArea text = new TextArea();
        private final TableBody table = new TableBody();
        private final Label number = new Label();

        PreviewBody() {
            text.setEditable(false);
            text.setWrapText(true);
            text.setPromptText("connect something and run");
            number.getStyleClass().add("big-number");
            getChildren().add(text);
        }

        @Override
        public double preferredHeight() {
            return 210;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            for (var c : getChildren()) c.resizeRelocate(0, 0, getWidth(), getHeight());
        }

        private void showOnly(javafx.scene.Node n) {
            if (getChildren().size() != 1 || getChildren().getFirst() != n) getChildren().setAll(n);
        }

        @Override
        public void reset() {
            showOnly(text);
            text.clear();
        }

        @Override
        public void status(NodeStatus status, String message) {
            if (status == NodeStatus.ERROR || status == NodeStatus.SKIPPED) {
                showOnly(text);
                text.setText("(" + status.name().toLowerCase() + ": " + message + ")");
            }
        }

        @Override
        public void emit(String channel, Object payload) {
            if ("text".equals(channel)) {
                showOnly(text);
                text.appendText((String) payload);
                return;
            }
            switch (payload) {
                case Table t -> {
                    showOnly(table);
                    table.show(t);
                }
                case Double d -> {
                    showOnly(number);
                    number.setText(DataTypes.formatNumber(d));
                }
                default -> {
                    showOnly(text);
                    text.setText(describe(payload));
                }
            }
        }
    }
}
