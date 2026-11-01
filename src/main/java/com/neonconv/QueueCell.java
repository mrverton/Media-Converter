package com.neonconv;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.function.Consumer;

/**
 * Renders one queue row. Cells are recycled by the ListView, so every binding made for an item is
 * released again in {@link #detach()} before the cell is given a new item (or becomes empty).
 */
final class QueueCell extends ListCell<MediaItem> {

    private static final String[] TAG_CLASSES = {
            "tag-pending", "tag-converting", "tag-completed", "tag-failed", "tag-cancelled"};
    private static final String[] ROW_CLASSES = {
            "row-pending", "row-converting", "row-completed", "row-failed", "row-cancelled"};

    private final HBox row = new HBox(14);
    private final Label ext = new Label();
    private final Label name = new Label();
    private final Label meta = new Label();
    private final ProgressBar bar = new ProgressBar(0);
    private final Label tag = new Label();
    private final Tooltip errorTip = new Tooltip();

    private final ChangeListener<MediaItem.Status> statusListener = (o, was, now) -> applyStatus(now);
    private MediaItem bound;

    QueueCell(ReadOnlyBooleanProperty busy, Consumer<MediaItem> onRemove) {
        row.getStyleClass().add("queue-row");
        row.setAlignment(Pos.CENTER_LEFT);

        ext.getStyleClass().add("ext-badge");
        ext.setMinWidth(58);
        ext.setPrefWidth(58);
        ext.setAlignment(Pos.CENTER);

        name.getStyleClass().add("row-title");
        meta.getStyleClass().add("row-meta");
        bar.getStyleClass().add("row-bar");
        bar.setMaxWidth(Double.MAX_VALUE);

        VBox info = new VBox(5, name, meta, bar);
        info.setAlignment(Pos.CENTER_LEFT);
        info.setMinWidth(0);
        HBox.setHgrow(info, Priority.ALWAYS);

        tag.getStyleClass().add("tag");
        tag.setAlignment(Pos.CENTER);
        tag.setMinWidth(Region.USE_PREF_SIZE);
        tag.setPrefWidth(150);

        Button remove = new Button("\u00D7");
        remove.getStyleClass().add("icon-button");
        remove.disableProperty().bind(busy);
        remove.setOnAction(e -> {
            if (getItem() != null) {
                onRemove.accept(getItem());
            }
        });

        row.getChildren().addAll(ext, info, tag, remove);
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
    }

    @Override
    protected void updateItem(MediaItem item, boolean empty) {
        super.updateItem(item, empty);
        detach();
        if (empty || item == null) {
            setGraphic(null);
            return;
        }
        bound = item;
        ext.setText(item.extension().isEmpty() ? "FILE" : abbreviate(item.extension()));
        name.setText(item.fileName());
        meta.textProperty().bind(item.metaTextProperty());
        tag.textProperty().bind(item.statusTextProperty());
        bar.progressProperty().bind(item.progressProperty());
        errorTip.textProperty().bind(item.messageProperty());
        item.statusProperty().addListener(statusListener);
        applyStatus(item.getStatus());
        setGraphic(row);
    }

    private void detach() {
        if (bound == null) {
            return;
        }
        bound.statusProperty().removeListener(statusListener);
        meta.textProperty().unbind();
        tag.textProperty().unbind();
        bar.progressProperty().unbind();
        errorTip.textProperty().unbind();
        bound = null;
    }

    private void applyStatus(MediaItem.Status status) {
        String key = status.name().toLowerCase(Locale.ROOT);
        tag.getStyleClass().removeAll(TAG_CLASSES);
        tag.getStyleClass().add("tag-" + key);
        row.getStyleClass().removeAll(ROW_CLASSES);
        row.getStyleClass().add("row-" + key);
        tag.setTooltip(status == MediaItem.Status.FAILED ? errorTip : null);
    }

    private static String abbreviate(String extension) {
        String upper = extension.toUpperCase(Locale.ROOT);
        return upper.length() > 4 ? upper.substring(0, 4) : upper;
    }
}
