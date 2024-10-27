package net.microstar.common.datastore;

import lombok.EqualsAndHashCode;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.Threads;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.stream.Collector;
import java.util.stream.Collectors;

import static java.util.concurrent.CompletableFuture.completedFuture;

/**
 * In-memory data store. Data will be stored in a map and be gone when the vm stops.
 * Typically used for testing. Can be configured with a read and/or write delay.
 */
public class MemoryDataStore extends AbstractDataStore {
    private final Map<String, MapItem> storeMap = new ConcurrentHashMap<>();
    private final Duration readDelay;
    private final Duration writeDelay;

    public MemoryDataStore() {
        this(Duration.ZERO, Duration.ZERO);
    }

    public MemoryDataStore(Duration readDelay, Duration writeDelay) {
        this.readDelay = readDelay;
        this.writeDelay = writeDelay;
    }

    @EqualsAndHashCode(callSuper = true, cacheStrategy = EqualsAndHashCode.CacheStrategy.LAZY)
    private static final class MapItem extends Item {
        public final @Nullable byte[] data;

        public MapItem(String path, Instant time, int count, long size, @Nullable byte[] data) {
            super(path, time, count, size);
            this.data = data;
        }

        public MapItem copyForPath(String newPath) {
            return new MapItem(newPath, time, count, size, data);
        }

        public MapItem copyForTime(Instant time) {
            return new MapItem(path, time, count, size, data);
        }

        public MapItem copyForLeaf() {
            final boolean isDir = path.endsWith("/");
            final String leaf = Arrays.stream(path.replaceAll("/$", "").split("/")).reduce((l, i) -> i).map(a -> a + (isDir ? "/" : "")).orElse("");
            return new MapItem(leaf, time, count, size, data);
        }
    }

    @Override
    public Runnable getCloseRunner() {
        return () -> { storeMap.clear(); closed(); };
    }

    @Override
    public CompletableFuture<List<Item>> list(String path, boolean recursive) {
        final String filterPath = normalizePath(path + "/"); // NOSONAR -- slash
        return completedFuture(storeMap.values().stream()
            .filter(item -> item.path.startsWith(filterPath))
            .map(item -> item.copyForPath(item.path.substring(filterPath.length())))
            .filter(item -> !item.path.isEmpty())
            .map(item -> recursive ? item : item.copyForPath(item.path.split("((?<=/))", 2)[0]))
            .map(Item.class::cast)
            .collect(joinItemsWithSamePath()).stream()
            .sorted(ITEM_COMPARATOR)
            .toList()
        );
    }

    @Override
    public CompletableFuture<Optional<Instant>> getLastModified(String path) {
        return supplyAsync(
            () -> Optional.ofNullable(storeMap.get(normalizePath(path))).map(item -> item.time)
        );
    }

    @Override
    public CompletableFuture<Boolean> exists(String path) {
        // Path may be a file (for which there may be a key) or a directory
        // for which there may be files that have the directory as prefix.
        final String normalizedPath = normalizePath(path);
        final String withSlash = normalizedPath.replaceFirst("/+$","") + "/";
        return CompletableFuture.completedFuture(
               storeMap.containsKey(normalizedPath)
            || storeMap.keySet().stream().anyMatch(key -> key.startsWith(withSlash))
        );
    }

    @Override
    public CompletableFuture<Boolean> remove(String path) {
        final String normPath = normalizePath(path);
        if (isDir(normPath)) {
            return list(path, true)
                .thenAccept(items -> items.forEach(item -> {
                    final String pathToRemove = IOUtils.concatPath(normPath, item.path);
                    storeMap.remove(pathToRemove);
                    changed(pathToRemove);
                }))
                .thenApply(v -> true);
        } else {
            return supplyAsync(() -> {
                storeMap.remove(normPath);
                changed(normPath);
                return true;
            });
        }
    }

    @Override
    public CompletableFuture<Boolean> move(String fromPath0, String toPath0) {
        final String fromPath = normalizePath(fromPath0);
        final boolean isFromDir = isDir(fromPath);
        final String toPath = normalizePath(toPath0 + (isFromDir ? "/" : ""));
        return remove(toPath).thenCompose(v ->
            (isFromDir
                ? list(fromPath, true) // returned item paths are relative to fromPath
                : completedFuture(
                Optional.ofNullable(storeMap.get(fromPath))
                    .map(MapItem::copyForLeaf)
                    .map(Collections::singletonList)
                    .orElseGet(Collections::emptyList))
            ).thenCompose(list -> {
                list.forEach(oldItem -> {
                    final String oldPathAbs = isFromDir ? IOUtils.concatPath(fromPath, oldItem.path) : fromPath;
                    final String newPathAbs = isFromDir ? IOUtils.concatPath(toPath, oldItem.path) : toPath;
                    final MapItem newItem = ((MapItem) oldItem).copyForPath(newPathAbs);
                    storeMap.put(newItem.path, newItem);
                    storeMap.remove(oldPathAbs);
                    changed(oldPathAbs);
                    changed(newPathAbs);
                });
                return completedFuture(true);
            }));
    }

    @Override
    public CompletableFuture<Optional<byte[]>> read(String path) {
        return supplyAsync(
            () -> Optional.ofNullable(storeMap.get(normalizePath(path))).map(item -> item.data),
            readDelay
        );
    }

    @Override
    public CompletableFuture<Optional<InputStream>> readStream(String path) {
        return completedFuture(Optional.ofNullable(storeMap.get(normalizePath(path)))
            .filter(item -> item.data != null)
            .map(item -> new ByteArrayInputStream(item.data)));
    }

    @Override
    public CompletableFuture<Boolean> write(String path, byte[] data, Instant time) {
        return supplyAsync(() -> {
            final String targetPath = normalizePath(path);
            storeMap.put(targetPath, new MapItem(targetPath, time, 1, data.length, data));
            changed(targetPath);
            return true;
        }, writeDelay);
    }

    @Override
    public CompletableFuture<Boolean> write(String path, InputStream source, Instant time, LongConsumer progress) {
        return supplyAsync(() -> {
            final String targetPath = normalizePath(path);
            try (final InputStream in = source; // copied so it will be auto-closed
                 final ByteArrayOutputStream target = new ByteArrayOutputStream()) {
                progress.accept(IOUtils.copy(in, target, progress));
                storeMap.put(targetPath, new MapItem(targetPath, time, 1, target.size(), target.toByteArray()));
            } catch (final IOException e) {
                return false;
            }
            changed(targetPath);
            return true;
        }, writeDelay);
    }

    @Override
    public CompletableFuture<Boolean> touch(String path, Instant time) {
        final String targetPath = normalizePath(path);
        return exists(targetPath).thenComposeAsync(pathExists -> {
            if (pathExists) { // NOSONAR -- boolean
                storeMap.put(targetPath, storeMap.get(targetPath).copyForTime(time));
                return CompletableFuture.completedFuture(true);
            } else {
                return write(targetPath, "", time);
            }
        }).thenCompose(b -> {
            changed(targetPath);
            return CompletableFuture.completedFuture(true);
        });
    }

    private Collector<Item, Object, List<Item>> joinItemsWithSamePath() {
        return Collectors.collectingAndThen(
            Collectors.toMap(item -> item.path, Function.identity(), Item::join),
            map -> new ArrayList<>(map.values())
        );
    }

    protected static <U> CompletableFuture<U> supplyAsync(Supplier<U> supplier, Duration startupDelay) {
        return CompletableFuture.supplyAsync(supplier, CompletableFuture.delayedExecutor(startupDelay.toMillis(), TimeUnit.MILLISECONDS, Threads.getExecutor()));
    }
}
