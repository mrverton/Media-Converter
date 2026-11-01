package com.neonconv;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.application.HostServices;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.input.DragEvent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.apache.commons.lang3.SystemUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The view layer: wires FXML nodes to {@link MainViewModel}, owns drag-and-drop and all purely
 * visual behaviour (smooth progress, pulsing border, status pill). No conversion logic lives here.
 */
public final class MainController {

    private static final PseudoClass DRAG_OVER = PseudoClass.getPseudoClass("drag-over");
    private static final String FFMPEG_DOWNLOAD_URL = "https://ffmpeg.org/download.html";

    @FXML private BorderPane root;
    @FXML private HBox statusPill;
    @FXML private Label statusLabel;
    @FXML private StackPane dropZone;
    @FXML private Rectangle dropBorder;
    @FXML private Label queueCountLabel;
    @FXML private ListView<MediaItem> queueList;
    @FXML private ComboBox<ConverterEngine.Format> formatCombo;
    @FXML private Slider bitrateSlider;
    @FXML private Label bitrateLabel;
    @FXML private Label bitrateHint;
    @FXML private ComboBox<Integer> jobsCombo;
    @FXML private TextField destinationField;
    @FXML private Button browseButton;
    @FXML private CheckBox sameFolderCheck;
    @FXML private Label engineInfoLabel;
    @FXML private WaveVisualizer visualizer;
    @FXML private Label overallLabel;
    @FXML private Label etaLabel;
    @FXML private ProgressBar overallBar;
    @FXML private Button cancelButton;
    @FXML private Button convertButton;

    private final MainViewModel vm = new MainViewModel();

    private Timeline progressAnimation;
    private File lastDirectory;
    private HostServices hostServices;
    private boolean ffmpegDialogOpen;
    private boolean showBatchSummary;

    // ------------------------------------------------------------------ setup

    @FXML
    private void initialize() {
        setupQueue();
        setupOutputControls();
        setupDropZone();
        setupActionBar();
        setupStatus();
    }

    void setHostServices(HostServices hostServices) {
        this.hostServices = hostServices;
    }

    /** Called once the window is visible. */
    void onShown() {
        vm.checkFfmpeg();
        styleSliderTrack();
    }

    private void setupQueue() {
        queueList.setItems(vm.queue());
        queueList.setFixedCellSize(80);
        queueList.setCellFactory(lv -> new QueueCell(vm.busyProperty(), vm::remove));

        Label placeholder = new Label("Queue is empty\nDrop files above or use + Files");
        placeholder.getStyleClass().add("placeholder");
        placeholder.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        queueList.setPlaceholder(placeholder);

        queueCountLabel.textProperty().bind(Bindings.size(vm.queue()).asString());

        vm.queue().addListener((ListChangeListener<MediaItem>) change -> {
            while (change.next()) {
                if (change.wasAdded() || change.wasRemoved()) {
                    if (showBatchSummary) {
                        showBatchSummary = false;
                        resetOverallBar();
                    }
                }
            }
            refreshOverall();
        });
    }

    private void setupOutputControls() {
        // format
        formatCombo.setItems(FXCollections.observableArrayList(ConverterEngine.Format.values()));
        formatCombo.valueProperty().bindBidirectional(vm.formatProperty());

        // bitrate: the slider selects an index into BITRATES
        bitrateSlider.setMin(0);
        bitrateSlider.setMax(MainViewModel.BITRATES.length - 1);
        bitrateSlider.setBlockIncrement(1);
        bitrateSlider.setMajorTickUnit(1);
        bitrateSlider.setMinorTickCount(0);
        bitrateSlider.setSnapToTicks(true);
        bitrateSlider.setValue(indexOfBitrate(vm.bitrateKbpsProperty().get()));
        bitrateSlider.valueProperty().addListener((o, was, now) -> {
            vm.bitrateKbpsProperty().set(MainViewModel.BITRATES[(int) Math.round(now.doubleValue())]);
            styleSliderTrack();
        });
        bitrateSlider.skinProperty().addListener((o, was, skin) -> Platform.runLater(this::styleSliderTrack));
        bitrateSlider.disableProperty().bind(
                Bindings.createBooleanBinding(() -> !vm.formatProperty().get().isLossy(), vm.formatProperty()));
        bitrateLabel.textProperty().bind(Bindings.createStringBinding(
                () -> vm.formatProperty().get().isLossy()
                        ? vm.bitrateKbpsProperty().get() + " kbps"
                        : "LOSSLESS",
                vm.formatProperty(), vm.bitrateKbpsProperty()));
        bitrateHint.textProperty().bind(Bindings.createStringBinding(
                () -> vm.formatProperty().get().isLossy()
                        ? "Higher bitrate means better fidelity and larger files."
                        : "Lossless codec: the bitrate setting is not used.",
                vm.formatProperty()));

        // parallelism
        jobsCombo.setItems(FXCollections.observableArrayList(1, 2, 3, 4, 6, 8));
        jobsCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(Integer n) {
                return n == null ? "" : n == 1 ? "1 file at a time" : n + " files in parallel";
            }

            @Override
            public Integer fromString(String s) {
                return null;
            }
        });
        jobsCombo.setValue(vm.parallelJobsProperty().get());
        jobsCombo.valueProperty().addListener((o, was, now) -> {
            if (now != null) {
                vm.parallelJobsProperty().set(now);
            }
        });

        // destination
        destinationField.textProperty().bind(Bindings.createStringBinding(
                () -> vm.destinationProperty().get() == null ? "" : vm.destinationProperty().get().toString(),
                vm.destinationProperty()));
        destinationField.disableProperty().bind(vm.sameFolderProperty());
        browseButton.disableProperty().bind(vm.sameFolderProperty());
        sameFolderCheck.selectedProperty().bindBidirectional(vm.sameFolderProperty());

        // engine info
        engineInfoLabel.textProperty().bind(Bindings.createStringBinding(() -> switch (vm.ffmpegStateProperty().get()) {
            case CHECKING -> "Probing FFmpeg...";
            case READY -> vm.ffmpegVersionProperty().get();
            case MISSING -> "FFmpeg not found on PATH";
        }, vm.ffmpegStateProperty(), vm.ffmpegVersionProperty()));
    }

    private void setupDropZone() {
        dropBorder.setLayoutX(2);
        dropBorder.setLayoutY(2);
        dropBorder.widthProperty().bind(dropZone.widthProperty().subtract(4));
        dropBorder.heightProperty().bind(dropZone.heightProperty().subtract(4));

        dropZone.setCursor(Cursor.HAND);
        dropZone.setOnMouseClicked(e -> onAddFiles());

        // the whole window accepts drops; the drop zone just highlights
        root.setOnDragOver(this::onDragOver);
        root.setOnDragEntered(e -> setDragActive(e.getDragboard().hasFiles()));
        root.setOnDragExited(e -> setDragActive(false));
        root.setOnDragDropped(this::onDragDropped);
    }

    private void setupActionBar() {
        overallBar.setProgress(0);

        cancelButton.visibleProperty().bind(vm.busyProperty());
        cancelButton.managedProperty().bind(cancelButton.visibleProperty());

        convertButton.disableProperty().bind(vm.canConvertProperty().not());
        convertButton.textProperty().bind(
                Bindings.when(vm.busyProperty()).then("Converting...").otherwise("Convert"));

        vm.busyProperty().addListener((o, was, busy) -> {
            visualizer.setActive(busy);
            if (busy) {
                showBatchSummary = false;
                resetOverallBar();
            } else {
                showBatchSummary = true;
            }
            refreshOverall();
            refreshStatus();

            // batch just finished: show the result in the system file manager
            if (was && !busy) {
                ConverterEngine.BatchStats done = vm.statsProperty().get();
                if (done.completed() > 0 && done.cancelled() == 0) {
                    openDestinationFolder();
                }
            }
        });
        vm.statsProperty().addListener((o, was, stats) -> {
            if (vm.busyProperty().get() || stats.finished()) {
                animateProgress(stats.fraction());
            }
            refreshOverall();
        });
        refreshOverall();
    }

    /** Opens the output folder (or the folder of the first converted file in "next to source" mode). */
    private void openDestinationFolder() {
        java.nio.file.Path folder = null;
        if (!vm.sameFolderProperty().get()) {
            folder = vm.destinationProperty().get();
        } else {
            for (MediaItem item : vm.queue()) {
                if (item.getStatus() == MediaItem.Status.COMPLETED && item.outputProperty().get() != null) {
                    folder = item.outputProperty().get().getParent();
                    break;
                }
            }
        }
        if (folder == null || !java.nio.file.Files.isDirectory(folder)) {
            return;
        }
        File dir = folder.toFile();
        // every AWT call stays off the JavaFX thread: it must not block (or deadlock with) the UI
        Thread opener = new Thread(() -> {
            try {
                if (java.awt.Desktop.isDesktopSupported()
                        && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.OPEN)) {
                    java.awt.Desktop.getDesktop().open(dir);
                }
            } catch (Exception ignored) {
                // no file manager available: the result is still visible in the queue
            }
        }, "open-output-folder");
        opener.setDaemon(true);
        opener.start();
    }

    private void setupStatus() {
        vm.ffmpegStateProperty().addListener((o, was, state) -> {
            refreshStatus();
            if (state == MainViewModel.FfmpegState.MISSING) {
                Platform.runLater(this::showFfmpegMissingDialog);
            }
        });
        statusPill.setOnMouseClicked(e -> {
            if (vm.ffmpegStateProperty().get() == MainViewModel.FfmpegState.MISSING) {
                showFfmpegMissingDialog();
            }
        });
        refreshStatus();
    }

    // ------------------------------------------------------------------ drag and drop

    private void onDragOver(DragEvent e) {
        if (e.getDragboard().hasFiles()) {
            e.acceptTransferModes(TransferMode.ANY);
        }
        e.consume();
    }

    private void onDragDropped(DragEvent e) {
        Dragboard board = e.getDragboard();
        boolean ok = board.hasFiles();
        if (ok) {
            vm.addPaths(board.getFiles().stream().map(File::toPath).toList());
        }
        setDragActive(false);
        e.setDropCompleted(ok);
        e.consume();
    }

    private void setDragActive(boolean on) {
        dropZone.pseudoClassStateChanged(DRAG_OVER, on);
    }

    // ------------------------------------------------------------------ FXML handlers

    @FXML
    private void onAddFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select media files");
        List<String> globs = new ArrayList<>();
        MediaItem.MEDIA_EXTENSIONS.stream().sorted().forEach(ext -> {
            globs.add("*." + ext);
            globs.add("*." + ext.toUpperCase(Locale.ROOT));   // case-sensitive file systems
        });
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Audio and video files", globs),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        List<File> files = chooser.showOpenMultipleDialog(window());
        if (files != null && !files.isEmpty()) {
            lastDirectory = files.get(0).getParentFile();
            vm.addPaths(files.stream().map(File::toPath).toList());
        }
    }

    @FXML
    private void onAddFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Add every media file in a folder");
        if (lastDirectory != null && lastDirectory.isDirectory()) {
            chooser.setInitialDirectory(lastDirectory);
        }
        File dir = chooser.showDialog(window());
        if (dir != null) {
            lastDirectory = dir;
            vm.addPaths(List.of(dir.toPath()));
        }
    }

    @FXML
    private void onBrowseDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose destination folder");
        if (vm.destinationProperty().get() != null) {
            File current = vm.destinationProperty().get().toFile();
            while (current != null && !current.isDirectory()) {
                current = current.getParentFile();
            }
            if (current != null) {
                chooser.setInitialDirectory(current);
            }
        }
        File dir = chooser.showDialog(window());
        if (dir != null) {
            vm.destinationProperty().set(dir.toPath());
        }
    }

    @FXML
    private void onClearCompleted() {
        vm.clearCompleted();
    }

    @FXML
    private void onClearAll() {
        vm.clearAll();
    }

    @FXML
    private void onCancel() {
        vm.cancel();
    }

    @FXML
    private void onConvert() {
        String problem = vm.startConversion();
        if (problem != null) {
            NeonDialog.show(window(), NeonDialog.Kind.INFO, "Can't start yet", problem, "OK");
        }
    }

    // ------------------------------------------------------------------ lifecycle (called by App)

    boolean canClose() {
        if (!vm.busyProperty().get()) {
            return true;
        }
        int choice = NeonDialog.show(window(), NeonDialog.Kind.DANGER, "Conversion in progress",
                "Files are still being converted. Quitting now cancels the batch and deletes the "
                        + "partially written output files.",
                "KEEP WORKING", "QUIT ANYWAY");
        return choice == 1;
    }

    void shutdown() {
        vm.shutdown();
    }

    // ------------------------------------------------------------------ visual state

    private void refreshStatus() {
        String text;
        String state;
        switch (vm.ffmpegStateProperty().get()) {
            case CHECKING -> { text = "CHECKING FFMPEG"; state = "idle"; }
            case MISSING -> { text = "FFMPEG MISSING"; state = "error"; }
            default -> {
                if (vm.busyProperty().get()) {
                    text = "CONVERTING";
                    state = "busy";
                } else {
                    text = "ENGINE READY";
                    state = "ok";
                }
            }
        }
        statusLabel.setText(text);
        statusPill.getStyleClass().removeAll("idle", "error", "busy", "ok");
        statusPill.getStyleClass().add(state);
    }

    private void refreshOverall() {
        ConverterEngine.BatchStats stats = vm.statsProperty().get();
        boolean busy = vm.busyProperty().get();
        int queued = vm.queue().size();

        if (busy) {
            overallLabel.setText(String.format(Locale.ROOT, "CONVERTING   %d / %d   \u00B7   %d active",
                    stats.done(), stats.total(), stats.running()));
            etaLabel.setText(stats.etaSeconds() >= 0 ? "ETA " + formatDuration(stats.etaSeconds()) : "ETA ...");
        } else if (showBatchSummary && stats.total() > 0 && stats.finished()) {
            overallLabel.setText(String.format(Locale.ROOT,
                    "BATCH COMPLETE   %d ok   \u00B7   %d failed   \u00B7   %d cancelled",
                    stats.completed(), stats.failed(), stats.cancelled()));
            etaLabel.setText("");
            overallBar.getStyleClass().removeAll("complete", "has-errors");
            overallBar.getStyleClass().add(stats.failed() == 0 && stats.cancelled() == 0 ? "complete" : "has-errors");
        } else {
            overallLabel.setText(queued == 0
                    ? "IDLE   \u00B7   drop files to begin"
                    : queued + (queued == 1 ? " file" : " files") + " ready");
            etaLabel.setText("");
        }
    }

    private void animateProgress(double target) {
        if (progressAnimation != null) {
            progressAnimation.stop();
        }
        progressAnimation = new Timeline(new KeyFrame(Duration.millis(280),
                new KeyValue(overallBar.progressProperty(), target, Interpolator.EASE_OUT)));
        progressAnimation.play();
    }

    private void resetOverallBar() {
        if (progressAnimation != null) {
            progressAnimation.stop();
        }
        overallBar.getStyleClass().removeAll("complete", "has-errors");
        overallBar.setProgress(0);
    }

    /** JavaFX sliders have no "filled" track, so the gradient stop follows the thumb. */
    private void styleSliderTrack() {
        Node track = bitrateSlider.lookup(".track");
        if (track == null) {
            return;
        }
        double span = bitrateSlider.getMax() - bitrateSlider.getMin();
        int pct = (int) Math.round((bitrateSlider.getValue() - bitrateSlider.getMin()) / span * 100);
        track.setStyle(String.format(Locale.ROOT,
                "-fx-background-color: linear-gradient(to right, #0078D7 %d%%, #D6D6D6 %d%%);", pct, pct));
    }

    private void showFfmpegMissingDialog() {
        if (ffmpegDialogOpen) {
            return;
        }
        ffmpegDialogOpen = true;
        try {
            String install = SystemUtils.IS_OS_WINDOWS ? "winget install Gyan.FFmpeg"
                    : SystemUtils.IS_OS_MAC ? "brew install ffmpeg"
                    : "sudo apt install ffmpeg";
            String message = "NEON SONIC needs FFmpeg to decode and encode media, but \"ffmpeg\" could not "
                    + "be started from your system PATH.\n\nInstall it, for example:\n\n      " + install
                    + "\n\nThen restart this app, or open a new terminal session first, and press RETRY.";
            while (true) {
                int choice = NeonDialog.show(window(), NeonDialog.Kind.DANGER, "FFmpeg not detected", message,
                        "RETRY CHECK", "DOWNLOAD PAGE", "CLOSE");
                if (choice == 0) {
                    vm.checkFfmpeg();
                    return;
                }
                if (choice == 1 && hostServices != null) {
                    hostServices.showDocument(FFMPEG_DOWNLOAD_URL);
                    continue;   // show the dialog again after the browser opens
                }
                return;
            }
        } finally {
            ffmpegDialogOpen = false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Window window() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static int indexOfBitrate(int kbps) {
        for (int i = 0; i < MainViewModel.BITRATES.length; i++) {
            if (MainViewModel.BITRATES[i] == kbps) {
                return i;
            }
        }
        return 2;
    }

    private static String formatDuration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%02d:%02d", m, s);
    }
}
