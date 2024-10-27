package net.microstar.common.datastore;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.SneakyThrows;
import net.microstar.common.conversions.ObjectMapping;
import net.microstar.common.util.Cache;
import net.microstar.common.util.Threads;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ExceptionUtils.rethrow;

/**
 * Layer on top of an actual data store that takes care of caching results in
 * memory and/or on the filesystem, depending on configuration:<pre>
 *
 * microstar.dataStores:
 *   storeName:                  # name of the store can be anything
 *     type: database            # cache will work with any datastore type
 *     url: postgresql://some.db.server/dbname[?user=other&password=secret]
 *     cache:                    # leave out if no cache is required
 *       maxMemSize:    10MB     # optional maximum byte size of all data cached in memory
 *       maxMemCount:   1000     # optional maximum number of files cached in memory
 *       maxFilesSize:  100MB    # optional maximum byte size of all data cached to disk
 *       maxFilesCount: 1000     # optional maximum number of files cached to disk
 *                 # while all above max* keys are optional, at least one must be provided
 *       maxAge:        10h      # optional shelf life, e.g. 1h20m or 1d12h
 *       maxUnreadAge:  5h       # optional shelf life that eventually resets when not read
 *       maxMemItemSize: 200KB   # optional maximum size of item in memory (default is 256KB) (ignored when no file cache)
 *       maxMemItemFraction: 0.5 # optional maximum fraction of memory size an item can be (ignored when no file cache)
 *       readOnlyOnStoreFail: false # optional flag to enable (default) or disable read-only mode
 *                                  # when store gives (connection or other) errors.
 * </pre>
 *
 * If both memory and file-cache are configured, the memory-cache
 * will be filled first, followed by file-cache. When memory-cache
 * is full (configured limit reached), the items will overflow to
 * the file-cache or, if no such cache is configured, be removed
 * from cache. So memory-cache and file-cache are joined as a
 * single cache.<p>
 *
 * Used cache elements will be moved to the first spot (MRU).
 * This only has effect when both memCache and fileCache are
 * enabled as this will move cacheItems from file to memory
 * when used.<p>
 *
 * Another goal of this cache is to add resilience to a failing
 * store, like a database that has intermittent connection
 * issues. It does this by reusing old listing data when not
 * able to acquire new data. This behaviour is on by default
 * can be turned off.
 */
public class CachedDataStore extends AbstractDataStore {
    private final DataStore source;
    private final Cache cache;
    private final @Nullable Cache listingFallbackCache; // listing (so no resource data) cache when backing store is failing

    public CachedDataStore(DataStore source, String storeName, Map<String,?> cacheSettings) {
        this.source = source;
        try {
            final Cache.Configuration userCfg = ObjectMapping.get().readValue(ObjectMapping.get().writeValueAsString(cacheSettings), Cache.Configuration.class);
            final Cache.Configuration cacheCfg = userCfg.toBuilder()
                .build();
            this.cache = new Cache(cacheCfg);
            final boolean useNonDataCache = Optional.ofNullable(ObjectMapping.get().readValue(""+cacheSettings.get("readOnlyOnStoreFail"), Boolean.class)).orElse(true);
            this.listingFallbackCache = useNonDataCache ? new Cache(Cache.Configuration.builder().maxMemCount(1000).build()) : null;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid cache settings for store '" + storeName + "':" + e.getMessage(), e);
        }
    }
    public boolean isCached(String path) {
        return cache.containsKey(path);
    }

    private static <T> T fromString(String s, TypeReference<T> type) { return noCheckedThrow(() -> ObjectMapping.get().readValue(s, type)); }
    private static String toString(Object object) { return noCheckedThrow(() -> ObjectMapping.get().writeValueAsString(object)); }
    @SneakyThrows // throws the exception that would otherwise be thrown by the original caller
    private <T> T getNonDataCached(String cacheKey, @Nullable T value, @Nullable Throwable ex, TypeReference<T> valueType) {
        final String s = listingFallbackCache == null ? "" : listingFallbackCache.getString(cacheKey, k->new byte[0]);
        if(value != null || s.isEmpty()) throw ex == null ? new IllegalStateException("non-data cache miss: " + cacheKey) : ex;
        return fromString(s, valueType);
    }
    @SneakyThrows // throws the exception that would otherwise be thrown by the caller
    private @Nullable <T> T viaNonDataCache(Supplier<String> cacheKeySupplier, @Nullable T value, @Nullable Throwable ex, TypeReference<T> valueType) {
        if(listingFallbackCache == null) {
            if(ex != null) throw ex;
            return value;
        }
        final String cacheKey = cacheKeySupplier.get();
        if(ex == null && value != null) {
            listingFallbackCache.put(cacheKey, toString(value));
            return value;
        }
        return getNonDataCached(cacheKey, value, ex, valueType);
    }


    @Override public Runnable getCloseRunner() {
        return () -> {
            source.getCloseRunner().run();
            Threads.execute(cache::close);
        };
    }

    @Override public CompletableFuture<List<Item>> list(String path, boolean recursive) {
        return source.list(path, recursive).handle((list,ex) ->
            viaNonDataCache(() -> String.join(";", "list", path, recursive ? "@rcr" : "@flat"), list, ex, new TypeReference<>() {}));
    }

    @Override public CompletableFuture<Optional<Instant>> getLastModified(String path) {
        return source.getLastModified(path).handle((lastModified,ex) ->
            viaNonDataCache(() -> String.join(";", "lastModified", path), lastModified, ex, new TypeReference<>() {}));
    } // uncached when connected

    @Override public CompletableFuture<Boolean> exists(String path) {
        return source.exists(path).handle((exists,ex) ->
            viaNonDataCache(() -> String.join(";", "exists", path), exists, ex, new TypeReference<>() {}));
    } // uncached when connected

    @Override public CompletableFuture<Boolean> remove(String path) {
        return source.remove(path)
            .thenApply(ok -> invalidate(ok, path));
    } // uncached

    @Override public CompletableFuture<Boolean> move(String fromPath, String toPath) {
        return source.move(fromPath, toPath)
            .thenApply(ok -> invalidate(ok, fromPath));
    } // uncached

    @Override public CompletableFuture<Optional<byte[]>> read(String path) {
        final Function<String,byte[]> supplier = key -> rethrow(() ->
            source.read(key).get().orElseThrow(), ex -> new IllegalStateException("Failed to read data for path " + path, ex));
        return cache.getBytesAsFuture(path, supplier).thenApply(Optional::ofNullable);
    }

    @Override public CompletableFuture<Optional<InputStream>> readStream(String path) {
        final Function<String,byte[]> supplier = key -> rethrow(() ->
            source.read(key).get().orElseThrow(), ex -> new IllegalStateException("Failed to read data for path " + path, ex));
        return CompletableFuture.completedFuture(noThrow(() -> cache.get(path, supplier)));
    }

    @Override public CompletableFuture<Boolean> write(String path, byte[] data, Instant time) {
        return source.write(path, data, time)
            .thenApply(ok -> invalidate(ok, path));
    }
    @Override public CompletableFuture<Boolean> write(String path, InputStream stream, Instant time, LongConsumer progress) {
        return source.write(path, stream, time, progress)
            .thenApply(ok -> invalidate(ok, path));
    }
    @Override public CompletableFuture<Boolean> touch(String path, Instant time) {
        return source.touch(path, time)
            .thenApply(ok -> invalidate(ok, path));
    }

    @Override public Runnable onChange(String path, Consumer<List<String>> changeHandler) {
        return source.onChange(path, changeHandler);
    }
    @Override public Runnable onClose(Consumer<DataStore> closingStoreHandler) {
        return source.onClose(closingStoreHandler);
    }

    private boolean invalidate(boolean ok, String path) {
        cache.invalidate(path);
        return ok;
    }
}
