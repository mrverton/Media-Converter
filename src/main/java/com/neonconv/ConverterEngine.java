package com.neonconv;

import javafx.beans.property.ReadOnlyBooleanProperty;
import javafx.beans.property.ReadOnlyBooleanWrapper;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.concurrent.ScheduledService;
import javafx.concurrent.Task;
import javafx.util.Duration;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Headless conversion engine. It knows nothing about controls or CSS.
 *
 * <ul>
 *   <li>Each file becomes one JavaFX {@link Task} that runs FFmpeg through {@link ProcessBuilder}
 *       and parses {@code -progress pipe:1} output line by line.</li>
 *   <li>Tasks run on a bounded {@link ThreadPoolExecutor} whose size can change between batches.</li>
 *   <li>A {@link ScheduledService} polls the live (volatile) progress of every task a few times a
 *       second and publishes one aggregated {@link BatchStats}, so the UI is never flooded.</li>
 * </ul>
 *
 * Public methods that touch state ({@link #submit}, {@link #cancelAll}, {@link #close}) must be
 * called from the JavaFX Application Thread.
 */
public final class ConverterEngine implements AutoCloseable {

    // ------------------------------------------------------------------ public types

    public enum Format {
        MP3("MP3", "mp3", true, "libmp3lame"),
        WAV("WAV", "wav", false, "pcm_s16le"),
        FLAC("FLAC", "flac", false, "flac"),
        AAC("AAC (M4A)", "m4a", true, "aac"),
        OGG("OGG Vorbis", "ogg", true, "libvorbis");

        private final String label;
        private final String extension;
        private final boolean lossy;
        private final String codec;

        Format(String label, String extension, boolean lossy, String codec) {
            this.label = label;
            this.extension = extension;
            this.lossy = lossy;
            this.codec = codec;
        }

        public String extension() { return extension; }
        public boolean isLossy() { return lossy; }

        void appendCodecArgs(List<String> cmd, int kbps) {
            cmd.add("-c:a");
            cmd.add(codec);
            if (lossy) {
                cmd.add("-b:a");
                cmd.add(kbps + "k");
            }
            if (this == MP3) {
                // libmp3lame only supports mono/stereo; video soundtracks are often 5.1
                cmd.add("-ac");
                cmd.add("2");
            }
            if (this == FLAC) {
                cmd.add("-compression_level");
                cmd.add("5");
            }
        }

        @Override
        public String toString() { return label; }
    }

    /** Immutable snapshot of the user's choices for one batch. {@code destination == null} means "next to source". */
    public record Settings(Format format, int bitrateKbps, Path destination) { }

    public record BatchStats(int total, int completed, int failed, int cancelled, int running,
                             double fraction, long etaSeconds, boolean finished) {
        public static final BatchStats EMPTY = new BatchStats(0, 0, 0, 0, 0, 0, -1, false);

        public int done() { return completed + failed + cancelled; }
    }

    // ------------------------------------------------------------------ internals

    private static final String FFMPEG = "ffmpeg";
    private static final Pattern DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
    private static final Pattern VERSION = Pattern.compile("version\\s+(\\S+)");
    private static final Pattern PROGRESS_KEY = Pattern.compile(
            "^(frame|fps|stream_\\d+_\\d+_q|bitrate|total_size|out_time_us|out_time_ms|out_time"
                    + "|dup_frames|drop_frames|speed|progress)=.*");

    private final ThreadPoolExecutor pool;
    private final ExecutorService ioPool;
    private final ExecutorService monitorExecutor;
    private final BatchMonitor monitor;

    private final ReadOnlyBooleanWrapper busy = new ReadOnlyBooleanWrapper(false);
    private final ReadOnlyObjectWrapper<BatchStats> stats = new ReadOnlyObjectWrapper<>(BatchStats.EMPTY);

    private volatile List<ConversionTask> batch = List.of();
    private volatile long batchStartNanos;
    private int remaining; // FX thread only

    public ConverterEngine() {
        pool = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), daemonFactory("neon-ffmpeg"));
        pool.allowCoreThreadTimeOut(true);
        ioPool = Executors.newCachedThreadPool(daemonFactory("neon-io"));
        monitorExecutor = Executors.newSingleThreadExecutor(daemonFactory("neon-monitor"));

        monitor = new BatchMonitor();
        monitor.setPeriod(Duration.millis(150));
        monitor.setExecutor(monitorExecutor);
        monitor.setOnSucceeded(e -> {
            BatchStats latest = monitor.getValue();
            if (busy.get() && latest != null) {
                stats.set(latest);
            }
        });
    }

    // ------------------------------------------------------------------ public API

    public ReadOnlyBooleanProperty busyProperty() { return busy.getReadOnlyProperty(); }

    public ReadOnlyObjectProperty<BatchStats> statsProperty() { return stats.getReadOnlyProperty(); }

    /** Runs any background task (file scanning, FFmpeg probing) off the FX thread. */
    public void execute(Task<?> task) {
        ioPool.execute(task);
    }

    /** A task that resolves to the FFmpeg version string, or fails if FFmpeg is not on the PATH. */
    public Task<String> createFfmpegProbeTask() {
        return new Task<>() {
            @Override
            protected String call() throws Exception {
                Process p = new ProcessBuilder(FFMPEG, "-version").redirectErrorStream(true).start();
                try {
                    p.getOutputStream().close();
                    String first;
                    try (BufferedReader r = new BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                        first = r.readLine();
                    }
                    if (!p.waitFor(5, TimeUnit.SECONDS)) {
                        throw new IOException("ffmpeg did not respond");
                    }
                    if (p.exitValue() != 0 || first == null) {
                        throw new IOException("ffmpeg returned exit code " + p.exitValue());
                    }
                    Matcher m = VERSION.matcher(first);
                    return m.find() ? "ffmpeg " + m.group(1) : first;
                } finally {
                    p.destroyForcibly();
                }
            }
        };
    }

    /** Queues the items on the pool. Items are reset to PENDING first. */
    public void submit(List<MediaItem> items, Settings settings, int parallelism) {
        if (items.isEmpty() || busy.get()) {
            return;
        }
        resizePool(parallelism);

        List<ConversionTask> tasks = new ArrayList<>(items.size());
        for (MediaItem item : items) {
            item.reset();
            ConversionTask task = new ConversionTask(item, settings);
            wire(task);
            tasks.add(task);
        }
        remaining = tasks.size();
        batch = List.copyOf(tasks);
        batchStartNanos = System.nanoTime();

        stats.set(computeStats());
        busy.set(true);
        monitor.restart();
        for (ConversionTask task : tasks) {
            pool.execute(task);
        }
    }

    /** Cancels queued and running conversions; partial output files are deleted. */
    public void cancelAll() {
        for (ConversionTask task : batch) {
            task.cancel();
        }
        pool.purge();
    }

    @Override
    public void close() {
        monitor.cancel();
        for (ConversionTask task : batch) {
            task.destroyProcess();
        }
        pool.shutdownNow();
        ioPool.shutdownNow();
        monitorExecutor.shutdownNow();
    }

    // ------------------------------------------------------------------ wiring (FX thread)

    private void wire(ConversionTask task) {
        MediaItem item = task.item;

        task.setOnRunning(e -> {
            item.setProgress(-1);
            item.setStatus(MediaItem.Status.CONVERTING);
        });
        task.progressProperty().addListener((o, was, now) -> {
            if (item.getStatus() == MediaItem.Status.CONVERTING) {
                item.setProgress(now.doubleValue());
            }
        });
        task.setOnSucceeded(e -> {
            item.setOutput(task.getValue());
            item.setProgress(1);
            item.setStatus(MediaItem.Status.COMPLETED);
            taskFinished();
        });
        task.setOnFailed(e -> {
            item.setMessage(describe(task.getException()));
            item.setProgress(0);
            item.setStatus(MediaItem.Status.FAILED);
            taskFinished();
        });
        task.setOnCancelled(e -> {
            item.setProgress(0);
            item.setStatus(MediaItem.Status.CANCELLED);
            taskFinished();
        });
    }

    private void taskFinished() {
        if (--remaining > 0) {
            return;
        }
        monitor.cancel();
        stats.set(computeStats());   // final, exact numbers
        busy.set(false);
        pool.purge();
    }

    private void resizePool(int requested) {
        int size = Math.max(1, Math.min(requested, 16));
        // core must never exceed max, so the order depends on the direction of the change
        if (size > pool.getMaximumPoolSize()) {
            pool.setMaximumPoolSize(size);
            pool.setCorePoolSize(size);
        } else {
            pool.setCorePoolSize(size);
            pool.setMaximumPoolSize(size);
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        String msg = t.getMessage();
        return msg == null || msg.isBlank() ? t.getClass().getSimpleName() : msg;
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    // ------------------------------------------------------------------ aggregated stats (any thread)

    private BatchStats computeStats() {
        List<ConversionTask> tasks = batch;
        int total = tasks.size();
        if (total == 0) {
            return BatchStats.EMPTY;
        }
        int completed = 0;
        int failed = 0;
        int cancelled = 0;
        int running = 0;
        double sum = 0;
        for (ConversionTask t : tasks) {
            if (t.isDone()) {
                sum += 1;
                if (t.isCancelled()) {
                    cancelled++;
                } else if (t.failed) {
                    failed++;
                } else {
                    completed++;
                }
            } else if (t.started) {
                running++;
                sum += Math.max(0, t.live);
            }
        }
        double fraction = sum / total;
        boolean finished = completed + failed + cancelled == total;
        long eta = -1;
        if (!finished && fraction >= 0.03) {
            double elapsed = (System.nanoTime() - batchStartNanos) / 1e9;
            eta = Math.round(elapsed * (1 - fraction) / fraction);
        }
        return new BatchStats(total, completed, failed, cancelled, running, fraction, eta, finished);
    }

    /** Polls {@link #computeStats()} on its own daemon thread, a few times per second. */
    private final class BatchMonitor extends ScheduledService<BatchStats> {
        @Override
        protected Task<BatchStats> createTask() {
            return new Task<>() {
                @Override
                protected BatchStats call() {
                    return computeStats();
                }
            };
        }
    }

    // ------------------------------------------------------------------ one file = one task

    private static final class ConversionTask extends Task<Path> {
        final MediaItem item;
        private final Settings settings;

        volatile boolean started;
        volatile boolean failed;
        volatile double live;
        private volatile Process process;

        ConversionTask(MediaItem item, Settings settings) {
            this.item = item;
            this.settings = settings;
        }

        @Override
        protected Path call() throws Exception {
            started = true;
            Path out = null;
            try {
                out = reserveOutput();
                ProcessBuilder pb = new ProcessBuilder(buildCommand(out));
                pb.redirectErrorStream(true);              // one merged stream: banner + progress
                Process p = pb.start();
                process = p;
                p.getOutputStream().close();               // we never talk to ffmpeg's stdin
                if (isCancelled()) {
                    p.destroy();
                }

                Deque<String> tail = new ArrayDeque<>();
                long durationUs = -1;
                double lastReported = -1;

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (durationUs < 0) {
                            Matcher m = DURATION.matcher(line);
                            if (m.find()) {
                                durationUs = toMicros(m);
                            }
                        }
                        if (line.startsWith("out_time_us=") || line.startsWith("out_time_ms=")) {
                            // both keys carry microseconds in FFmpeg's -progress output
                            long us = parseLong(line.substring(12));
                            if (durationUs > 0 && us >= 0) {
                                double f = Math.min(0.995, (double) us / durationUs);
                                live = f;
                                if (f - lastReported >= 0.002) {
                                    lastReported = f;
                                    updateProgress(f, 1.0);
                                }
                            }
                        } else if (!line.isBlank() && !PROGRESS_KEY.matcher(line).matches()) {
                            if (tail.size() >= 10) {
                                tail.removeFirst();
                            }
                            tail.addLast(line.trim());
                        }
                    }
                }

                int exit = p.waitFor();
                if (isCancelled()) {
                    FileUtils.deleteQuietly(out.toFile());
                    return null;
                }
                if (exit != 0) {
                    throw new IOException(summarize(tail, exit));
                }
                live = 1;
                updateProgress(1, 1);
                return out;
            } catch (InterruptedException ie) {
                // cancel(true) interrupts the worker; treat it as a cancellation
                destroyProcess();
                if (out != null) {
                    FileUtils.deleteQuietly(out.toFile());
                }
                return null;
            } catch (Exception | Error e) {
                if (!isCancelled()) {
                    failed = true;
                }
                if (out != null) {
                    FileUtils.deleteQuietly(out.toFile());
                }
                throw e;
            } finally {
                Process p = process;
                if (p != null && p.isAlive()) {
                    p.destroyForcibly();
                }
            }
        }

        @Override
        protected void cancelled() {
            destroyProcess();   // unblocks the reader thread: its stream hits EOF
        }

        void destroyProcess() {
            Process p = process;
            if (p != null) {
                p.destroy();
            }
        }

        /**
         * Creates the (empty) output file atomically. Two inputs with the same base name
         * (a.mp4 + a.mkv) therefore can't pick the same target while running in parallel,
         * and an existing file is never overwritten: "song (1).mp3", "song (2).mp3", ...
         */
        private Path reserveOutput() throws IOException {
            Path dir = settings.destination() != null ? settings.destination() : item.source().getParent();
            Files.createDirectories(dir);
            String base = FilenameUtils.getBaseName(item.fileName());
            if (base.isBlank()) {
                base = "output";
            }
            String ext = settings.format().extension();
            for (int n = 0; ; n++) {
                Path candidate = dir.resolve(n == 0 ? base + "." + ext : base + " (" + n + ")." + ext);
                try {
                    return Files.createFile(candidate);
                } catch (FileAlreadyExistsException taken) {
                    // try the next suffix
                }
            }
        }

        private List<String> buildCommand(Path out) {
            List<String> cmd = new ArrayList<>(List.of(
                    FFMPEG, "-hide_banner", "-nostdin", "-y",
                    "-i", item.source().toString(),
                    "-vn", "-map", "0:a:0?", "-map_metadata", "0"));
            settings.format().appendCodecArgs(cmd, settings.bitrateKbps());
            cmd.addAll(List.of("-progress", "pipe:1", "-nostats", out.toString()));
            return cmd;
        }

        private static long toMicros(Matcher m) {
            long hours = Long.parseLong(m.group(1));
            long minutes = Long.parseLong(m.group(2));
            double seconds = Double.parseDouble(m.group(3));
            return (long) ((hours * 3600 + minutes * 60 + seconds) * 1_000_000d);
        }

        private static long parseLong(String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return -1;   // "N/A" early in the run
            }
        }

        private static String summarize(Deque<String> tail, int exit) {
            List<String> lines = new ArrayList<>(tail);
            if (lines.isEmpty()) {
                return "ffmpeg exited with code " + exit;
            }
            int from = Math.max(0, lines.size() - 2);
            return String.join("  |  ", lines.subList(from, lines.size()));
        }
    }
}
