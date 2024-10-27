package net.microstar.common.io;

import io.methvin.watcher.DirectoryChangeEvent;
import io.methvin.watcher.DirectoryWatcher;
import lombok.RequiredArgsConstructor;
import org.slf4j.helpers.NOPLogger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static net.microstar.common.util.ThreadUtils.debounce;
import static net.microstar.common.util.Utils.sleep;

/** Recursively watch files and directories for changes.
  * Note that the close() method needs to be called after a watch() was done or
  * instances will keep going even if no longer referenced.
  */
public class FileTreeChangeDetector {
    private static final Duration MODIFIED_DEBOUNCE_DURATION = Duration.ofMillis(2000);
    private static final Cleaner cleaner = Cleaner.create();
    private final State state;
    private boolean ignoreAll = false;
    private long ignoreAllTime = 0L;
    private boolean onlyFiles = true;

    private static class PathInfo {
        long lastInfoChange = System.currentTimeMillis();
        long endOfIgnore;
        long lastHash;
        void changed() { lastInfoChange = System.currentTimeMillis(); }
    }

    @RequiredArgsConstructor
    private static class State implements Runnable {
        private final DirectoryWatcher watcher;
        private final Map<Path,PathInfo> pathToInfo = new ConcurrentHashMap<>();

        /** Called by the cleaner when garbage collecting */
        public void run() {
            try { watcher.close(); } catch (final IOException dontCare) {/*nothing can be done from Cleaner*/}
        }
    }
    public enum ChangeType {
        CREATED, MODIFIED, DELETED, OVERFLOW
    }

    public FileTreeChangeDetector(Path dirToWatch, BiConsumer<Path,ChangeType> changeHandler) {
        this(Collections.singletonList(dirToWatch), changeHandler);
    }
    public FileTreeChangeDetector(Path dirToWatch, BiConsumer<Path,ChangeType> changeHandler, Consumer<Exception> exceptionHandler) {
        this(Collections.singletonList(dirToWatch), changeHandler, exceptionHandler);
    }
    public FileTreeChangeDetector(List<Path> dirsToWatch, BiConsumer<Path,ChangeType> changeHandler) {
        this(dirsToWatch, changeHandler, ex -> {});
    }
    @SuppressWarnings("this-escape")
    public FileTreeChangeDetector(List<Path> dirsToWatch, BiConsumer<Path,ChangeType> changeHandler, Consumer<Exception> exceptionHandler) {
        final List<Path> dirs = dirsToWatch.stream()
            .filter(Objects::nonNull)
            .peek(dir -> { if(!dir.toFile().exists()) throw new IllegalArgumentException("Cannot watch non-existing directory " + dir); })
            .toList();
        if (dirs.isEmpty()) throw new IllegalArgumentException("No dirs to watch");
        try {
            state = new State(DirectoryWatcher.builder()
                .paths(dirs)
                .listener(event -> {
                    if((ignoreAll && event.path().toFile().lastModified() > ignoreAllTime) // ignore events from before settings the ignoreAll flag
                        || (onlyFiles && Files.isDirectory(event.path()))
                        || isIgnored(event.path(), event.eventType())
                        || !isHashChanged(event)) return;

                    try {
                        handleRawEvent(changeHandler, event);
                    } catch(final Exception e) {
                        exceptionHandler.accept(e);
                    }
                })
                .fileHasher(null) // hasher is not including type, so we need to do that ourselves
                .logger(NOPLogger.NOP_LOGGER)
                .build());

            cleaner.register(this, state);
        } catch(final IOException e) {
            throw new IllegalStateException("Unable to start", e);
        }
    }

    private void handleRawEvent(BiConsumer<Path,ChangeType> changeHandler, io.methvin.watcher.DirectoryChangeEvent event) {
        final Path path = event.path();
        final Consumer<ChangeType> call = evt -> changeHandler.accept(path, evt);

        switch(event.eventType()) {
            case CREATE   -> call.accept(ChangeType.CREATED);
            case MODIFY   -> {
                // A single 'this file has modified' event is not particularly useful if the modifying
                // hasn't finished yet. However, we won't get a 'modification has finished' event. As
                // a workaround, debounce the MODIFY event which triggers the MODIFIED event when no
                // more MODIFY events are received within the debounce time.
                // This has the unfortunate side effect that if the file is deleted just after modification,
                // a MODIFIED event may be sent after deletion. To prevent his, check for existence before
                // sending the actual MODIFIED event.
                // (alternative is to check if the file is locked for writing, but that can only be tested
                // by locking the file which may prevent the other side from writing (tested and that happens)
                // and often the file is not locked between writes anyway)
                if(Files.isDirectory(path)) call.accept(ChangeType.MODIFIED);
                else debounce("MODIFIED:" + path , MODIFIED_DEBOUNCE_DURATION,
                        () -> { if(path.toFile().exists()) call.accept(ChangeType.MODIFIED); });
            }
            case DELETE   -> call.accept(ChangeType.DELETED);
            case OVERFLOW -> call.accept(ChangeType.OVERFLOW);
        }
    }

    public FileTreeChangeDetector watch() {
        state.watcher.watchAsync();
        return this;
    }

    /** Set if events should be generated for file only (default) or for directories as well */
    public FileTreeChangeDetector setOnlyFiles(boolean set) {
        onlyFiles = set;
        return this;
    }
    public FileTreeChangeDetector setIgnoreAll(boolean set) {
        ignoreAll = set;
        ignoreAllTime = System.currentTimeMillis();
        // To prevent old events surfacing after the setIgnoreAll(true) is requested,
        // sleep a small bit so the lastModified of the file can be checked.
        sleep(10); // yes, an annoying side-effect (aren't they all) -- file systems are annoying

        // Even then, the file system can take longer to generate events.
        // Then ignoreAllTime is used to compare the file lastModified.

        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public FileTreeChangeDetector ignorePath(Path path, Duration timeToIgnore) {
        final long endTime = System.currentTimeMillis() + timeToIgnore.toMillis();
        final PathInfo info = state.pathToInfo.computeIfAbsent(path, p -> new PathInfo());
        info.endOfIgnore = Math.max(info.endOfIgnore, endTime);
        prunePathInfos();
        return this;
    }
    public boolean isIgnored(Path path, DirectoryChangeEvent.EventType eventType) {
        final @Nullable PathInfo pathInfo = state.pathToInfo.get(path);
        if(pathInfo == null) return false; // never ignore unknown paths
        pathInfo.changed();

        return eventType == DirectoryChangeEvent.EventType.CREATE  // ignore when receiving a CREATE on a known path
            || pathInfo.endOfIgnore > System.currentTimeMillis()   // ignore when user ignored the file for a duration
            ;
    }
    private boolean isHashChanged(DirectoryChangeEvent event) {
        final PathInfo info = state.pathToInfo.computeIfAbsent(event.path(), p -> new PathInfo());
        final int newHash = hashFromEvent(event);

        if(newHash == info.lastHash) return false;
        info.lastHash = newHash;
        return true;
    }
    private void prunePathInfos() {
        final long now = System.currentTimeMillis();
        state.pathToInfo.entrySet()
            .removeIf(entry -> entry.getValue().endOfIgnore < now
                            && entry.getValue().lastInfoChange < now - 5_000);
    }
    private static int hashFromEvent(DirectoryChangeEvent event) {
        return Objects.hash(
            event.eventType(),
            event.path().toFile().lastModified(),
            event.path().toFile().length()
        );
    }

    /** This will terminate the watcher thread. The watcher thread will also terminate when this is garbage collected. */
    public void close() {
        setIgnoreAll(true); // in case events come in after this call was made
        state.run();
    }
    public boolean isClosed() {
        return state.watcher.isClosed();
    }
}