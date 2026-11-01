package com.neonconv;

import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * State and commands behind the main window. Contains no JavaFX controls, so the view can be
 * rebuilt (or tested) without touching conversion logic.
 */
public final class MainViewModel {

    public enum FfmpegState { CHECKING, READY, MISSING }

    /** Slider positions map onto these bitrates (kbps). */
    public static final int[] BITRATES = {128, 160, 192, 224, 256, 320};
    private static final int MAX_FOLDER_DEPTH = 8;

    private final ConverterEngine engine = new ConverterEngine();

    /** The extractor makes the list fire "update" events when an item's status changes. */
    private final ObservableList<MediaItem> queue =
            FXCollections.observableArrayList(item -> new Observable[]{item.statusProperty()});
    private final Set<Path> knownSources = new HashSet<>();

    private final ObjectProperty<ConverterEngine.Format> format =
            new SimpleObjectProperty<>(ConverterEngine.Format.MP3);
    private final IntegerProperty bitrateKbps = new SimpleIntegerProperty(192);
    private final IntegerProperty parallelJobs = new SimpleIntegerProperty(defaultJobs());
    private final ObjectProperty<Path> destination = new SimpleObjectProperty<>(defaultDestination());
    private final BooleanProperty sameFolder = new SimpleBooleanProperty(false);

    private final ObjectProperty<FfmpegState> ffmpegState = new SimpleObjectProperty<>(FfmpegState.CHECKING);
    private final StringProperty ffmpegVersion = new SimpleStringProperty("");

    private final BooleanBinding hasConvertible;
    private final BooleanBinding canConvert;

    public MainViewModel() {
        hasConvertible = Bindings.createBooleanBinding(
                () -> queue.stream().anyMatch(MediaItem::isConvertible), queue);
        canConvert = ffmpegState.isEqualTo(FfmpegState.READY)
                .and(engine.busyProperty().not())
                .and(hasConvertible);
    }

    // ------------------------------------------------------------------ commands

    /** Probes {@code ffmpeg -version} on a background thread. */
    public void checkFfmpeg() {
        ffmpegState.set(FfmpegState.CHECKING);
        Task<String> probe = engine.createFfmpegProbeTask();
        probe.setOnSucceeded(e -> {
            ffmpegVersion.set(probe.getValue());
            ffmpegState.set(FfmpegState.READY);
        });
        probe.setOnFailed(e -> {
            ffmpegVersion.set("");
            ffmpegState.set(FfmpegState.MISSING);
        });
        engine.execute(probe);
    }

    /**
     * Adds files and (recursively) the media files inside folders. Scanning and file-size lookups
     * happen on a worker thread, and the queue receives a single bulk update, so dropping several
     * hundred files neither blocks the UI nor floods the list with change events.
     */
    public void addPaths(Collection<Path> inputs) {
        List<Path> snapshot = List.copyOf(inputs);
        Task<List<MediaItem>> scan = new Task<>() {
            @Override
            protected List<MediaItem> call() {
                Map<Path, MediaItem> found = new LinkedHashMap<>();
                for (Path input : snapshot) {
                    if (Files.isDirectory(input)) {
                        collectFolder(input, found);
                    } else if (Files.isRegularFile(input)) {
                        MediaItem item = new MediaItem(input);
                        found.putIfAbsent(item.source(), item);
                    }
                }
                return List.copyOf(found.values());
            }
        };
        scan.setOnSucceeded(e -> {
            List<MediaItem> fresh = scan.getValue().stream()
                    .filter(item -> knownSources.add(item.source()))
                    .toList();
            if (!fresh.isEmpty()) {
                queue.addAll(fresh);
            }
        });
        engine.execute(scan);
    }

    private static void collectFolder(Path folder, Map<Path, MediaItem> into) {
        try {
            Files.walkFileTree(folder, Set.of(), MAX_FOLDER_DEPTH, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile() && MediaItem.looksLikeMedia(file)) {
                        MediaItem item = new MediaItem(file);
                        into.putIfAbsent(item.source(), item);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // unreadable entries are skipped
                }
            });
        } catch (IOException ignored) {
            // an unreadable folder simply contributes nothing
        }
    }

    public void remove(MediaItem item) {
        if (engine.busyProperty().get()) {
            return;
        }
        if (queue.remove(item)) {
            knownSources.remove(item.source());
        }
    }

    public void clearCompleted() {
        if (engine.busyProperty().get()) {
            return;
        }
        List<MediaItem> done = queue.stream()
                .filter(i -> i.getStatus() == MediaItem.Status.COMPLETED)
                .toList();
        queue.removeAll(done);
        done.forEach(i -> knownSources.remove(i.source()));
    }

    public void clearAll() {
        if (engine.busyProperty().get()) {
            return;
        }
        queue.clear();
        knownSources.clear();
    }

    /** @return {@code null} when the batch started, otherwise a message the view should show. */
    public String startConversion() {
        List<MediaItem> todo = queue.stream().filter(MediaItem::isConvertible).toList();
        if (todo.isEmpty()) {
            return "Nothing to convert yet. Drop some files into the queue first.";
        }
        Path target = null;
        if (!sameFolder.get()) {
            target = destination.get();
            if (target == null) {
                return "Choose a destination folder, or tick \"Save next to source files\".";
            }
            try {
                Files.createDirectories(target);
            } catch (IOException ex) {
                return "The destination folder could not be created:\n" + target + "\n\n" + ex.getMessage();
            }
            if (!Files.isWritable(target)) {
                return "The destination folder is not writable:\n" + target;
            }
        }
        engine.submit(todo,
                new ConverterEngine.Settings(format.get(), bitrateKbps.get(), target),
                parallelJobs.get());
        return null;
    }

    public void cancel() {
        engine.cancelAll();
    }

    public void shutdown() {
        engine.close();
    }

    // ------------------------------------------------------------------ observable state

    public ObservableList<MediaItem> queue() { return queue; }
    public ObjectProperty<ConverterEngine.Format> formatProperty() { return format; }
    public IntegerProperty bitrateKbpsProperty() { return bitrateKbps; }
    public IntegerProperty parallelJobsProperty() { return parallelJobs; }
    public ObjectProperty<Path> destinationProperty() { return destination; }
    public BooleanProperty sameFolderProperty() { return sameFolder; }
    public ObjectProperty<FfmpegState> ffmpegStateProperty() { return ffmpegState; }
    public StringProperty ffmpegVersionProperty() { return ffmpegVersion; }
    public BooleanBinding canConvertProperty() { return canConvert; }
    public ReadOnlyBooleanProperty busyProperty() { return engine.busyProperty(); }
    public ReadOnlyObjectProperty<ConverterEngine.BatchStats> statsProperty() { return engine.statsProperty(); }

    // ------------------------------------------------------------------ defaults

    private static int defaultJobs() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
    }

    private static Path defaultDestination() {
        Path home = Path.of(System.getProperty("user.home", "."));
        Path music = home.resolve("Music");
        return (Files.isDirectory(music) ? music : home).resolve("NeonSonic");
    }
}
