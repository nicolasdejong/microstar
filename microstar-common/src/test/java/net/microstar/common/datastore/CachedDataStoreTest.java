package net.microstar.common.datastore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings({"squid:S5778", "NotNullFieldNotInitialized"})
class CachedDataStoreTest {
    private MemoryDataStore source;
    private CachedDataStore store;
    private FailingForTestDataStore failingStore;

    private CachedDataStore create(Map<String,?> cacheCfg) throws ExecutionException, InterruptedException {
        source = new MemoryDataStore();
        source.write("a", "aValue").get();
        failingStore = new FailingForTestDataStore(source).setFailing(false);
        store = new CachedDataStore(failingStore, "cachedStore", cacheCfg);
        return store;
    }
    @BeforeEach void init() throws ExecutionException, InterruptedException {
        create(Map.of("maxMemSize", "100KB"));
    }
    @AfterEach void cleanup() {
        store.getCloseRunner().run();
        source.getCloseRunner().run();
    }

    @Test void shouldHandleCacheSettings() {
        assertDoesNotThrow(() -> create(Map.of("maxMemSize", "100KB")));
    }
    @Test void shouldThrowOnInvalidCacheSettings() {
        assertThrows(IllegalArgumentException.class, () -> create(Map.of("maxMemItemFraction", "2")));
    }
    @Test void shouldThrowOnMissingMandatoryCacheSettings() {
        assertThrows(IllegalArgumentException.class, () -> create(Collections.emptyMap()));
    }
    @Test void shouldCacheReads() throws ExecutionException, InterruptedException {
        assertThat(store.readString("a").get().orElse(""), is("aValue"));
        assertTrue(store.isCached("a"));
    }
    @Test void shouldCacheStreams() throws ExecutionException, InterruptedException, IOException {
        assertThat(store.readStream("a").get().orElseThrow().readAllBytes(), is("aValue".getBytes(StandardCharsets.UTF_8)));
        assertTrue(store.isCached("a"));
    }
    @Test void shouldInvalidateOnWrite() throws ExecutionException, InterruptedException {
        store.readString("a").get();
        store.write("a", "secondValue").get();
        assertFalse(store.isCached("a"));
    }
    @Test void shouldInvalidateOnWriteStream() throws ExecutionException, InterruptedException {
        store.readString("a").get();
        assertTrue(store.isCached("a"));

        store.write("a", new ByteArrayInputStream("secondValue".getBytes(StandardCharsets.UTF_8))).get();
        assertFalse(store.isCached("a"));
    }
    @Test void shouldInvalidateOnTouch() throws ExecutionException, InterruptedException {
        store.touch("a", Instant.now()).get();
        assertFalse(store.isCached("a"));
    }

    @Test void listingFallbackCacheShouldNotBeUsedWhenDisabled() throws ExecutionException, InterruptedException {
        // reset the store created at @BeforeEach
        store.getCloseRunner().run();

        // recreate the store but now disable cache on fail
        // falling back to the original behaviour on failure
        store = new CachedDataStore(failingStore, "cachedStore", Map.of("maxMemSize", "100KB", "readOnlyOnStoreFail", false));

        store.list().get(); // first call so the value would be cached normally
        failingStore.setFailing(true);

        // by default failing store would result in the cached value, but
        // since that is disabled, the original exception should be thrown.
        assertThrows(ExecutionException.class, () -> store.list().get());
    }
    @Test void storeFailureShouldThrowIfNotCached() {
        // cache can not return previous value if not cached before -- in that case it still throws
        failingStore.setFailing(true);
        assertThrows(ExecutionException.class, () -> store.list().get());
    }
    @Test void storeFailureOnListShouldUseCache() throws ExecutionException, InterruptedException {
        // default behaviour with connection
        source.write("b", "bValue").get();
        assertThat(store.list().get().stream().map(item -> item.path).toList(), is(List.of("a","b")));

        // let's cut the connection and try again
        failingStore.setFailing(true);
        assertThat(store.list().get().stream().map(item -> item.path).toList(), is(List.of("a","b"))); // cached value

        // source may be updated but if there is no connection, the consumer won't notice
        source.write("c", "cValue").get();
        assertThat(store.list().get().stream().map(item -> item.path).toList(), is(List.of("a","b")));

        // let's restore the connection. New data should be received now.
        failingStore.setFailing(false);
        assertThat(store.list().get().stream().map(item -> item.path).toList(), is(List.of("a","b","c")));
    }
}