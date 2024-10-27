package net.microstar.spring;

import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.FailingDataStore;
import net.microstar.common.datastore.FileSystemDataStore;
import net.microstar.common.datastore.MemoryDataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReference;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("DataFlowIssue") // no null checks here
class DataStoresTest {

    @AfterEach void cleanup() {
        DataStores.closeAll();
        DynamicPropertiesManager.clearAllState();
    }

    @Test void storeShouldBeUpdatedWhenConfigurationChanges() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "memory", "n", 1)
                )
            )
        ));
        final DynamicReferenceNotNull<DataStore> storeRef = DataStores.get("testStore");
        final DataStore store1 = storeRef.get();
        assertThat(storeRef.get(), is(store1));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                    "testStore", Map.of("type", "memory", "n", 2)
                )
            )
        ));
        assertThat(storeRef.get(), not(is(store1)));
    }
    @Test void userShouldBeUpdatedWhenConfigurationChanges(@TempDir Path tempDir1, @TempDir Path tempDir2) {
        IOUtils.writeString(tempDir1.resolve("testFile1.txt"), "abc1");
        IOUtils.writeString(tempDir2.resolve("testFile2.txt"), "abc2");
        final AtomicInteger storeUpdateCount = new AtomicInteger(0);

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "filesystem", "root", tempDir1.toString())
            ))
        ));

        final DynamicReference<DataStore> storeRef = DataStores.get("testStore")
                .whenChanged(store -> storeUpdateCount.incrementAndGet());

        assertThat(storeUpdateCount.get(), is(0));
        assertInstanceOf(FileSystemDataStore.class, storeRef.get()); // side effect of get() creates the store (lazy construct)
        assertThat(storeUpdateCount.get(), is(1));

        assertThat(noCheckedThrow(() -> storeRef.get().readString("testFile1.txt").get()), is(Optional.of("abc1")));
        assertThat(storeUpdateCount.get(), is(1));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "filesystem", "root", tempDir2.toString())
            ))
        ));
        assertThat(storeUpdateCount.get(), is(2)); // new call because of configuration change
        assertThat(noCheckedThrow(() -> storeRef.get().readString("testFile2.txt").get()), is(Optional.of("abc2")));
    }
    @Test void userShouldBeAbleToSubscribeBeforeStoreIsRegistered() {
        final AtomicInteger newStoreCount = new AtomicInteger(0);

        final DynamicReference<DataStore> storeRef = DataStores.get("testStore")
            .whenChanged(store -> newStoreCount.incrementAndGet());

        assertTrue(DataStores.isFailingDataStore(storeRef.get()));
        assertThat(DataStores.nameOf(storeRef).orElse(""), is("testStore"));
        assertThat(newStoreCount.get(), is(0)); // No configuration yet

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "notATestStore", Map.of("type", "memory")
            ))
        ));

        assertInstanceOf(FailingDataStore.class, storeRef.get());
        assertThat(newStoreCount.get(), is(0)); // not called again -- no 'testStore' configured yet

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "memory")
            ))
        ));
        assertThat(newStoreCount.get(), is(1)); // called after configuration adds testStore
        assertInstanceOf(MemoryDataStore.class, storeRef.get());
    }
    @Test void removedDataStoreShouldFail() throws ExecutionException, InterruptedException {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "memory")
            ))
        ));

        final DynamicReference<DataStore> storeRef = DataStores.get("testStore");
        assertInstanceOf(MemoryDataStore.class, storeRef.get());

        storeRef.get().write("/path", "data").get(); // does not fail
        assertThat(storeRef.get().readString("/path").get().orElseThrow(), is("data"));

        DynamicPropertiesManager.clearAllState();

        assertInstanceOf(FailingDataStore.class, storeRef.get());
    }
    @Test void dataStoreShouldBeCreatedOnceEvenIfUsedByMultipleUsers(@TempDir Path tempDir1, @TempDir Path tempDir2) {
        final AtomicInteger newStoreCount = new AtomicInteger(1);

        final DynamicReference<DataStore> storeRef = DataStores.get("testStore");
        assertInstanceOf(FailingDataStore.class, storeRef.get());

        final AtomicReference<DataStore> firstStoreRef = new AtomicReference<>();
        storeRef
            .whenChanged(store -> {
                switch (newStoreCount.getAndIncrement()) {
                    // First change: configuration for testStore was added leading to a new store supplier that is called on get()
                    case 1: {
                        assertInstanceOf(FileSystemDataStore.class, storeRef.get()); // get() call triggers creation of store (which increases newStoreCount)
                        firstStoreRef.set(storeRef.get());
                        break;
                    }
                    // Second change: triggered by the storeRef.get() in case 1.
                    case 2: { break; }

                    // Third change: the testStore configuration was changed leading to a new store supplier that is called on get()
                    case 3: {
                        assertInstanceOf(FileSystemDataStore.class, storeRef.get()); // get() call triggers creation of store (which increases newStoreCount)
                        assertThat(storeRef.get(), not(is(firstStoreRef.get())));
                        break;
                    }

                    // Fourth change: triggered by the storeRef.get() in case 3.
                    case 4: { break; }
                }
                // when case 4 has been processed, newStoreCount is 5
            });

        // First configuration, leading to a new 'testStore'.
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "filesystem", "root", tempDir1.toString()),
                "notATestStore", Map.of("type", "memory")
            ))
        ));

        // Second configuration change should *not* create a new testStore when its attributes don't change
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "filesystem", "root", tempDir1.toString()),
                "notATestStore", Map.of("type", "memory", "n", 1)
            ))
        ));

        // Third configuration change changes the root of the testStore which *should* lead to a new instance of the store
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "testStore", Map.of("type", "filesystem", "root", tempDir2.toString()),
                "notATestStore", Map.of("type", "memory", "n", 1)
            ))
        ));

        // So there are 3 configuration changes and 2 changes triggered by the get() calls in the whenChanged() above
        assertThat(newStoreCount.get(), is(5));
    }
    @Test void storeChangeListenerShouldBeCalledWhenStoreConfigurationChanged() {
        final AtomicInteger callCount = new AtomicInteger(0);
        final Runnable removeListener = DataStores.addStoreChangeListener("testStore", callCount::incrementAndGet);
        try {
            // A store reference must be requested before any events for it will be generated
            DataStores.get("testStore");

            DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
                Map.of("microstar.dataStores", Map.of(
                        "testStore", Map.of("type", "memory", "n", 1) // initial setting for this store -- should trigger callback
                    )
                )
            ));
            assertThat(callCount.get(), is(1));

            DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
                Map.of("microstar.dataStores", Map.of(
                        "testStore", Map.of("type", "memory", "n", 1), // the same as before
                        "otherStore", Map.of("type", "foobar")
                    )
                )
            ));
            assertThat(callCount.get(), is(1)); // no change because the configuration didn't change

            DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
                Map.of("microstar.dataStores", Map.of(
                        "testStore", Map.of("type", "memory", "n", 2) // changed from before -- should trigger callback
                    )
                )
            ));
            assertThat(callCount.get(), is(2));
        } finally {
            removeListener.run();
        }
    }
}
