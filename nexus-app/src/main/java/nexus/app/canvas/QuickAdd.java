package nexus.app.canvas;

import java.util.List;
import java.util.function.Consumer;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import nexus.core.graph.NodeDefinition;
import nexus.core.registry.NodeRegistry;
import nexus.core.types.DataType;
import nexus.core.types.DataTypes;

/**
 * The "add node" popup: type to search all node types, arrows and Enter to pick. Opened from a wire
 * dropped on empty space it only lists nodes that can accept (or produce) the wire's type.
 */
public final class QuickAdd {
    /** Only nodes with an input that accepts {@code type} (or an output that produces it). */
    public record Filter(DataType type, boolean needsInput) {
        boolean accepts(NodeDefinition d) {
            if (needsInput) return d.inputs().stream().anyMatch(p -> DataTypes.canConnect(type, p.type()));
            return d.outputs().stream().anyMatch(p -> DataTypes.canConnect(p.type(), type));
        }
    }

    private final NodeRegistry registry;
    private final Popup popup = new Popup();
    private final TextField search = new TextField();
    private final ListView<NodeDefinition> list = new ListView<>();
    private final Label caption = new Label();
    private Filter filter;
    private Consumer<NodeDefinition> onPick;

    public QuickAdd(NodeRegistry registry) {
        this.registry = registry;
        search.setPromptText("Search nodes…");
        list.setPrefSize(330, 300);
        list.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(NodeDefinition d, boolean empty) {
                super.updateItem(d, empty);
                if (empty || d == null) {
                    setGraphic(null);
                    setText(null);
                    return;
                }
                var swatch = new Region();
                swatch.setMinSize(10, 10);
                swatch.setMaxSize(10, 10);
                swatch.setStyle("-fx-background-radius: 3; -fx-background-color: " + toHex(Colors.categoryColor(d.category())) + ";");
                var title = new Label(d.title());
                title.setStyle("-fx-font-weight: bold;");
                var cat = new Label(d.category());
                cat.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px;");
                var row = new HBox(8, swatch, title, cat);
                row.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
                var desc = new Label(d.description());
                desc.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px;");
                setGraphic(new VBox(1, row, desc));
                setText(null);
            }
        });
        caption.setStyle("-fx-text-fill: #8b93a7; -fx-font-size: 10.5px;");
        var box = new VBox(6, search, caption, list);
        box.getStyleClass().add("quick-add");
        box.setPadding(new Insets(8));
        popup.getContent().add(box);
        popup.setAutoHide(true);
        search.textProperty().addListener((o, a, b) -> refresh());
        search.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DOWN -> {
                    list.getSelectionModel().selectNext();
                    list.scrollTo(list.getSelectionModel().getSelectedIndex());
                    e.consume();
                }
                case UP -> {
                    list.getSelectionModel().selectPrevious();
                    list.scrollTo(list.getSelectionModel().getSelectedIndex());
                    e.consume();
                }
                case ENTER -> pick(list.getSelectionModel().getSelectedItem());
                case ESCAPE -> hide();
                default -> {
                }
            }
        });
        list.setOnMouseClicked(e -> {
            if (e.getClickCount() >= 1) pick(list.getSelectionModel().getSelectedItem());
        });
        list.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) pick(list.getSelectionModel().getSelectedItem());
        });
    }

    private static String toHex(javafx.scene.paint.Color c) {
        return String.format("#%02x%02x%02x", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    public void show(Node owner, double screenX, double screenY, Filter filter, Consumer<NodeDefinition> onPick) {
        this.filter = filter;
        this.onPick = onPick;
        search.clear();
        caption.setText(filter == null ? "All nodes" : (filter.needsInput() ? "Nodes that take " : "Nodes that produce ") + filter.type().name());
        refresh();
        popup.show(owner, screenX, screenY);
        search.requestFocus();
    }

    public void hide() {
        popup.hide();
    }

    private void refresh() {
        String q = search.getText();
        List<NodeDefinition> items = (q == null || q.isBlank() ? registry.all().stream().sorted(
                (a, b) -> a.category().equals(b.category()) ? a.title().compareToIgnoreCase(b.title()) : a.category().compareTo(b.category()))
                .toList() : registry.search(q));
        if (filter != null) items = items.stream().filter(filter::accepts).toList();
        list.setItems(FXCollections.observableArrayList(items));
        if (!items.isEmpty()) list.getSelectionModel().select(0);
    }

    private void pick(NodeDefinition d) {
        if (d == null) return;
        hide();
        if (onPick != null) onPick.accept(d);
    }
}
