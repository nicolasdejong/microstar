package net.microstar.common.io;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableSet;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Singular;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.datastore.AbstractDataStore;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.MemoryDataStore;
import net.microstar.common.util.DynamicReference;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.Encryption;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateResourceTest {
    private static final int SOME_NUM = 123;
    private static final int SOME_NUM2 = 234;
    private DynamicReferenceNotNull<DataStore> storeRef;

    @BeforeEach
    void setup() {
        final AbstractDataStore store = new MemoryDataStore();
        store.setChangeDebounceDuration(Duration.ZERO);
        storeRef = new DynamicReferenceNotNull<>(store);
    }
    @AfterEach
    void cleanup() {
        getDataStore().getCloseRunner().run();
    }

    @Builder(toBuilder = true) @EqualsAndHashCode
    @ToString
    @Jacksonized
    public static class SomeData {
        public final int num;
        public final String text;
        @Builder.Default
        public final Optional<String> optionalText = Optional.empty();
        @Singular
        public final ImmutableSet<Integer> numbers;
    }

    /** When an object is used as key in a map, it needs a specific Jackson KeySerializer and KeyDeserializer,
      * or a String constructor with compatible toString()
      */
    @EqualsAndHashCode
    public static class ServiceRef {
        public final UUID id;
        public final String name;

        @SuppressWarnings("unused") // It is used reflectively by Jackson
        public ServiceRef(String s) { final String[] parts = s.split("/"); id = UUID.fromString(parts[0]); name = parts[1]; }
        public String toString() { return id + "/" + name; }
    }

    private static SomeData getExampleData() {
        return SomeData.builder()
            .num(SOME_NUM)
            .text("someText")
            .numbers(new HashSet<>())
            .build();
    }

    @Test void dataShouldBeStored() {
        final String storeFile = createTestFile();
        final StateResource<SomeData> store1 = new StateResource<>(storeRef, storeFile, SomeData.class);
        final StateResource<SomeData> store2 = new StateResource<>(storeRef, storeFile, SomeData.class);
        assertThat(store1.getOptional().isEmpty(), is(true));
        assertThat(store2.getOptional().isEmpty(), is(true));
        assertThrows(IllegalStateException.class, store1::get);
        store1.set(getExampleData());
        assertThat(store1.get(), is(getExampleData()));
        assertThat(store2.get(), is(getExampleData()));
        assertThat(store1.getOptional(), is(Optional.of(getExampleData())));
        store2.set(store2.get().toBuilder().num(SOME_NUM2).build());
        assertThat(store1.get().num, is(SOME_NUM2));
        assertThat(store2.get().num, is(SOME_NUM2));
    }
    @Test void defaultShouldBeUsedWhenNoFile() {
        final String storeFile = createTestFile();
        final StateResource<SomeData> store = new StateResource<>(storeRef, storeFile, SomeData.class);
        assertThat(store.getOptional().isEmpty(), is(true));
        assertThat(store.setDefault(getExampleData()), is(store));
        assertThat(store.getOptional().isEmpty(), is(false));
        assertThat(store.get().num, is(SOME_NUM));

    }
    @Test void externalChangesShouldBePickedUp() {
        final String storeFile = createTestFile();
        final StateResource<SomeData> store = new StateResource<>(storeRef, storeFile, SomeData.class);
        store.set(getExampleData());
        final String json = getDataStore().readString(storeFile).orElseThrow();
        sleep(1000); // sleep so that file.lastModified is higher in written file on next line -- resolution is 1s
        getDataStore().write(storeFile, json.replace(""+SOME_NUM, ""+SOME_NUM2));
        assertThat(store.get().num, is(SOME_NUM2));
    }
    @Test void getShouldBeEqualsToSet() {
        final String storeFile = createTestFile();
        final StateResource<SomeData> store = new StateResource<>(storeRef, storeFile, SomeData.class);
        assertThat(store.set(SomeData.builder().num(1).build()).num, is(1));
        assertThat(store.get().num, is(1));
    }
    @Test void storesShouldBeAtomicInTheSameVM() throws InterruptedException {
        final class Inner {
            private Inner() {}
            public static void fill(StateResource<SomeData> store, int from, int upto) {
                IntStream.range(from, upto + 1).forEach(num -> {
                    store.update(oldValue -> oldValue.toBuilder().number(num).build());
                    sleep(3);
                });
            }
        }
        final String storeFile = createTestFile();
        final Supplier<StateResource<SomeData>> createStateResource = () -> new StateResource<>(storeRef, storeFile, SomeData.class).setDefault(getExampleData());
        final List<Thread> threads = List.of(
            new Thread(() -> Inner.fill(createStateResource.get(), 0,99)),
            new Thread(() -> Inner.fill(createStateResource.get(), 100,199)),
            new Thread(() -> Inner.fill(createStateResource.get(), 200,299))
        );
        threads.forEach(Thread::start);
        for(final Thread t : threads) t.join();

        final StateResource<SomeData> result = new StateResource<>(storeRef, storeFile, SomeData.class);
        assertThat(result.get().numbers.size(), is(3*100));
    }
    @Test void objectAsMapKey() {
        final String storeFile = createTestFile();
        getDataStore().write(storeFile, "{}");

        final StateResource<Map<ServiceRef,String>> store = new StateResource<>(storeRef, storeFile, new TypeReference<>() {});
        final Map<ServiceRef,String> map = store.get();

        assertThat(map.size(), is(0));
    }

    private BlockingDataStore getDataStore() {
        return BlockingDataStore.forStore(storeRef);
    }
    private String createTestFile() {
        return Encryption.getRandomString(10, 20);
    }
}