package net.microstar.common.io;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static net.microstar.common.util.ExceptionUtils.rethrow;

/** Get notified when specific files are deleted */
public class DeletedFileNotifier {
    private static final Map<Path,DeleteFileWatcher> watchers = new ConcurrentHashMap<>();

    /** Watch the given file and create it if it doesn't yet exist and call runOnDelete when the file is deleted. */
    public static void forFile(Path file, String description, Runnable runOnDelete) {
        if(!Files.exists(file)) {
            IOUtils.makeSureDirectoryExists(file.toAbsolutePath().getParent());
            rethrow(() -> Files.writeString(file, description), ex -> new IllegalStateException("Unable to create file: " + file));
        }
        if(!Files.isRegularFile(file)) throw new IllegalArgumentException(DeletedFileNotifier.class.getSimpleName() + " only watches files. Not a file: " + file);
        watchers.put(file, new DeleteFileWatcher(file, runOnDelete, DeletedFileNotifier::finished).startWatching());
    }
    public static void stopWatchingFile(String name) { stopWatchingFile(Path.of(name)); }
    public static void stopWatchingFile(Path file) { Optional.ofNullable(watchers.get(file)).ifPresent(DeleteFileWatcher::stopWatching); }
    public static void stopWatchingAll() { Set.copyOf(watchers.keySet()).forEach(DeletedFileNotifier::stopWatchingFile); }
    private static void finished(DeleteFileWatcher finishedWatcher) { watchers.remove(finishedWatcher.fileToWatch); }
}

/** The assumption is that no more than a handful specific files will be watched, so creating
  * a thread for each of them isn't a problem.
  */
class DeleteFileWatcher extends Thread {
    public final Path fileToWatch;
    private final Runnable runOnDeleted;
    private final Consumer<DeleteFileWatcher> runOnFinished;
    private final AtomicBoolean stop = new AtomicBoolean(false);

    public DeleteFileWatcher(Path fileToWatch, Runnable runOnDeleted, Consumer<DeleteFileWatcher> runOnFinished) {
        this.fileToWatch = fileToWatch.toAbsolutePath();
        this.runOnDeleted = runOnDeleted;
        this.runOnFinished = runOnFinished;
        setDaemon(true);
    }
    public boolean isStopped() { return stop.get(); }
    public DeleteFileWatcher startWatching() { start(); return this; }
    public void stopWatching() { stop.set(true); interrupt(); }

    @Override
    public void run() {
        try (final WatchService watcher = FileSystems.getDefault().newWatchService()) {
            final Path watchedDir = fileToWatch.toAbsolutePath().getParent();
            watchedDir.register(watcher, StandardWatchEventKinds.ENTRY_DELETE);
            boolean detectedDeletion = !Files.exists(fileToWatch);

            while (!isStopped() && !detectedDeletion) {
                WatchKey key;
                try { key = watcher.poll(500, TimeUnit.MILLISECONDS); } catch (final InterruptedException e) { return; } // NOSONAR: stops threads
                // Mac doesn't detect deletions, so poll
                if(!Files.exists(fileToWatch)) detectedDeletion = true;

                if(key != null) for (WatchEvent<?> event : key.pollEvents()) {
                    detectedDeletion |= event.kind() == StandardWatchEventKinds.ENTRY_DELETE
                                     && watchedDir.resolve((Path)event.context()).equals(fileToWatch)
                                     && !Files.exists(fileToWatch);

                    if (!key.reset()) break;
                }
                if(detectedDeletion && !stop.get()) runOnDeleted.run();
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        runOnFinished.accept(this);
    }
}