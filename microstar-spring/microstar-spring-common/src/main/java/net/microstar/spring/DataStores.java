package net.microstar.spring;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Singular;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.conversions.DurationString;
import net.microstar.common.datastore.CachedDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.FailingDataStore;
import net.microstar.common.datastore.FileSystemDataStore;
import net.microstar.common.datastore.MemoryDataStore;
import net.microstar.common.datastore.SqlDataStore;
import net.microstar.common.util.DynamicReference;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.Listeners;
import net.microstar.spring.settings.DynamicPropertyRef;
import net.microstar.spring.settings.PropsMap;
import org.springframework.core.ParameterizedTypeReference;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.peek;

/** Data store abstraction access, configured at the "microstar.dataStores" key.<p>
  *
  * This configuration holds a map of data store configurations, where the key is the
  * name of the store and the possible values depend on the type of datastore, but at
  * least:<pre>
  *  - type    Mandatory: One of 'memory, 'filesystem' or 'database'. These are default.
  *            More types may be added via the register() method. Type can also be a
  *            fully qualified class name of a class that implements DataStore and has
  *            a constructor that accepts a PropsMap.</pre>
  *
  * Other keys in the configuration map depend on the type:<pre>
  *
  *  type memory:       - readDelay   Optional: Time to pause before reading. Defaults to 0
  *                     - writeDelay  Optional: Time to pause before writing. Defaults to 0
  *  type filesystem:   - root        Optional: Location on filesystem that is the root for
  *                                   this data store. Defaults to current directory if not
  *                                   provided.
  *  type database      - url         Mandatory: Jdbc connection string
  *                     - table       Optional: (schema.)table to use. Defaults to 'files'.
  *                     - user        Optional: username if not in url
  *                     - password    Optional: password if not in url (can be encrypted
  *                                   using {cipher})
  *                     - poolSize    Database connection pool size. Defaults to 10.
  *                     - pollingTime Optional: time between polling for changes. Defaults to 10s.
  *
  * Every datastore can have caching. For those options see CachedDataStore
  * </pre>
  **/
@Slf4j
public final class DataStores {
    private DataStores() {/*singleton*/}
    private static final AtomicReference<Map<String, Function<PropsMap,DataStore>>> dataStoreFactories = new AtomicReference<>(new HashMap<>());
    private static final Map<String, StoreInfo> nameToStoreInfo = new HashMap<>() {
        @Override
        public StoreInfo remove(Object obj) {
            throw new IllegalStateException("Never remove StoreInfos -- the DynamicReferences would be lost");
        }
    };
    private static final DynamicPropertyRef<Map<String,Object>> configuration = DynamicPropertyRef.<Map<String,Object>>of("microstar.dataStores", new ParameterizedTypeReference<>(){})
        .withDefault(Collections.emptyMap())
        .onChange(cfg -> updateStoresForChangedConfiguration());
    private static final Listeners<String,String> storeChangeListeners = new Listeners<>() {
        @Override
        protected void call(Consumer<String> listener, @Nullable String nameListenedFor, @Nullable String name, @Nullable String unused) {
            if(nameListenedFor == null || nameListenedFor.equals(name)) listener.accept(name);
        }
    };

    @Builder(toBuilder = true)
    private static class StoreInfo {
        @Default public final PropsMap storeSettings = PropsMap.empty();
        @Default public final DynamicReferenceNotNull<DataStore> storeRef = DynamicReferenceNotNull.of(new FailingDataStore("unnamed"));
    }
    @Builder @ToString
    public static class DataStoreChange {
        public final String name;
        @Singular
        public final List<String> paths;
    }

    static {
        registerDefaultFactories();
    }

    private static void registerDefaultFactories() {
        registerFactory("memory", map -> new MemoryDataStore(
            DurationString.toDuration(map.getString("readDelay").orElse("0s")),
            DurationString.toDuration(map.getString("writeDelay").orElse("0s"))
        ));
        registerFactory("filesystem", map -> new FileSystemDataStore(
            // path on local filesystem which is root of the FileSystemDataStore
            // one of:
            //  - single/path
            //  - multiple/paths,seperated/by,commas/
            //  - [multiple, paths, list]
            // In case of multiple, the first path that exists is used
            map.getList("root").map(roots -> roots.stream()
                .map(Objects::toString)
                .map(Path::of)
                .toArray(Path[]::new)
            ).orElseGet(() ->
                map.getString("root")
                    .map(root -> Arrays.stream(root.split("\\s*,\\s*"))
                        .map(Path::of)
                        .toArray(Path[]::new))
                    .orElseThrow(() -> new IllegalArgumentException("No filesystem root provided"))
            )
        ));
        registerFactory("database", map -> new SqlDataStore(
            getMandatoryString("database", map, "url"),
            map.getString("section").orElse(""),
            map.getString("table").orElse("files"),
            map.getString("user").orElse(""),
            map.getString("password").orElse(""),
            map.getInteger("poolSize").orElse(10),
            DurationString.toDuration(map.getString("pollingTime").orElse("10s"))
        ));
        configuration.callOnChangeHandlers(); // after registering types
    }


    public static void registerFactory(String type, Function<PropsMap,DataStore> dataStoreFactory) {
        synchronized(dataStoreFactories) {
            final Map<String,Function<PropsMap,DataStore>> newFactories = new HashMap<>(dataStoreFactories.get());
            newFactories.put(type, dataStoreFactory);
            dataStoreFactories.set(newFactories);
        }
    }

    public static Runnable addStoreChangeListener(String storeName, Runnable calledWhenRebuilt) {
        return storeChangeListeners.add(storeName, name -> calledWhenRebuilt.run());
    }

    public static void closeAll() {
        synchronized (nameToStoreInfo) {
            nameToStoreInfo.keySet().stream().toList().forEach(DataStores::close);
        }
    }
    public static void close(String name) {
        synchronized (nameToStoreInfo) {
            Optional.ofNullable(nameToStoreInfo.get(name))
                .ifPresent(storeInfo -> {
                    log.info("Closing datastore '{}'", name);
                    storeInfo.storeRef.get().getCloseRunner().run();
                    storeInfo.storeRef.set(new FailingDataStore(name));
                });
        }
    }
    public static void close(DynamicReference<DataStore> storeRef) {
        synchronized (nameToStoreInfo) {
            nameToStoreInfo.entrySet().stream()
                .filter(entry -> entry.getValue().storeRef.equals(storeRef))
                .map(Map.Entry::getKey)
                .findFirst()
                .ifPresent(DataStores::close);
        }
    }
    public static Optional<String> nameOf(DynamicReference<DataStore> storeRef) {
        return Optional.ofNullable(storeRef.get()).flatMap(DataStores::nameOf);
    }
    public static Optional<String> nameOf(DataStore store) {
        synchronized (nameToStoreInfo) {
            return nameToStoreInfo.entrySet().stream()
                .filter(entry -> Objects.equals(entry.getValue().storeRef.getOptional().orElse(null), store))
                .map(Map.Entry::getKey)
                .findFirst();
        }
    }

    public static boolean isFailingDataStore(DataStore ds) {
        return ds instanceof FailingDataStore;
    }

    /** Returns a reference to the requested datastore.
      * The reference will be updated when settings change, leading to a new store.
      * When no store exists for the given storeName, a FailingDataStore will be returned.<p>
      *
      * Every store is constructed only once and will be used by all simultaneously.
      * Therefore, each store needs to support multiple threads.<p>
      *
      * The instance will be invalidated when its configuration changes, as new
      * store configuration will lead to a new instance. The old one will be
      * closed then. This is the reason only a dynamic reference to the store is
      * provided instead of the store itself.<p>
      *
      * The user of the store should *always* use the DynamicReference to get
      * the store and *never* keep the store as the store may have been replaced
      * the next time the DynamicReference.get() is called (e.g. due to settings
      * changes).
      */
    public static DynamicReferenceNotNull<DataStore> get(String storeName) {
        synchronized (nameToStoreInfo) {
            return nameToStoreInfo
                .computeIfAbsent(storeName, sn -> buildStoreInfo(
                    storeName,
                    // This is the 'oldStoreInfo'. The DynamicReference of the StoreInfo
                    // should *always* be moved to any new StoreInfo instance so the
                    // existing DynamicReferences keep working. So the DynamicReference
                    // created here forever stays related to the store with this storeName.
                    StoreInfo.builder().storeRef(DynamicReferenceNotNull.of(new FailingDataStore(storeName))).build()))
                .storeRef;
        }
    }

    /** This method will be called when the configuration changes. Will only lead to updated
      * stores when configuration for that store was changed since the last time this was called.<p>
      *
      * Calling the method otherwise may be in case configuration change handlers are called before
      * this method is called.<p>
      *
      * Note that an ephemeral store like the MemoryDataStore will lose all its data when recreated.
      */
    public  static void updateStoresForChangedConfiguration() {
        synchronized (nameToStoreInfo) {
            nameToStoreInfo
                .keySet()
                .forEach(name -> nameToStoreInfo
                    .computeIfPresent(name, (n, oldInfo) -> buildStoreInfo(name, oldInfo)));
        }
    }

    private static StoreInfo buildStoreInfo(String storeName, StoreInfo oldStoreInfo) {
        //noinspection unchecked
        return Optional.ofNullable(configuration.get().get(storeName))
            .map(mapObj -> mapObj instanceof Map<?, ?> map ? PropsMap.fromDeepMap((Map<String, Object>) map) : null)
            .or(() -> Optional.of(PropsMap.empty()))
            .map(settings ->
                oldStoreInfo.storeSettings.equals(settings)
                    ? oldStoreInfo
                    : peek(oldStoreInfo.toBuilder(), result -> log.info("Updating settings of DataStore '{}' for changed configuration", storeName))
                        .storeRef(
                            // A supplier is provided (which will actually build the store) and the value is
                            // set to null meaning the next time a get() is performed on the DynamicReference,
                            // the supplier will be called. This way the store only initializes when used.
                            oldStoreInfo.storeRef.setDefault(() -> buildStore(storeName, settings)).set(null, /*forceChangeEvent:*/true)
                        )
                        .storeSettings(settings)
                        .build())
            .filter(storeInfo -> { if(storeInfo != oldStoreInfo) storeChangeListeners.call(storeName); return true; }) // peek()
            .orElseThrow(); // this won't happen because of the or()
    }
    private static DataStore buildStore(String storeName, @Nullable PropsMap storeSettings) {
        if (storeSettings == null || storeSettings.isEmpty()) {
            log.warn("No settings found for DataStore '{}'", storeName);
            return new FailingDataStore(storeName);
        }
        final String storeType = storeSettings.getString("type").orElse("");
        final @Nullable Function<PropsMap, DataStore> factoryForSettings = dataStoreFactories.get().get(storeType);

        // A class name can be provided as type. This class should implement the DataStore
        // interface and have a constructor that accepts a PropsMap
        //noinspection unchecked
        final DataStore newDataStore = Objects.requireNonNullElseGet(factoryForSettings,
                () -> noThrow(() -> (Class<DataStore>) Class.forName(storeType))
                    .flatMap(c -> noThrow(() -> c.getConstructor(PropsMap.class)))
                    .map(constr -> (Function<PropsMap, DataStore>) (PropsMap propsMap) -> noThrow(() -> constr.newInstance(propsMap)).orElse(null))
                    .orElseGet(() -> {
                        log.warn("(At this time) unknown dataStore requested: {}", storeName);
                        return pm -> new FailingDataStore(storeName);
                    }))
            .apply(storeSettings);

        final DataStore resultStore = storeSettings.getMap().containsKey("cache")
            ? new CachedDataStore(
                newDataStore, storeName,
                storeSettings.getMap("cache").orElseThrow(() -> new IllegalArgumentException("The 'cache' value should be a map but is " + storeSettings.get("cache"))))
            : newDataStore;

        log.info("Created new {}instance of DataStore '{}'", resultStore instanceof CachedDataStore ? "(cached) " : "", storeName);
        return resultStore;
    }

    private static String getMandatoryString(String type, PropsMap map, String key) {
        return map.getString(key).orElseThrow(() -> new IllegalArgumentException("Failed to initialize dataStore of type " + type + ": the mandatory key '" + key + "' is not provided."));
    }
}
