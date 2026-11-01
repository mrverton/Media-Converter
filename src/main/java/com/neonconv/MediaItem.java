package com.neonconv;

import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.apache.commons.io.FilenameUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * One row of the conversion queue. Pure model: JavaFX properties only, no UI nodes.
 * All mutation happens on the JavaFX Application Thread.
 */
public final class MediaItem {

    public enum Status { PENDING, CONVERTING, COMPLETED, FAILED, CANCELLED }

    /** Extensions picked up when a whole folder is dropped (single files are always accepted). */
    public static final Set<String> MEDIA_EXTENSIONS = Set.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v", "mpg", "mpeg", "ts", "m2ts",
            "3gp", "ogv", "vob", "mts",
            "mp3", "wav", "flac", "aac", "m4a", "ogg", "oga", "opus", "wma", "aiff", "aif",
            "ape", "alac", "amr", "ac3", "dts", "mka", "caf", "wv");

    private final Path source;
    private final String fileName;
    private final String extension;
    private final long sizeBytes;

    private final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.PENDING);
    /** 0..1, or a negative number while the duration is unknown (indeterminate). */
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final StringProperty message = new SimpleStringProperty("");
    private final ObjectProperty<Path> output = new SimpleObjectProperty<>();

    private final StringBinding statusText;
    private final StringBinding metaText;

    public MediaItem(Path path) {
        this.source = path.toAbsolutePath().normalize();
        Path name = this.source.getFileName();
        this.fileName = name == null ? this.source.toString() : name.toString();
        this.extension = FilenameUtils.getExtension(fileName).toLowerCase(Locale.ROOT);

        long size = 0;
        try {
            size = Files.size(this.source);
        } catch (IOException ignored) {
            // size is purely cosmetic
        }
        this.sizeBytes = size;

        this.statusText = Bindings.createStringBinding(() -> switch (status.get()) {
            case PENDING -> "PENDING";
            case CONVERTING -> progress.get() < 0
                    ? "CONVERTING"
                    : "CONVERTING " + Math.round(progress.get() * 100) + "%";
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        }, status, progress);

        this.metaText = Bindings.createStringBinding(() -> {
            if (status.get() == Status.FAILED && !message.get().isBlank()) {
                return message.get();
            }
            Path out = output.get();
            if (status.get() == Status.COMPLETED && out != null) {
                return "\u2192 " + out;
            }
            Path parent = this.source.getParent();
            return formatSize(sizeBytes) + "   \u00B7   " + (parent == null ? "" : parent.toString());
        }, status, message, output);
    }

    public static boolean looksLikeMedia(Path path) {
        Path name = path.getFileName();
        return name != null
                && MEDIA_EXTENSIONS.contains(FilenameUtils.getExtension(name.toString()).toLowerCase(Locale.ROOT));
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024;
            unit++;
        } while (value >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, value >= 100 ? "%.0f %s" : "%.1f %s", value, units[unit]);
    }

    /** Items that a new "Convert" run should pick up. */
    public boolean isConvertible() {
        Status s = status.get();
        return s == Status.PENDING || s == Status.FAILED || s == Status.CANCELLED;
    }

    public void reset() {
        status.set(Status.PENDING);
        progress.set(0);
        message.set("");
        output.set(null);
    }

    public Path source() { return source; }
    public String fileName() { return fileName; }
    public String extension() { return extension; }
    public long sizeBytes() { return sizeBytes; }

    public ObjectProperty<Status> statusProperty() { return status; }
    public Status getStatus() { return status.get(); }
    public void setStatus(Status value) { status.set(value); }

    public DoubleProperty progressProperty() { return progress; }
    public void setProgress(double value) { progress.set(value); }

    public StringProperty messageProperty() { return message; }
    public void setMessage(String value) { message.set(value == null ? "" : value); }

    public ObjectProperty<Path> outputProperty() { return output; }
    public void setOutput(Path value) { output.set(value); }

    public StringBinding statusTextProperty() { return statusText; }
    public StringBinding metaTextProperty() { return metaText; }
}
