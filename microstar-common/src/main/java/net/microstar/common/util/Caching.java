package net.microstar.common.util;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import net.microstar.common.exceptions.WrappedException;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

/** Convenience class for caching values.
  * Values are cached for an id. The id can be generated from the location of the call, preventing
  * the need for an explicit id. Less code makes for improved readability. However, while getting the
  * id from the code location is pretty fast nowadays, it requires a few nanoseconds more than just
  * providing the id as a string. So which one to use depends on the use case.
  */
public final class Caching {
    private static final Map<String, Cache<?,?>> caches = new ConcurrentHashMap<>();
    private Caching() {}

    /** Cache identified by the caller code location for an unbounded cached */
    public static <K,V> V cache(Supplier<V> valueGenerator) {
        return cache(Reflection.getCallerId(Caching.class), Integer.MAX_VALUE, Integer.MAX_VALUE, "", valueGenerator);
    }
    /** Cache identified by the caller code location for an unbounded cached */
    public static <K,V> V cache(K key, Supplier<V> valueGenerator) {
        return cache(Reflection.getCallerId(Caching.class), Integer.MAX_VALUE, Integer.MAX_VALUE, key, valueGenerator);
    }
    /** Cache identified by the caller code location for a size limited cached */
    public static <K,V> V cache(int maxSize, K key, Supplier<V> valueGenerator) {
        return cache(Reflection.getCallerId(Caching.class), maxSize, Integer.MAX_VALUE, key, valueGenerator);
    }
    /** Cache identified by the caller code location for a time and size limited cached */
    public static <K,V> V cache(int maxSize, int maxAgeMs, K key, Supplier<V> valueGenerator) {
        return cache(Reflection.getCallerId(Caching.class), maxSize, maxAgeMs, key, valueGenerator);
    }

    /** Cache identified by the provided id for an unbounded cached */
    public static <K,V> V cache(String id, K key, Supplier<V> valueGenerator) {
        return cache(id, Integer.MAX_VALUE, Integer.MAX_VALUE, key, valueGenerator);
    }
    /** Cache identified by the provided id for a size limited cached */
    public static <K,V> V cache(String id, int maxSize, K key, Supplier<V> valueGenerator) {
        return cache(id, maxSize, Integer.MAX_VALUE, key, valueGenerator);
    }
    /** Cache identified by the provided id for a time and size limited cached */
    public static <K,V> V cache(String id, int maxSize, int maxAgeMs, K key, Supplier<V> valueGenerator) {
        @SuppressWarnings("unchecked")
        final Cache<K,V> cache = (Cache<K,V>)caches.computeIfAbsent(id, cacheId -> CacheBuilder
            .newBuilder()
            .maximumSize(maxSize)
            .expireAfterWrite(Duration.ofMillis(maxAgeMs))
            .build()
        );
        try {
            return cache.get(key, valueGenerator::get);
        } catch(final ExecutionException generatorFailed) {
            throw new WrappedException(generatorFailed);
        }
    }

    public static void clearCache(String id) { clearCache(id, null); }
    public static <K,V> Optional<V> clearCache(String id, @Nullable K key) {
        final Optional<Cache<?,?>> idCache = Optional.ofNullable(caches.get(id));
        caches.remove(id);
        //noinspection unchecked
        return key == null ? Optional.empty() : idCache.map(c -> (V)c.getIfPresent(key));
    }
}
