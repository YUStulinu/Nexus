package nexus.app.panels;

import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import nexus.app.canvas.Colors;
import nexus.app.canvas.GraphCanvas;
import nexus.core.graph.NodeDefinition;
import nexus.core.registry.NodeRegistry;

/** The node palette: every node type by category, searchable; drag an item onto the canvas (or double-click it). */
public final class Palette extends VBox {
    private final NodeRegistry registry;
    private final VBox items = new VBox(4);
    private final TextField search = new TextField();
    private final Consumer<NodeDefinition> onActivate;

    public Palette(NodeRegistry registry, Consumer<NodeDefinition> onActivate) {
        this.registry = registry;
        this.onActivate = onActivate;
        getStyleClass().add("side-panel");
        var title = new Label("NODES");
        title.getStyleClass().add("panel-title");
        search.setPromptText("Search…");
        search.getStyleClass().add("palette-search");
        search.textProperty().addListener((o, a, b) -> rebuild());
        var scroll = new ScrollPane(items);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        items.setPadding(new Insets(0, 10, 10, 10));
        VBox.setMargin(search, new Insets(0, 10, 6, 10));
        VBox.setVgrow(scroll, Priority.ALWAYS);
        getChildren().addAll(title, search, scroll);
        setPrefWidth(230);
        rebuild();
    }

    private void rebuild() {
        items.getChildren().clear();
        String q = search.getText();
        if (q != null && !q.isBlank()) {
            for (var d : registry.search(q)) items.getChildren().add(item(d));
            if (items.getChildren().isEmpty()) items.getChildren().add(new Label("no match"));
            return;
        }
        for (var e : registry.byCategory().entrySet()) {
            var cat = new Label(e.getKey().toUpperCase());
            cat.getStyleClass().add("palette-category");
            items.getChildren().add(cat);
            for (var d : e.getValue()) items.getChildren().add(item(d));
        }
    }

    private Region item(NodeDefinition d) {
        var swatch = new Region();
        swatch.getStyleClass().add("palette-swatch");
        swatch.setMinSize(4, 26);
        swatch.setMaxWidth(4);
        var c = Colors.categoryColor(d.category());
        swatch.setStyle(String.format("-fx-background-color: #%02x%02x%02x;", (int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255)));
        var title = new Label(d.title());
        title.getStyleClass().add("item-title");
        var desc = new Label(d.description());
        desc.getStyleClass().add("item-desc");
        desc.setMaxWidth(180);
        var text = new VBox(1, title, desc);
        var box = new HBox(8, swatch, text);
        box.getStyleClass().add("palette-item");
        Tooltip.install(box, new Tooltip(d.title() + "\n" + d.description() + "\n\n"
                                         + "inputs: " + (d.inputs().isEmpty() ? "none" : String.join(", ", d.inputs().stream().map(p -> p.label() + " (" + p.type().name() + ")").toList()))
                                         + "\noutputs: " + (d.outputs().isEmpty() ? "none" : String.join(", ", d.outputs().stream().map(p -> p.label() + " (" + p.type().name() + ")").toList()))));
        box.setOnDragDetected(e -> {
            var db = box.startDragAndDrop(TransferMode.COPY);
            var content = new ClipboardContent();
            content.putString(GraphCanvas.DRAG_PREFIX + d.id());
            db.setContent(content);
            e.consume();
        });
        box.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) onActivate.accept(d);
        });
        return box;
    }
}
