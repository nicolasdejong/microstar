package net.microstar.common.util;

import net.microstar.common.conversions.DurationString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.testing.TestUtils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled // Disabled as on Jenkins this test hangs, unlike on other environments
@SuppressWarnings({"squid:S5778"/*multiple statements in shouldThrow()*/,"ExtractMethodRecommender"})
class CacheTest {
    @Nullable private Cache cache;

    @AfterEach void cleanup() {
        if(cache != null) { cache.close(); cache = null; }
    }

    @Test void configurationShouldValidateStorage() {
        assertThrows(IllegalArgumentException.class, () -> new Cache(Cache.Configuration.builder().build()));
        assertThrows(IllegalArgumentException.class, () -> new Cache(Cache.Configuration.builder()
            .maxMemItemSize(ByteSize.ZERO)
            .build()));
        assertThrows(IllegalArgumentException.class, () -> new Cache(Cache.Configuration.builder()
            .maxMemItemFraction(0)
            .build()));
    }
    @Test void settingOnlyCountShouldSetSizeToUnlimited() {
        // This throws when maxMemSize and maxFilesSize is the default (0)
        // Test here if the cache unlimits the cache that belongs to the given count (mem or files
        assertDoesNotThrow(() -> new Cache(Cache.Configuration.builder().maxMemCount(3).build()).put("a", "aa"));
    }
    @Test void cacheShouldKeepData() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofBytes(100)).build());
        cache.put("a", "aaa");
        cache.put("b" ,"bbb");
        assertThat(cache.getString("a"), is("aaa"));
        assertThat(cache.getString("b"), is("bbb"));
        assertTrue(cache.isInMem("a"));
    }
    @Test void onceClosedAnyCallShouldThrow() {
        cache = Cache.onlyMemory(ByteSize.ofKilobytes(100));
        cache.put("a", "111"); // [][ a ]
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.get("a'"));
    }
    @Test void invalidateShouldRemoveKey() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(100)).build());
        cache.put("a", "111"); // [][ a ]
        assertTrue(cache.containsKey("a"));
        cache.invalidate("a");
        assertFalse(cache.containsKey("a"));
    }
    @Test void invalidateAllShouldClearTheCache() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(100)).build());
        cache.put("a", "111");
        cache.put("b", "222");
        cache.invalidateAll();
        assertFalse(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
        assertTrue(cache.memSize().isZero());
        cache.put("a", "11");
        assertTrue(cache.containsKey("a"));
        assertFalse(cache.containsKey("b"));
        assertThat(cache.memSize().getBytesInt(), is(2));
    }
    @Test void putShouldMakeKeyAvailable() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(100)).build());
        cache.put("a", "111"); // [][ a ]
        assertTrue(cache.containsKey("a"));
        assertThat(cache.getString("a"), is("111"));
    }
    @Test void getShouldReturnMemCachedValue() {
        cache = Cache.onlyMemory(ByteSize.ofKilobytes(100));
        cache.put("a", "111"); // [][ a ]
        assertThat(cache.getString("a"), is("111"));
    }
    @Test void getShouldReturnFileCachedValue() {
        cache = Cache.onlyFiles(ByteSize.ofKilobytes(100));
        cache.put("a", "111"); // [ a ][]
        assertThat(cache.getString("a"), is("111"));
    }
    @Test void getShouldCallSupplierWhenMissing() {
        final AtomicInteger supplierCallCount = new AtomicInteger();
        final Function<String,byte[]> supplier = s -> {
            supplierCallCount.incrementAndGet();
            return ("@" + s).getBytes(StandardCharsets.UTF_8);
        };
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofBytes(5)).dataSupplier(supplier).build());
        assertThat(cache.size().getBytesInt(), is(0));

        assertThat(cache.getString("a"), is("@a"));
        assertThat(cache.toStackString(), is("[][a]"));
        assertThat(supplierCallCount.get(), is(1));
        assertThat(cache.size().getBytesInt(), is(2));
        assertThat(cache.getString("a"), is("@a"));
        assertThat(supplierCallCount.get(), is(1));

        assertThat(cache.getString("b"), is("@b"));
        assertThat(cache.toStackString(), is("[][a,b]"));
        assertThat(supplierCallCount.get(), is(2));
        assertThat(cache.size().getBytesInt(), is(4));
        assertThat(cache.getString("b"), is("@b"));
        assertThat(supplierCallCount.get(), is(2));

        assertThat(cache.getString("c"), is("@c"));
        assertThat(cache.toStackString(), is("[][b,c]"));
        assertThat(supplierCallCount.get(), is(3));
        assertThat(cache.size().getBytesInt(), is(4));
        assertThat(cache.getString("c"), is("@c"));
        assertThat(supplierCallCount.get(), is(3));
    }
    @Test void noValueWhenNoSupplierShouldThrow() {
        cache = Cache.onlyMemory(ByteSize.ofKilobytes(100));
        assertThrows(IllegalArgumentException.class, () -> cache.get("a"));
    }
    @Test void nullValueFromSupplierShouldThrow() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(100)).dataSupplier(s->null).build());
        assertThrows(IllegalStateException.class, () -> cache.get("a"));
    }
    @Test void thereShouldNotBeConcurrentSupplyCalls() {
        final String errorMessage = "Supplier should not be called concurrently for the same key";
        final AtomicBoolean testFailed = new AtomicBoolean();
        for(int retest=0; retest<10; retest++) {
            final Set<String> supplyingKey = ConcurrentHashMap.newKeySet();
            final Function<String,byte[]> supplier = s -> { // cannot be extracted as method because supplyingKey is needed
                try {
                    if(!testFailed.get()) {
                        final boolean isConcurrentCall = supplyingKey.contains(s);
                        if(isConcurrentCall) testFailed.set(true); // the next line will throw but not fail the test as it is in a different thread
                        assertFalse(isConcurrentCall, errorMessage);
                        supplyingKey.add(s);
                        sleep(5);
                    }
                    return ("@" + s).getBytes(StandardCharsets.UTF_8);
                } finally {
                    supplyingKey.remove(s);
                }
            };
            cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofBytes(5)).dataSupplier(supplier).build());
            final Object trigger = new Object();
            final AtomicInteger runCount = new AtomicInteger();
            final int threadCount = 100;
            for(int i=0; i<threadCount; i++) {
                new ThreadBuilder().run(() -> {
                    runCount.incrementAndGet();
                    synchronized(trigger) { noCheckedThrow(() -> trigger.wait()); }
                    try {
                        cache.get("a");
                    } finally {
                        runCount.decrementAndGet();
                    }
                });
            }
            while(runCount.get() < threadCount) sleep(1);
            sleep(10);
            synchronized(trigger) { trigger.notifyAll(); }
            while(runCount.get() > 0) sleep(1);
            assertFalse(testFailed.get(), errorMessage);
        }
    }
    @Test void shouldMapObjectToBytes() {
        final Function<Object,byte[]> mapper = obj -> {
            if(obj instanceof Duration d) { return (""+d.toMillis()).getBytes(StandardCharsets.UTF_8); }
            throw new IllegalArgumentException("Object of type " + (obj==null?null:obj.getClass()) + " not supported by mapper");
        };
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(100)).toBytesMapper(mapper).build());
        cache.put("a", Duration.ofMillis(123));
        assertThat(cache.getString("a"), is("123"));
        assertThrows(IllegalArgumentException.class, () -> cache.put("b", ByteSize.ofBytes(234)));
    }
    @Test void shouldMapBytesToType() {
        final Function<byte[],Duration> mapper = bytes -> {
            final String s = new String(bytes, StandardCharsets.UTF_8);
            return Duration.ofMillis(Integer.parseInt(s));
        };
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(100)).toTypeMapper(Duration.class, mapper).build());
        cache.put("a", "123");
        assertThat(cache.get("a", Duration.class), is(Duration.ofMillis(123)));
        assertThrows(IllegalArgumentException.class, () -> cache.get("a", ByteSize.class));
    }
    @Test void maxMemSizeShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofBytes(7)).build());
        cache.put("a", "111"); // [][ a ]
        assertThat(cache.toStackString(), is("[][a]"));
        assertThat(cache.memSize().getBytesInt(), is(3));

        cache.put("b" ,"222"); // [][ a b ]
        assertThat(cache.toStackString(), is("[][a,b]"));
        assertThat(cache.memSize().getBytesInt(), is(6));

        cache.put("c" ,"333"); // [][ b c ]
        assertThat(cache.toStackString(), is("[][b,c]"));
        assertThat(cache.memSize().getBytesInt(), is(6));

        cache.get("b"); // [][ c b ]
        cache.put("d" ,"444"); // [][ b d ]
        assertThat(cache.toStackString(), is("[][b,d]"));
        assertThat(cache.memSize().getBytesInt(), is(6));
    }
    @Test void maxMemCountShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(1)).maxMemCount(3).build());
        cache.put("a", "123");
        assertThat(cache.toStackString(), is("[][a]"));
        assertThat(cache.memCount(), is(1));

        cache.put("b" ,"456");
        assertThat(cache.toStackString(), is("[][a,b]"));
        assertThat(cache.memCount(), is(2));

        cache.put("c" ,"789");
        assertThat(cache.toStackString(), is("[][a,b,c]"));
        assertThat(cache.memCount(), is(3));

        cache.put("d" ,"012");
        assertThat(cache.toStackString(), is("[][b,c,d]"));
        assertThat(cache.memCount(), is(3));

        cache.get("b"); // last read, making order [c, d, b] removing c on next put
        cache.put("e" ,"012");
        assertThat(cache.toStackString(), is("[][d,b,e]"));
        assertThat(cache.memCount(), is(3));
        assertThat(cache.count(), is(3));
    }
    @Test void maxMemItemSizeShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder()
            .maxFilesSize(ByteSize.ofKilobytes(100))
            .maxMemSize(ByteSize.ofKilobytes(100))
            .maxMemItemSize(ByteSize.ofBytes(5))
            .build());
        cache.put("a", "123");
        assertThat(cache.toStackString(), is("[][a]"));

        cache.put("b", "123456"); // size=6 would fit in memory (3+6 < 10) but is larger than max item size (5)
        assertThat(cache.toStackString(), is("[b][a]"));
    }
    @Test void maxMemItemFractionShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxFilesSize(ByteSize.ofKilobytes(100)).maxMemSize(ByteSize.ofBytes(100)).maxMemItemFraction(0.05).build());
        cache.put("a", "123");
        assertThat(cache.toStackString(), is("[][a]"));

        cache.put("b", "123456"); // size=6 would fit in memory (3+6 < 10) but is larger than max item size (5)
        assertThat(cache.toStackString(), is("[b][a]"));
    }
    @Test void maxFileSizeShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxFilesSize(ByteSize.ofBytes(7)).build());
        cache.put("a", "111"); // [ a ][]
        assertThat(cache.toStackString(), is("[a][]"));

        cache.put("b", "222"); // [ a b ][]
        assertThat(cache.toStackString(), is("[a,b][]"));

        assertThat(cache.filesCount(), is(2));
        assertThat(cache.filesSize().getBytesInt(), is(6));

        cache.put("c", "333"); // [ b c ][]
        assertThat(cache.filesCount(), is(2));
        assertThat(cache.filesSize().getBytesInt(), is(6));
        assertThat(cache.toStackString(), is("[b,c][]"));
    }
    @Test void maxFileCountShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxFilesSize(ByteSize.ofKilobytes(1)).maxFilesCount(2).build());
        cache.put("a", "1"); // [ a ][]
        assertThat(cache.toStackString(), is("[a][]"));

        cache.put("b", "2"); // [ a b ][]
        assertThat(cache.toStackString(), is("[a,b][]"));

        assertThat(cache.filesCount(), is(2));

        cache.put("c", "3"); // [ b c ][]
        assertThat(cache.filesCount(), is(2));
        assertThat(cache.toStackString(), is("[b,c][]"));
    }
    @Test void cacheShouldMoveBetweenMemAndFile() {
        cache = new Cache(Cache.Configuration.builder().maxFilesSize(ByteSize.ofKilobytes(100)).maxMemSize(ByteSize.ofKilobytes(100)).maxMemCount(2).build());
        cache.put("a", "1"); // [][ a ]
        assertThat(cache.toStackString(), is("[][a]"));

        cache.put("b", "2"); // [][ a b ]
        assertThat(cache.toStackString(), is("[][a,b]"));

        cache.put("c", "3"); // [ a ][ b c ]
        assertThat(cache.toStackString(), is("[a][b,c]"));

        cache.put("d", "4"); // [ a b ][ c d ]
        assertThat(cache.toStackString(), is("[a,b][c,d]"));

        cache.get("b"); // [ a c ][ d b ] -- MRU so getting b brings it to top (most right here)
        assertThat(cache.toStackString(), is("[a,c][d,b]"));
    }
    @Test void maxAgeShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(1)).maxAge(Duration.ofMillis(10)).build());
        cache.put("a", "111");
        assertThat(cache.toStackString(), is("[][a]"));
        Utils.sleep(100);

        // There is no automatic process that checks every millisecond if age is reached
        // as that would be too expensive. It only checks on gets or puts.
        cache.put("b", "222");
        assertThat(cache.toStackString(), is("[][b]"));
    }
    @Test void maxUnreadAgeShouldBeGuarded() {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(1)).maxUnreadAge(Duration.ofMillis(50)).build());
        cache.put("a", "111");
        cache.put("b", "222");
        assertThat(cache.toStackString(), is("[][a,b]"));
        final long t0 = System.currentTimeMillis();
        while(System.currentTimeMillis() - t0 < 500 && cache.containsKey("b")) {
            Utils.sleep(5);
            cache.get("a");
        }
        assertThat(cache.toStackString(), is("[][a]"));
    }
    @Test void getAsFutureShouldReturnCachedValue() throws ExecutionException, InterruptedException {
        final Function<String,byte[]> supplier = s -> ("@" + s).getBytes(StandardCharsets.UTF_8);
        final Function<byte[],Duration> mapper = bytes -> DurationString.toDuration(new String(bytes, StandardCharsets.UTF_8));
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(1)).dataSupplier(supplier).toTypeMapper(Duration.class, mapper).build());
        cache.put("a", "1s");

        assertThat(cache.getStringAsFuture("a").get(), is("1s"));
        assertThat(cache.getStringAsFuture("b").get(), is("@b"));
        assertThat(cache.getAsFuture("b").thenApply(stream -> noCheckedThrow(()->new String(stream.readAllBytes(), StandardCharsets.UTF_8))).get(), is("@b"));
        assertThat(cache.getAsFuture("a", Duration.class).get(), is(Duration.ofSeconds(1)));
        assertThat(cache.getBytesAsFuture("b").thenApply(bytes -> new String(bytes, StandardCharsets.UTF_8)).get(), is("@b"));
    }
    @Test void getBytesAsFutureShouldNotBeAsyncWhenFromMemory() throws ExecutionException, InterruptedException {
        cache = new Cache(Cache.Configuration.builder().maxMemSize(ByteSize.ofKilobytes(1)).build());
        cache.put("a", "@a");

        final AtomicReference<Thread> futureThread = new AtomicReference<>();
        final CompletableFuture<String> aFuture = cache.getStringAsFuture("a").thenApply(s -> {
            futureThread.set(Thread.currentThread());
            return s;
        });
        assertThat(aFuture.get(), is("@a"));
        assertEquals(Thread.currentThread(), futureThread.get());
        assertNotNull(futureThread.get());
    }
    @Test void itemThatDoesNotFitInCacheShouldNotBeCached() {
        final Cache cache = Cache.onlyMemory(ByteSize.ofBytes(10));
        cache.put("a", "0123456789012");
        assertFalse(cache.containsKey("a"));
        assertThat(cache.toStackString(), is("[][]"));

        final Cache cache2 = Cache.using(Cache.Configuration.builder()
            .maxMemSize(ByteSize.ofBytes(10))
            .maxFilesSize(ByteSize.ofBytes(20))
            .build());

        cache2.put("a", "12345"); // [][ a ]
        assertThat(cache2.toStackString(), is("[][a]"));

        cache2.put("b", "123456"); // [ b ][]
        assertThat(cache2.toStackString(), is("[a][b]"));

        cache2.put("c", "12345678901234567890123"); // no change because item is too large
        assertThat(cache2.toStackString(), is("[a][b]"));
    }
}