package net.microstar.common.util;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Singular;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.io.IOUtils;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;

/**
 * File and memory cache. Cache is ordered: most recently used cache is in memory
 * and less used cache is on the file system. Both types of cache can be enabled or
 * disabled via configuration.<p>
 *
 * Implemented as two caches stuck together: memory-cache + file-cache. Cache is
 * filled top to bottom: first the memory cache is filled to the configured limit,
 * then the file-cache will be filled to the configured limit. When an item is
 * requested, it will move to the top of the cache (MRU: most recently used).
 * When the memory cache becomes too large, the bottom of the memory cache will
 * be added to the top of the file cache. When the file cache becomes too large,
 * files at the bottom of the file cache will be removed from disk (and so removed
 * from the cache). This way, most active data will always be available in memory
 * while less used data will move to file cache.<p>
 *
 * Checking if the cache is dirty is not in scope for this implementation. Call
 * invalidate(key) when is determined that a value is no longer current.
 */
public class Cache {
    @Jacksonized @Builder(toBuilder = true) @ToString
    public static class Configuration {
        /** To create a memory cache, a size has to be provided as the default is none */
        @Default public final ByteSize maxMemSize     = ByteSize.ZERO;

        /** To limit a memory cache, a max count has to be provided, as the default is unlimited */
        @Default public final int      maxMemCount    = 0;

        /** To create a memory cache, a size has to be provided as the default is none */
        @Default public final ByteSize maxFilesSize   = ByteSize.ZERO;

        /** To limit a memory cache, a max count has to be provided, as the default is unlimited */
        @Default public final int      maxFilesCount  = 0;

        /** Default time until a cached item should be deleted (default is unlimited (actually 27 years)) */
        @Default public final Duration maxAge         = Duration.ofDays(9999);

        /** Default time until a cached item that is not read should be deleted (default is unlimited (actually 27 years)) */
        @Default public final Duration maxUnreadAge   = Duration.ofDays(9999);

        /** Resource items larger than this size will never be moved to memory. Defaults to 256KB. Ignored when no file-cache. */
        @Default public final ByteSize maxMemItemSize = ByteSize.ofKilobytes(256);

        /** Resource items larger than this fraction (0..1) of the maxMemSize will never be moved to memory. This to prevent a
          * big item from almost clearing out the complete memory cache. Defaults to 1 (as big as memory). Ignored when no file-cache.
          */
        @Default public final double   maxMemItemFraction = 1;

        /** When the requested key is not found in the cache, this supplier will be called for the resource data */
        @Nullable public final Function<String,byte[]> dataSupplier;

        /** The cache contains only byte[] data, so if other types are requested, they should be mapped */
        @Singular public final Map<Class<?>,Function<byte[],?>> toTypeMappers;

        /** The cache contains only byte[] data, so if other types are requested, they should be mapped */
        @Nullable public final Function<Object,byte[]> toBytesMapper;
    }
    private final Configuration cfg;
    private final @Nullable Path cacheDir;
    private final Map<String,CacheItem> cacheMap = new HashMap<>(); // always sync on this for any thread sensitive operations
    private final Map<String,Object> supplyingSyncs = new ConcurrentHashMap<>(); // prevent multiple concurrent supply calls for the same key
    private @Nullable CacheItem topOfCache; // Most recently used item: every touched item moves to the top (MRU)
    private volatile boolean closed;
    private final AtomicLong    currentMemSize    = new AtomicLong(); // optimization: prune() will remove overflow but will only be called when needed
    private final AtomicInteger currentMemCount   = new AtomicInteger();
    private final AtomicLong    currentFilesSize  = new AtomicLong();
    private final AtomicInteger currentFilesCount = new AtomicInteger();

    private class CacheItem {
        private static final Random random = new Random();
        private final String key;
        private final long creationTime = System.currentTimeMillis();
        private @Nullable CacheItem down; // null when at bottom
        private @Nullable CacheItem up; // null when at top
        private long lastTouched = creationTime;
        private int size;
        public @Nullable byte[] data; // One of [data, dataFile] is non-null
        public @Nullable Path file;   // Both null or both non-null is not allowed

        public CacheItem(String key, byte[] data) {
            this.key  = key;
            this.size = data.length;
            this.data = data;
            this.file = null;
            toTop();
        }

        // All methods here should be called synchronized on this

        public boolean isInMem() { return isInList() && data != null; }
        public boolean isInFile() { return isInList() && file != null; }
        public boolean isInList() { return up != null || down != null || topOfCache == this; }

        public CacheItem touched() {
            lastTouched = System.currentTimeMillis();
            return this;
        }
        public CacheItem clearData() { // This may be a more expensive call, so call outside any synchronized
            data = null;
            if(file != null) IOUtils.del(file);
            file = null;
            size = 0;
            return this;
        }
        public CacheItem delete() {
            if(isInList()) subtractFromCurrent();
            if(up   != null) up.down = down;
            if(down != null) down.up = up;
            if(topOfCache == this) topOfCache = down;
            up = null;
            down = null;
            // don't call clearData here
            return this;
        }
        public CacheItem toTop() {
            if(up == null && topOfCache == this) return this;
            delete();
            up = null;
            down = topOfCache;
            if(down != null) down.up = this;
            topOfCache = this;
            addToCurrent();
            return this;
        }
        public CacheItem dataToFile() {
            if (file == null && data != null && canBeInFile(this) && cacheDir != null) {
                subtractFromCurrent();
                file = createCacheFile(cacheDir);
                noCheckedThrow(() -> Files.write(file, data));
                data = null;
                addToCurrent();
            }
            return this;
        }
        public CacheItem dataToMem() {
            if (data == null && file != null && canBeInMem(this)) {
                subtractFromCurrent();
                data = noCheckedThrow(() -> Files.readAllBytes(file));
                IOUtils.del(file);
                file = null;
                addToCurrent();
            }
            return this;
        }
        public InputStream getInputStream() {
            if (data != null) return new ByteArrayInputStream(data);
            if (file != null) return noCheckedThrow(() -> new FileInputStream(file.toFile()));
            // shouldn't get here
            throw new IllegalStateException("Internal cache error: item without data!");
        }
        private static Path createCacheFile(Path cacheDir) {
            return cacheDir.resolve("cache_" + System.currentTimeMillis() + random.nextInt(1000));
        }
        private void addToCurrent() {
            if(isInMem()) { currentMemSize.addAndGet(size); currentMemCount.incrementAndGet(); }
            if(isInFile()) { currentFilesSize.addAndGet(size); currentFilesCount.incrementAndGet(); }
        }
        private void subtractFromCurrent() {
            if(isInMem()) { currentMemSize.addAndGet(-size); currentMemCount.decrementAndGet(); }
            if(isInFile()) { currentFilesSize.addAndGet(-size); currentFilesCount.decrementAndGet(); }
        }
        public boolean canBeInMem(CacheItem item) {
            return (item.size < (int)(cfg.maxMemSize.getBytesInt() * cfg.maxMemItemFraction) || !canBeInFile(item))
                && (item.size < cfg.maxMemItemSize.getBytesInt() || !canBeInFile(item))
                && cfg.maxMemCount > 0;
        }
        private boolean canBeInFile(CacheItem item) {
            return item.size < cfg.maxFilesSize.getBytesInt() && cacheDir != null;
        }
    }

    public static Cache using(Configuration cfg) { return new Cache(cfg); }
    public static Cache onlyMemory(ByteSize size) { return new Cache(Configuration.builder().maxMemSize(size).build()); }
    public static Cache onlyFiles(ByteSize size) { return new Cache(Configuration.builder().maxFilesSize(size).build()); }

    public Cache(Configuration cfgIn) {
        cfg = validateAndFix(cfgIn);

        if(cfg.maxFilesCount > 0 && cfg.maxFilesSize.isGreaterThan(0)) {
            cacheDir = IOUtils.createTempDir("cache");
            Runtime.getRuntime().addShutdownHook(new ThreadBuilder().name("DeleteCacheDir").toRun(() -> IOUtils.delTree(cacheDir)).build());
        } else {
            cacheDir = null;
        }
    }
    public void close() {
        synchronized (cacheMap) {
            if(closed) return;
            closed = true;
        }
        invalidateAll();
        if(cacheDir != null) IOUtils.del(cacheDir);
    }

    public int      count()      { return currentMemCount.get() + currentFilesCount.get(); }
    public int      memCount()   { return currentMemCount.get(); }
    public int      filesCount() { return currentFilesCount.get(); }
    public ByteSize size()       { return ByteSize.ofBytes(currentMemSize.get() + currentFilesSize.get()); }
    public ByteSize memSize()    { return ByteSize.ofBytes(currentMemSize.get()); }
    public ByteSize filesSize()  { return ByteSize.ofBytes(currentFilesSize.get()); }

    public Cache invalidateAll() {
        synchronized (cacheMap) {
            cacheMap.clear();
            topOfCache = null;
            resetCurrent();
        }
        if(cacheDir != null) {
            IOUtils.delTree(cacheDir);
            IOUtils.makeSureDirectoryExists(cacheDir);
        }
        return this;
    }
    public Cache invalidate(String key) {
        checkClosed();
        final @Nullable CacheItem item;
        synchronized (cacheMap) {
            item = cacheMap.remove(key);
            if(item != null) item.delete();
        }
        if(item != null) item.clearData(); // this may be more expensive, so outside synchronized
        return this;
    }

    public Cache put(String key, byte[] data) {
        checkClosed();
        synchronized (cacheMap) {
            invalidate(key);
            final CacheItem newItem = new CacheItem(key, data);

            // Check beforehand so the whole cache is not wiped: is the data too large to fit in cache? Then ignore put
            if(   cfg.maxMemItemSize.isSmallerThan(newItem.size) && cfg.maxFilesSize.isSmallerThan(newItem.size)) {
                return this;
            }

            cacheMap.put(key, newItem);
            if(   cfg.maxMemItemSize.isSmallerThan(newItem.size)
              || (cfg.maxMemItemFraction != 0d && (cfg.maxMemItemFraction * cfg.maxMemSize.getBytesLongClipped()) < newItem.size)) {
                newItem.dataToFile(); // this may not do anything if no files. maxMemItem* should be ignored when no files, so ok.
            }
        }
        prune();
        return this;
    }
    public Cache put(String key, String data) {
        return put(key, data.getBytes(StandardCharsets.UTF_8));
    }
    public Cache put(String key, Object mappableData) {
        return put(key, mapToBytes(mappableData));
    }

    public InputStream  get(String key) { return get(key, cfg.dataSupplier); }
    public InputStream  get(String key, @Nullable Function<String,byte[]> supplier) {
        checkClosed();
        return getItem(key).map(item -> {
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized(item) { // NOSONAR -- item is param but still local
                // MRU -> Move just read item to top of cache (which is memory)
                item.dataToMem();
                synchronized(cacheMap) { item.toTop(); }
                // Now if this item was moved from files to memory,
                // memory usage may be too high and items may need to
                // be moved from memory to files. Ideally this would
                // happen in a different thread so this get() can
                // return quickly, however if this happens many times
                // before the other thread moves items to files, memory
                // usage will become much too high. Therefore, do the
                // pruning/moving immediately. (prune will return
                // immediately if no limits were crossed)
                prune();
                return item.getInputStream();
            }
        }).orElseGet(() -> supplyAndStoreValueFor(key, supplier));
    }
    public <T> T        get(String key, Class<T> type) { return get(key, type, cfg.dataSupplier); }
    public <T> T        get(String key, Class<T> type, @Nullable Function<String,byte[]> supplier) {
        return mapToType(getBytes(key, supplier), type);
    }
    public byte[]  getBytes(String key) { return getBytes(key, cfg.dataSupplier); }
    public byte[]  getBytes(String key, @Nullable Function<String,byte[]> supplier) {
        final InputStream stream = get(key, supplier); // allow this to throw
        return noCheckedThrow(() -> { try(final InputStream streamToClose = stream) { return streamToClose.readAllBytes(); } });
    }
    public String getString(String key) { return getString(key, cfg.dataSupplier); }
    public String getString(String key, @Nullable Function<String,byte[]> supplier) {
        return new String(getBytes(key, supplier), StandardCharsets.UTF_8);
    }

    public CompletableFuture<InputStream> getAsFuture(String key) { return getAsFuture(key, cfg.dataSupplier); }
    public CompletableFuture<InputStream> getAsFuture(String key, @Nullable Function<String,byte[]> supplier) {
        checkClosed();
        return getItem(key).map(item -> {
            //noinspection SynchronizationOnLocalVariableOrMethodParameter
            synchronized(item) { // NOSONAR -- item is param but still local
                item.dataToMem();
                synchronized(cacheMap) { item.toTop(); }
                prune();
                return CompletableFuture.completedFuture(item.getInputStream());
            }
        }).orElseGet(() -> CompletableFuture.supplyAsync(() -> supplyAndStoreValueFor(key, supplier), Threads.getExecutor()));

    }
    public <T> CompletableFuture<T>       getAsFuture(String key, Class<T> type) { return getAsFuture(key, type, cfg.dataSupplier); }
    public <T> CompletableFuture<T>       getAsFuture(String key, Class<T> type, @Nullable Function<String,byte[]> supplier) {
        return getBytesAsFuture(key, supplier)
            .thenApply(bytes -> mapToType(bytes, type));
    }
    public CompletableFuture<byte[]> getBytesAsFuture(String key) { return getBytesAsFuture(key, cfg.dataSupplier); }
    public CompletableFuture<byte[]> getBytesAsFuture(String key, @Nullable Function<String,byte[]> supplier) {
        checkClosed();
        final AtomicBoolean isFromFile = new AtomicBoolean();
        final CompletableFuture<InputStream> streamFuture = getItem(key).map(item -> {
            final boolean isInMem;
            synchronized(item) { // NOSONAR -- item is param but still local
                isInMem = item.isInMem();
            }
            if(isInMem) {
                synchronized(item) { // NOSONAR -- item is param but still local
                    synchronized(cacheMap) { item.toTop(); }
                    isFromFile.set(false);
                    prune();
                    return CompletableFuture.completedFuture(item.getInputStream());
                }
            }
            return CompletableFuture.supplyAsync(() -> { // dataToMem involves IO so the future should become async
                synchronized(item) { // NOSONAR -- item is param but still local
                    item.dataToMem();
                    synchronized(cacheMap) { item.toTop(); }
                    isFromFile.set(item.isInFile()); // may be false when no memory configured in cache
                    prune();
                    return item.getInputStream();
                }
            }, Threads.getExecutor());
        }).orElseGet(() -> CompletableFuture.supplyAsync(() -> {
            final InputStream stream = supplyAndStoreValueFor(key, supplier);
            isFromFile.set(isInFile(key));
            return stream;
        }, Threads.getExecutor()));

        final Function<InputStream,byte[]> streamToBytes = stream ->
            noCheckedThrow(() -> { try(final InputStream streamToClose = stream) { return streamToClose.readAllBytes(); } });

        return isFromFile.get()
            ? streamFuture.thenApplyAsync(streamToBytes)
            : streamFuture.thenApply(streamToBytes);
    }
    public CompletableFuture<String> getStringAsFuture(String key) { return getStringAsFuture(key, cfg.dataSupplier); }
    public CompletableFuture<String> getStringAsFuture(String key, @Nullable Function<String,byte[]> supplier) {
        return getBytesAsFuture(key, supplier)
            .thenApply(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }

    /** Returns true if the given key is currently cached. A get on this key may still
      * succeed, but only after loading the data into the cache (e.g. by the supplier
      * or a put() call).
      */
    public boolean containsKey(String key) {
        synchronized (cacheMap) { return cacheMap.containsKey(key); }
    }

    public boolean isInFile(String key) { return getItem(key).map(CacheItem::isInFile).orElse(false); }
    public boolean isInMem(String key) { return getItem(key).map(CacheItem::isInMem).orElse(false); }

    private byte[] mapToBytes(Object obj) {
        if(cfg.toBytesMapper == null) throw new IllegalStateException("Cannot map to bytes because no toBytesMapper was configured");
        return cfg.toBytesMapper.apply(obj);
    }
    private  <T> T mapToType(byte[] data, Class<T> type) {
        final @Nullable Function<byte[],?> typeMapper = cfg.toTypeMappers.get(type);
        if(typeMapper == null) throw new IllegalArgumentException("Cannot map to type " + type + " because there is no toTypeMapper for it configured");
        //noinspection unchecked
        return (T)typeMapper.apply(data);
    }

    private Optional<CacheItem> getItem(String key) {
        synchronized (cacheMap) {
            return Optional.ofNullable(cacheMap.get(key)).filter(item -> { item.touched(); return true; });
        }
    }

    private InputStream supplyAndStoreValueFor(String key, @Nullable Function<String,byte[]> supplier) {
        synchronized(supplyingSyncs.computeIfAbsent(key, k -> new Object())) {
            try {
                // value may have been added while waiting on synchronized
                synchronized (cacheMap) {
                    if (cacheMap.containsKey(key)) return get(key);
                }
                if(supplier == null) throw new IllegalArgumentException("Key '"+key+"' not in cache and no supplier provided.");
                @Nullable final byte[] data = supplier.apply(key);
                if (data == null) throw new IllegalStateException("Null result from supplier for key: " + key);
                put(key, data);
                return get(key);
            } finally {
                supplyingSyncs.remove(key);
            }
        }
    }

    @SuppressWarnings("ConstantValue")
    private static Configuration validateAndFix(Configuration cfgIn) { // NOSONAR -- lots of ifs, but easy to understand
        Configuration cfg = cfgIn;
        final boolean hasMaxMemSize = cfg.maxMemSize.isGreaterThan(0);
        final boolean hasMaxMemCount = cfg.maxMemCount > 0;
        final boolean hasMaxFilesSize = cfg.maxFilesSize.isGreaterThan(0);
        final boolean hasMaxFilesCount = cfg.maxFilesCount > 0;

        final boolean hasFiles = hasMaxFilesSize || hasMaxFilesCount;
        final boolean hasMem   = hasMaxMemSize   || hasMaxMemCount;
        if(!hasFiles && !hasMem) throw new IllegalArgumentException("Cache size 0 is not allowed -- set a maxFile or maxMem value");

        if(hasMem) {
            if (!hasMaxMemSize &&  hasMaxMemCount) cfg = cfg.toBuilder().maxMemSize(ByteSize.ofGigabytes(1)).build();
            if ( hasMaxMemSize && !hasMaxMemCount) cfg = cfg.toBuilder().maxMemCount(Integer.MAX_VALUE).build();
        }
        if(hasFiles) {
            if(!hasMaxFilesSize &&  hasMaxFilesCount) cfg = cfg.toBuilder().maxFilesSize(ByteSize.ofGigabytes(1)).build();
            if( hasMaxFilesSize && !hasMaxFilesCount) cfg = cfg.toBuilder().maxFilesCount(Integer.MAX_VALUE).build();
        }
        if(cfg.maxMemItemSize.isZero()) throw new IllegalArgumentException("Max cache item size of 0 is not allowed");
        if(cfg.maxMemItemFraction <= 0 || cfg.maxMemItemFraction > 1) throw new IllegalArgumentException("Max memory item fraction should be > 0 and <= 1 but is " + cfg.maxMemItemFraction);
        return cfg;
    }
    private void prune() { // NOSONAR -- a bit too complex but due to local vars not possible to split up
        removeAgedItems();
        if(closed || !isCacheOverflow()) return;

        synchronized (cacheMap) {
            if(closed || !isCacheOverflow()) return;
            long memSize = 0;
            int  memCount = 0;
            long filesSize = 0;
            int  filesCount = 0;

            for(@Nullable CacheItem item = topOfCache; item != null; item = item.down) {
                synchronized(item) {
                    if(item.isInMem()) {
                        memSize += item.size;
                        memCount++;
                        if(cfg.maxMemSize.isSmallerThan(memSize) || memCount > cfg.maxMemCount) {
                            item.dataToFile();
                            final int itemSize = item.size; // remember this, as invalidate() below will clear it.
                            if(item.isInMem()) { // dataToFile may do nothing, e.g. if no file cache allowed
                                invalidate(item.key);
                            }
                            memSize -= itemSize;
                            memCount--;
                        }
                    } // no else (mem item may have been moved to file)
                    if(item.isInFile()) {
                        filesSize += item.size;
                        filesCount++;
                        if(cfg.maxFilesSize.isSmallerThan(filesSize) || filesCount > cfg.maxFilesCount) {
                            filesSize -= item.size;
                            filesCount--;
                            invalidate(item.key);
                        }
                    }
                }
            }
            currentMemSize.set(memSize);
            currentMemCount.set(memCount);
            currentFilesSize.set(filesSize);
            currentFilesCount.set(filesCount);
        }
    }
    private void removeAgedItems() {
        synchronized(cacheMap) { // use primitives instead of Durations as to not create objects
            final long now = System.currentTimeMillis();
            final long maxAge = cfg.maxAge.toMillis();
            final long maxUnreadAge = cfg.maxUnreadAge.toMillis();

            for(@Nullable CacheItem item = topOfCache; item != null; item = item.down) {
                final long age = now - item.creationTime;
                final long unreadAge = now - item.lastTouched;

                if(age > maxAge || unreadAge > maxUnreadAge) invalidate(item.key);
            }
        }
    }
    private boolean isCacheOverflow() {
        return cfg.maxMemSize.isSmallerThan(currentMemSize.get())     || (currentMemCount.get()   > cfg.maxMemCount)
            || cfg.maxFilesSize.isSmallerThan(currentFilesSize.get()) || (currentFilesCount.get() > cfg.maxFilesCount);
    }
    private void resetCurrent() {
        currentMemSize.set(0);
        currentMemCount.set(0);
        currentFilesSize.set(0);
        currentFilesCount.set(0);
    }
    private void checkClosed() {
        if(closed) throw new IllegalStateException("This cache is closed");
    }

    String toStackString() {
        final List<String> memList = new ArrayList<>();
        final List<String> filesList = new ArrayList<>();
        for(@Nullable CacheItem item = topOfCache; item != null; item = item.down) {
            (item.isInMem() ? memList : filesList).add(item.key);
        }
        return "[" + CollectionUtils.reverse(filesList.stream()).collect(Collectors.joining(",")) + "]"
            + "[" + CollectionUtils.reverse(memList.stream()).collect(Collectors.joining(",")) + "]";
    }
}