package nexus.app.views;

import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import nexus.core.exec.NodeStatus;
import nexus.core.types.Table;

/** Views for the document and search nodes. */
public final class RagViews {
    private RagViews() {
    }

    public static void register() {
        Bodies.register("search-hits", SearchBody::new);
    }

    /**
     * The passages a search found, as cards: where each comes from, how similar it is, and how it
     * ranked by meaning and by words - which makes the hybrid fusion visible.
     */
    static final class SearchBody extends NodeBody {
        private final VBox cards = new VBox(6);
        private final ScrollPane scroll = new ScrollPane(cards);
        private final Label empty = new Label("not run yet");

        SearchBody() {
            cards.setPadding(new Insets(2));
            scroll.setFitToWidth(true);
            scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            scroll.setFocusTraversable(false);
            empty.getStyleClass().add("body-muted");
            cards.getChildren().add(empty);
            getChildren().add(scroll);
        }

        @Override
        public double preferredHeight() {
            return 260;
        }

        @Override
        public boolean interactive() {
            return true;
        }

        @Override
        protected void layoutChildren() {
            scroll.resizeRelocate(0, 0, getWidth(), getHeight());
        }

        @Override
        public void reset() {
            cards.getChildren().setAll(empty);
            empty.setText("searching…");
        }

        @Override
        public void status(NodeStatus status, String message) {
            if (status == NodeStatus.ERROR) {
                empty.setText(message);
                cards.getChildren().setAll(empty);
            }
        }

        @Override
        public void outputs(Map<String, Object> outputs) {
            if (!(outputs.get("hits") instanceof Table hits)) return;
            List<?> passages = outputs.get("passages") instanceof List<?> l ? l : List.of();
            cards.getChildren().clear();
            if (hits.size() == 0) {
                empty.setText("nothing found");
                cards.getChildren().add(empty);
                return;
            }
            for (int i = 0; i < hits.size(); i++) {
                var row = hits.rows().get(i);
                var head = new Label(String.format("[%d]  %s   ·   similarity %s   ·   meaning #%s   ·   words #%s",
                                                   i + 1, row.get(1), Bodies.describe(row.get(2)), row.get(3), row.get(4)));
                head.getStyleClass().add("hit-head");
                var text = new Label(i < passages.size() ? String.valueOf(passages.get(i)) : String.valueOf(row.get(5)));
                text.setWrapText(true);
                text.getStyleClass().add("hit-text");
                var card = new VBox(2, head, text);
                card.getStyleClass().add("hit-card");
                card.setPadding(new Insets(5, 7, 6, 7));
                cards.getChildren().add(card);
            }
            scroll.setVvalue(0);
        }
    }
}
