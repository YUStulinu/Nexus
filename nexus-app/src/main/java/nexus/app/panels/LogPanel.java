package nexus.app.panels;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;

/** The run log: one line per notable event, newest last, capped. */
public final class LogPanel extends ListView<LogPanel.Entry> {
    public enum Level { INFO, OK, WARN, ERROR }

    public record Entry(String time, Level level, String text) {
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int LIMIT = 5000;
    private final ObservableList<Entry> entries = FXCollections.observableArrayList();

    public LogPanel() {
        getStyleClass().add("log-list");
        setItems(entries);
        setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Entry e, boolean empty) {
                super.updateItem(e, empty);
                if (empty || e == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(e.time() + "  " + e.text());
                setStyle("-fx-text-fill: " + switch (e.level()) {
                    case OK -> "#8ce99a";
                    case WARN -> "#ffd43b";
                    case ERROR -> "#ff8787";
                    default -> "#c1c7d6";
                } + ";");
            }
        });
    }

    public void add(Level level, String text) {
        var e = new Entry(LocalTime.now().format(TIME), level, text);
        System.out.println(e.time() + " " + level + " " + text);   // the console mirrors the log panel
        entries.add(e);
        if (entries.size() > LIMIT) entries.remove(0, entries.size() - LIMIT);
        scrollTo(entries.size() - 1);
    }

    public void clearLog() {
        entries.clear();
    }
}
