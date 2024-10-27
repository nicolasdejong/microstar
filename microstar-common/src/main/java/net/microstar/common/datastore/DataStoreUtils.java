package net.microstar.common.datastore;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.exceptions.WrappedException;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.Threads;

import javax.annotation.Nullable;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ExceptionUtils.rethrow;
import static net.microstar.common.util.ThreadUtils.throttle;

@Slf4j
public final class DataStoreUtils {
    private DataStoreUtils() {}

    @Builder(toBuilder = true) @ToString
    public static class CopyProgressInfo {
        public final UUID id;
        public final int countDone;
        public final int count;
        public final long sizeDone;
        public final long size;
        @Builder.Default
        public final String message = "";
        @Builder.Default
        public final String error = "";
    }
    private static final Set<UUID> copiesInProgress = new HashSet<>();

    public static UUID copy(DataStore sourceStore, String sourcePath, DataStore targetStore, String targetPath) { return copy(sourceStore, sourcePath, targetStore, targetPath, cpi -> {}); }
    public static UUID copy(DataStore sourceStore, String sourcePath, DataStore targetStore, String targetPath, Consumer<CopyProgressInfo> progressHandler) {
        final UUID copyId = UUID.randomUUID();
        synchronized (copiesInProgress) { copiesInProgress.add(copyId); }
        final Consumer<String> fail = error -> log.info("FAIL: {}", error);

        Threads.execute(() ->
            sourceStore.list(sourcePath, /*recursive=*/true).thenAccept(copyList -> {
                try {
                    if(copyList.isEmpty()) {
                        copySingle(sourceStore, sourcePath, targetStore, targetPath, transferred -> {}, fail);
                    } else {
                        copy(copyId, copyList, sourceStore, sourcePath, targetStore, targetPath, progressHandler);
                    }
                } catch (final Exception e) {
                    fail.accept("Copy failed! " + e.getMessage());
                }
                synchronized (copiesInProgress) {
                    copiesInProgress.remove(copyId);
                    copiesInProgress.notifyAll();
                }
                progressHandler.accept(CopyProgressInfo.builder().message("Finished").build());
            })
        );
        return copyId;
    }
    private static void copy(UUID copyId, List<DataStore.Item> copyList, DataStore sourceStore, String sourcePath, DataStore targetStore, String targetPath, Consumer<CopyProgressInfo> progressHandler) {
        final Consumer<CopyProgressInfo> throttledProgressHandler = throttle(progressHandler, Duration.ofSeconds(1));
        final AtomicReference<CopyProgressInfo> progressInfo = new AtomicReference<>(CopyProgressInfo.builder()
            .id(copyId)
            .count(copyList.size())
            .size(copyList.stream().mapToLong(item -> item.size).sum())
            .build());
        final Consumer<Function<CopyProgressInfo, CopyProgressInfo>> updateProgress = handler -> {
            progressInfo.set(handler.apply(progressInfo.get()));
            throttledProgressHandler.accept(progressInfo.get());
        };
        final Consumer<String> fail = error -> {
            log.info("FAIL: {}", error);
            progressHandler.accept(progressInfo.get().toBuilder().message("Copy failed").error(error).build());
        };

        for (final DataStore.Item item : copyList) {
            synchronized (copiesInProgress) {
                if (!copiesInProgress.contains(copyId)) {
                    log.warn("Copy stopped");
                    copiesInProgress.notifyAll();
                    break;
                }
            }
            final long startSizeDone = progressInfo.get().sizeDone;
            final LongConsumer progress = transferred ->
                updateProgress.accept(pi -> pi.toBuilder().sizeDone(startSizeDone + transferred).build());

            copySingle(sourceStore, IOUtils.concatPath(sourcePath, item.path), targetStore, IOUtils.concatPath(targetPath, item.path), progress, fail);

            updateProgress.accept(pi -> pi.toBuilder().countDone(pi.countDone + 1).build());
        }
    }
    private static void copySingle(DataStore sourceStore, String sourcePath, DataStore targetStore, String targetPath, LongConsumer progress, Consumer<String> fail) {
        final InputStream readStream = noThrow(() -> sourceStore.readStream(sourcePath).get().orElseThrow())
            .orElseThrow(() -> {
                final String error = "Copy failed: unable to get data of source: " + sourcePath;
                fail.accept(error);
                return new DataStoreException(error);
            });
        noThrow(() -> targetStore.write(targetPath, readStream, progress).get())
            .filter(result -> { noThrow(readStream::close); return true; }) // *always* close the stream or it leaks memory
            .filter(result -> Boolean.TRUE == result)
            .orElseThrow(() -> { // NOSONAR -- result value is not relevant
                noThrow(readStream::close); // close above wasn't called if empty (Optional doesn't have a finally)
                final String error = "Copy failed: unable to copy data to target: " + targetPath;
                fail.accept(error);
                return new DataStoreException(error);
            });
    }

    public static void copy(BlockingDataStore sourceStore, String sourcePath, BlockingDataStore targetStore, String targetPath) {
        final UUID copyId = copy(sourceStore.getStore(), sourcePath, targetStore.getStore(), targetPath, cpi -> {});
        synchronized (copiesInProgress) {
            while (copiesInProgress.contains(copyId)) {
                try {
                    copiesInProgress.wait();
                } catch (final InterruptedException e) { // NOSONAR -- don't want to add 'throws InterruptedException' to the whole call stack
                    throw new WrappedException(e);
                }
            }
        }
    }
    public static void stopCopy(UUID copyId) {
        synchronized (copiesInProgress) {
            copiesInProgress.remove(copyId);
            copiesInProgress.notifyAll();
        }
    }

    public static CompletableFuture<Boolean> copy(Path sourceFile, DataStore targetStore, String targetPath) {
        if(!Files.exists(sourceFile)) throw new IllegalArgumentException("File to copy from does not exist: " + sourceFile);
        if(Files.isDirectory(sourceFile)) {
            return CompletableFuture.supplyAsync(() -> {
                IOUtils.listDeep(sourceFile)
                    .forEach(path -> rethrow(() ->
                            copy(path, targetStore, IOUtils.concatPath(targetPath, sourceFile.relativize(path).toString().replace("\\","/"))).get(),
                        ex -> new IllegalStateException("Copy failed", ex))
                    );
                return true;
            }, Threads.getExecutor());
        }
        @Nullable InputStream ss = null;
        try {
            @SuppressWarnings("resource") // input stream cannot be auto closed via try() because of async writing
            final InputStream sourceStream = ss = new FileInputStream(sourceFile.toFile());
            return targetStore.write(targetPath, sourceStream)
                .whenComplete((b, ex) -> noThrow(sourceStream::close));
        } catch(final Exception e) {
            if(ss != null) noThrow(ss::close);
            throw new DataStoreException("Unable to copy file " + sourceFile + " to datastore: " + e.getMessage(), e);
        }
    }
    public static CompletableFuture<Void> copy(DataStore sourceStore, String sourcePath, Path targetFile) { return copy(sourceStore, sourcePath, targetFile, /*overwrite=*/false); }
    public static CompletableFuture<Void> copy(DataStore sourceStore, String sourcePath, Path targetFile, boolean overwrite) {
        if(sourcePath.endsWith("/")) {
            return CompletableFuture.supplyAsync(() -> {
                noThrow(() -> sourceStore.listNames(sourcePath, true).get()).orElse(Collections.emptyList())
                    .forEach(path -> {
                        final Path target = targetFile.resolve(path);
                        IOUtils.makeSureDirectoryExists(target.getParent());
                        noCheckedThrow(() -> { copy(sourceStore, path, targetFile.resolve(path), overwrite).get(); return true; });
                    });
                return null;
            }, Threads.getExecutor());
        }
        return sourceStore.readStream(sourcePath)
            .thenAcceptAsync(optSourceStream -> {
                if(optSourceStream.isEmpty()) throw new IllegalArgumentException("Cannot copy from " + sourceStore + "::" + sourcePath);
                try {
                    if(Files.exists(targetFile)) {
                        if(overwrite) IOUtils.del(targetFile);
                        else throw new IllegalArgumentException("Unable to copy as files exists and overwrite=false: " + targetFile);
                    }
                    rethrow(() -> Files.copy(optSourceStream.get(), targetFile), ex -> new IllegalStateException("Unable to copy to " + targetFile + ": " + ex.getMessage(), ex));
                } finally {
                    noThrow(() -> optSourceStream.get().close());
                }
            });
    }
}
