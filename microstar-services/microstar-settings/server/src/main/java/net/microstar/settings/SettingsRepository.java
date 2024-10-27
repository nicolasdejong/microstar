package net.microstar.settings;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.annotation.PreDestroy;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.Threads;
import net.microstar.common.util.VersionComparator;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.settings.FileHistory.FileVersion;
import net.microstar.settings.model.SettingsFile;
import net.microstar.spring.DataStores;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.MiniBus;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.io.Closeable;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.function.Predicate.not;
import static net.microstar.common.MicroStarConstants.UUID_ZERO;
import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.ExceptionUtils.rethrow;
import static net.microstar.common.util.ThreadUtils.debounce;
import static net.microstar.settings.FileHistory.ActionType.CHANGED;
import static net.microstar.settings.FileHistory.ActionType.DELETED;
import static net.microstar.settings.FileHistory.ActionType.RENAMED;
import static net.microstar.settings.FileHistory.ActionType.RESTORED;

@Service
@Slf4j
@EnableScheduling
public class SettingsRepository implements Closeable {
    static final String CURRENT_DIR_NAME = "current";
    static final String HISTORY_DIR_NAME = "history";
    private static final String DATASTORE_NAME = "settings";
    private static final DynamicPropertiesRef<SettingsProperties> settings = DynamicPropertiesRef.of(SettingsProperties.class);
    private final DispatcherService dispatcher;
    private final SettingsSyncer syncer;
    private final DynamicReferenceNotNull<DataStore> dataStoreRef;
    private final MiniBus miniBus;

    @EqualsAndHashCode
    private static class ServiceRef {
        final UUID instanceId;
        final ServiceId serviceId;

        ServiceRef(UUID instanceId, ServiceId serviceId) { this.instanceId = instanceId; this.serviceId = serviceId; }

        public String toString() { return instanceId + "/" + serviceId.combined; }
    }
    /** This map keeps for each service instance a list of filenames that are needed by the instance */
    private final AtomicReference<ImmutableMap<ServiceRef, ImmutableSet<String>>> settingsDependenciesPerServiceInstance = new AtomicReference<>();

    @SuppressWarnings("this-escape")
    public SettingsRepository(DispatcherService dispatcher, MicroStarApplication app, MiniBus miniBus) {
        this.dispatcher = dispatcher;
        this.miniBus = miniBus;
        this.syncer = new SettingsSyncer(this, dispatcher);
        this.dataStoreRef = DataStores.get(DATASTORE_NAME);

        final BlockingDataStore store = getStore();

        store.list(CURRENT_DIR_NAME).stream()
            .filter(item -> !store.isDir(item))
            .forEach(this::addNewVersionIfMissing);

        settingsDependenciesPerServiceInstance.set(ImmutableUtil.emptyMap());

        log.info("SettingsRepository started in dataStore '{}'", DATASTORE_NAME);

        app.onRegistered(this::syncSettingsBetweenStars);
    }

    /** A mapping is kept from serviceInstanceId to the files it depends on.
      * Periodically remove instances that no longer exist.
      */
    @Scheduled(fixedDelay = 600, initialDelay = 10, timeUnit = TimeUnit.SECONDS)
    private void pruneSettingsInstances() {
        final ImmutableMap<UUID,ServiceId> knownInstanceIds = dispatcher.getServiceInstanceIds();

        //noinspection UnstableApiUsage -- the putAll() method has been in beta since Guava 19, now using Guava 31
        settingsDependenciesPerServiceInstance.set(
                ImmutableMap.<ServiceRef,ImmutableSet<String>>builder()
                    .putAll( settingsDependenciesPerServiceInstance.get().entrySet().stream()
                        .filter(entry -> knownInstanceIds.containsKey(entry.getKey().instanceId))
                        .toList())
                    .build()
        );
    }

    @Override @PreDestroy
    public void close() {
        log.info("Closing SettingsRepository...");
        DataStores.close(dataStoreRef);
        log.info("Closing SettingsRepository finished");
    }

    public boolean hasResourceWithName(String name) {
        return pathForCurrentOrHistoryExistingName(name).filter(p -> !p.endsWith("/")).isPresent();
    }


    public List<SettingsFile> getAllFilenames() {
        final Set<String> current = streamListing(CURRENT_DIR_NAME, /*recursive=*/true)
            .map(item -> item.path)
            .filter(not(IOUtils::isProbablyTempFile))
            .collect(Collectors.toSet());
        final Set<String> history = streamListing(HISTORY_DIR_NAME, /*recursive=*/true)
            .map(item -> item.path)
            .filter(name -> name.endsWith("/state"))
            .map(name -> name.replaceFirst("/state$", ""))
            .filter(not(IOUtils::isProbablyTempFile))
            .collect(Collectors.toSet());
        final Set<String> deleted = history.stream()
            .filter(name -> !current.contains(name))
            .collect(Collectors.toSet());

        return history.stream()
            .sorted()
            .map(name -> SettingsFile.builder().name(name).isDeleted(deleted.contains(name)).build())
            .toList();
    }

    public void store(String name, String content, String username, @Nullable String message) {
        final String path = pathForCurrentNewName(name) // either new (create) or existing (overwrite)
            .orElseThrow(() -> new FatalException("Illegal path: " + name));
        final FileHistory fileHistory = historyForName(name)
            .orElseThrow(() -> new FatalException("No history for: " + name));

        // Ignore if no change
        if(getStore().exists(path) && Optional.of(getStore().readString(path).orElse(""))
            .filter(oldContent -> oldContent.equals(content)).isPresent()) return;

        rethrow(() -> getStore().write(path, content),
            ex -> new FatalException("Unable to write file " + name, ex));

        log.info(username + " stores file " + name);
        fileHistory.addNewVersion(path, now(), username, CHANGED, message);
        updateServicesForChangedContent(name);
    }

    public String getContentsOf(String name) {
        return getContentsOfOpt(name)
            .orElseThrow(() -> new NotFoundException("No data for path: " + name));
    }
    public String getContentsOf(String name, int version) {
        return getContentsOfOpt(name, version)
            .orElseThrow(() -> new NotFoundException("No data for version " + version + " of path: " + name));
    }
    public Optional<String> getContentsOfOpt(String name) {
        return pathForCurrentOrHistoryExistingName(name)
            .flatMap(path -> getStore().readString(path));
    }
    public Optional<String> getContentsOfOpt(String name, int version) {
        return historyForName(name)
            .map(h -> h.getContentOfVersion(version));
    }

    public void rename(String name, String newName, String username) {
        if(name.equals(newName)) return;
        pathForCurrentExistingName(name)
            .flatMap(p -> {
                final String path = p;
                final String newPath = pathForCurrentNewName(newName)
                    .filter(fp -> !getStore().exists(fp)) // cannot rename to existing path
                    .orElseThrow(() -> new FatalException("Illegal new path: " + newName));
                return noThrow(() -> {
                    final String oldHistoryPath = historyForName(name).orElseThrow().directory;
                    final String newHistoryPath = historyForName(newName).orElseThrow().directory;
                    getStore().move(oldHistoryPath, newHistoryPath); // first move history so moving the file won't be detected as a delete by the filesystem watcher
                    getStore().move(path, newPath);
                    historyForName(newName)
                        .ifPresent(fileHistory ->
                            fileHistory.addNewVersion(newPath, now(), username, RENAMED, null)
                        );
                    log.info("User {} renamed settings file from {} to {}", username, name, newName);
                    return newPath;
                });
            })
            .orElseThrow(() -> new FatalException("Unable to rename from '" + name + "' to '" + newName + "'")); // NOSONAR -- not using result
        sendConfigurationRefreshEventTo(name, newName);
    }
    public void delete(String name, String username) {
        pathForCurrentExistingName(name)
            .map(path -> getStore().remove(path))
            .filter(result -> {
                log.info("User {} deleted {}", username, name);
                historyForName(name)
                    .ifPresent(fileHistory ->
                        fileHistory.addNewVersion(pathForCurrentNewName(name).orElseThrow(), now(), username, DELETED, null)
                    );
                updateAllServicesForChangedContent();
                return true;
            })
            .orElseThrow(() -> new FatalException("Unable to delete: " + name)); // NOSONAR -- not using result
    }
    public void restore(String name, String username, Optional<Integer> version) {
        final String restoredFilePath = pathForCurrentNewName(name).orElseThrow(() -> new FatalException("Unable to restore invalid file " + name));
        final FileHistory fileHistory = historyForName(name)       .orElseThrow(() -> new FatalException("Unable to restore because no history for " + name));

        final int useVersion = version.orElse(fileHistory.getLatestVersionNumber() - 1);
        if(useVersion >= fileHistory.getLatestVersionNumber()) return; // latest version is current so nothing to restore

        final FileVersion fileVersion = fileHistory.getFileVersion(useVersion);
        if(fileVersion.actionType != CHANGED && fileVersion.actionType != RESTORED) throw new FatalException("Unable to restore from version type " + fileVersion.actionType);

        final String content = fileHistory.getContentOf(fileVersion);
        rethrow(() -> getStore().write(restoredFilePath, content), ex -> new FatalException("Unable to write to file " + name + ": " + ex.getMessage()));
        fileHistory.addNewVersion(restoredFilePath, now(), username, RESTORED, null);
        log.info("User {} restored {}", username, name);
        updateServicesForChangedContent(name);
    }

    public List<FileVersion> getVersions(String name) {
        return historyForName(name)
            .map(FileHistory::list)
            .orElseGet(Collections::emptyList);
    }
    public FileVersion getLatestVersion(String name) {
        return historyForName(name).orElseThrow(() -> new NotFoundException("Unknown name: " + name)).getLatestVersion();
    }

    public PropsMap getCombinedSettings(UUID serviceInstanceId, ServiceId serviceId, ImmutableList<String> profiles) {
        final SettingsCollector.OrderedSettings orderedSettings = new SettingsCollector(this).getOrderedSettings(serviceId, profiles);

        if(!UUID_ZERO.equals(serviceInstanceId)) addServiceDependencies(serviceInstanceId, serviceId, orderedSettings.files);
        return orderedSettings.settings;
    }

    public ImmutableList<ServiceId> getServicesUsingFile(String path) {
        final String name = path.replace("^/", "");
        return settingsDependenciesPerServiceInstance.get().entrySet().stream() // Entry<ServiceRef, ImmutableSet<String>>
            .filter(entry -> entry.getValue().contains(name))
            .map(entry -> entry.getKey().serviceId)
            .distinct()
            .collect(ImmutableUtil.toImmutableList());
    }

    public void updateServiceForChangeContent(ServiceId id) {
        Threads.execute(() ->
            getAllRunningServices().stream()
                .filter(serviceRef -> serviceRef.serviceId.equals(id))
                .forEach(this::sendConfigurationRefreshEventTo)
        );
    }
    public void updateAllServicesForChangedContent() {
        debounce(Duration.ofSeconds(2), () -> {
            log.info("Update all services for change");
            Threads.execute(() ->
                getAllRunningServices().forEach(this::sendConfigurationRefreshEventTo)
            );
        });
    }

    public List<String> getAllHistoryNames() {
        return getStore().list(HISTORY_DIR_NAME, /*recursive=*/true).stream()
            .filter(item -> item.getFilename().matches("^\\d+-.*$")) // try to get only version history files and ignore the rest
            .map(item -> item.path)
            .filter(not(IOUtils::isProbablyTempFile))
            .sorted()
            .toList();
    }

    public Mono<Set<String>> syncWithSettingsFromOtherStars() {
        return settings.get().syncBetweenStars ? syncer.syncWithSettingsFromOtherStars() : Mono.empty();
    }

    /** This method only exists for overloading in tests so the time is predictable */
    protected Instant now() {
        return Instant.now();
    }

    private CompletableFuture<Void> syncSettingsBetweenStars() {
        final CompletableFuture<Void> doneFuture = new CompletableFuture<>();
        if(settings.get().syncBetweenStars) {
            Threads.execute(Duration.ofSeconds(5), () -> {
                syncWithSettingsFromOtherStars()
                    .map(changes -> tellAllOtherSettingsServicesToSync())
                    .onErrorResume(e -> {
                        log.warn("Failed to sync: {}", Throwables.getRootCause(e).getMessage());
                        return Mono.empty();
                    })
                    .block();
                doneFuture.complete(null);
            });
        } else {
            doneFuture.complete(null);
        }
        return doneFuture;
    }
    Flux<RelayResponse<Set<String>>> tellAllOtherSettingsServicesToSync() {
        if(!settings.get().syncBetweenStars) return Flux.empty();
        return dispatcher.relay(RelayRequest
            .forPost("microstar-settings")
            .servicePath("sync")
            .excludeLocalStar()
            .build(), new ParameterizedTypeReference<>() {})
            ;
    }


    private void addNewVersionIfMissing(DataStore.Item item) {
        final String historyDirName = concatPath(HISTORY_DIR_NAME, item.path) + "/";
        if(getStore().list(historyDirName).isEmpty()) {
            log.info("Creating history for new detected file: {}", item.path);
            final FileHistory fileHistory = new FileHistory(historyDirName);
            fileHistory.addNewVersion(item.path, item.time, "init", RESTORED, "Copied into missing history");
        }
    }
    private void addServiceDependencies(UUID serviceInstanceId, ServiceId serviceId, ImmutableSet<String> touchedFiles) {
        settingsDependenciesPerServiceInstance.set(
                ImmutableMap.<ServiceRef, ImmutableSet<String>>builder()
                    .putAll(settingsDependenciesPerServiceInstance.get())
                    .put(new ServiceRef(serviceInstanceId, serviceId), touchedFiles)
                    .buildKeepingLast()
            );
    }

    Optional<String> pathForCurrentNewName(String name) { return pathForCurrentName(name, /*mustExist=*/false); }
    Optional<String> pathForCurrentExistingName(String name) { return pathForCurrentName(name, /*mustExist=*/true); }
    Optional<String> pathForCurrentName(String name, boolean mustExist) {
        return Optional.of(getStore().normalizePath(CURRENT_DIR_NAME, name))
            .filter(path -> path.startsWith("/" + CURRENT_DIR_NAME)) // name may hold ../../..
            .filter(path -> !mustExist || getStore().exists(getStore().normalizePath(path)))
            .map(path -> !getStore().isDir(path) || path.endsWith("/") ? path : (path + "/"));
    }
    Optional<String> pathForHistoryExistingName(String name) {
        return Optional.of(getStore().normalizePath(CURRENT_DIR_NAME, name))
            .filter(path -> path.startsWith("/" + CURRENT_DIR_NAME))
            .filter(path -> getStore().exists(path));
    }
    Optional<String> pathForCurrentOrHistoryExistingName(String name) {
        return pathForCurrentName(name, /*mustExist=*/true).or(() -> pathForHistoryExistingName(name));
    }

    Optional<FileHistory> historyForName(String name) {
        return pathForCurrentNewName(name) // file may be deleted but still have history
            .map(path -> {
                final String relPath = path.substring(1 + CURRENT_DIR_NAME.length() + 1);
                return new FileHistory(concatPath(HISTORY_DIR_NAME, relPath));
            });
    }

    BlockingDataStore getStore() { return BlockingDataStore.forStore(dataStoreRef); }
    private Stream<DataStore.Item> streamListing(String name, boolean recursive) {
        return noCheckedThrow(() -> dataStoreRef.get().list(name, recursive).get()).stream();
    }

    /**
     * This method should be called when the datastore is updated by an external change.
     * Such an external change could be a filesystem listener that detects a filesystem
     * change. The sync is not an external change, as it takes care of versioning there.
     * At the time of this writing, the FileSystemDataStore does not have a file system
     * listener as to be as similar to DB as possible. Later such a listener may be added.
     */
    public void handleDataStoreChange(String name) {
        final String path = name.replaceFirst("^/+","").startsWith(CURRENT_DIR_NAME) ? name : concatPath(CURRENT_DIR_NAME, name);
        if(IOUtils.isProbablyTempFile(path)) return;
        final BlockingDataStore store = getStore();
        final String relPath = store.normalizePath(path).replaceFirst("^/" + CURRENT_DIR_NAME + "/+", "/");

        final String historyDirName = concatPath(HISTORY_DIR_NAME, relPath) + "/";
        final FileHistory fileHistory = new FileHistory(historyDirName);

        setLatestVersion(fileHistory);

        final boolean isDeleted = !store.exists(path);
        log.info("Handling datastore change at " + relPath + (isDeleted ? " (deleted)" : ""));

        // When a file is renamed, the old filename will appear here as a deleted file.
        // Detect that is renamed instead of deleted by checking if history for the 'deleted' file exists.
        if(fileHistory.getLatestVersionNumber() == 0 && isDeleted) return;

        final Optional<String> oldContent = noThrow(() -> fileHistory.getContentOf(fileHistory.getLatestVersion()));
        final Optional<String> newContent = store.readString(path);

        if(oldContent.orElse("").equals(newContent.orElse(""))) return; // no change, for example when change is from here (like store())
        if(isDeleted && fileHistory.getLatestVersion().actionType == DELETED) return; // no change: was already deleted

        final Instant lastModifiedUtc = store.getLastModified(store.normalizePath(CURRENT_DIR_NAME, relPath))
            .orElseGet(Instant::now);
        final String user = "filesystem";
        fileHistory.addNewVersion(path, lastModifiedUtc, user, isDeleted ? DELETED : CHANGED, null);

        miniBus.post(MiniBus.Event.builder()
            .topic("DataStoreChanged")
            .name(DATASTORE_NAME)
            .message(relPath)
            .build());

        updateServicesForChangedContent(path);
    }

    private void setLatestVersion(FileHistory fileHistory) {
        final BlockingDataStore store = getStore();
        final boolean noLatestVersion = fileHistory.getLatestVersion().version == 0;

        store.list(fileHistory.directory).stream()
            .sorted(Comparator.comparing(item -> item.path, VersionComparator.OLDEST_TO_NEWEST))
            .forEach(item -> {
                final String[] parts = item.getFilename().split("-");
                final int itemVersion = noThrow(() -> Integer.parseInt(parts[0])).orElse(0);

                if(noLatestVersion || fileHistory.getLatestVersionNumber() < itemVersion) {
                    fileHistory.setLatestVersion(itemVersion, item.time, "sync", parts.length > 3 && "DELETED".equals(parts[3]) ? DELETED : CHANGED, "");
                }
            });
    }

    private void updateServicesForChangedContent(String path) {
        final String normPath = getStore().normalizePath(path);
        sendConfigurationRefreshEventTo(normPath.substring(1));
    }
    private void sendConfigurationRefreshEventTo(String... settingsNames) {
        getServicesForNames(settingsNames).forEach(this::sendConfigurationRefreshEventTo);
    }

    private void sendConfigurationRefreshEventTo(ServiceRef serviceRef) {
        Threads.execute(() -> { // when this is called from a request, blocking is not allowed
            try {
                log.info("Sending refresh event to {} instance {}", serviceRef.serviceId, serviceRef.instanceId);
                dispatcher.sendConfigurationRefreshEvent(serviceRef.instanceId);
            } catch (final Exception failure) {
                log.warn("Failure refreshing instances: {}", failure.getMessage());
            }
        });
    }

    private ImmutableSet<ServiceRef> getServicesForNames(String... names) {
        try {
            return settingsDependenciesPerServiceInstance.get().entrySet().stream()
                .filter(entry -> Stream.of(names).anyMatch(name -> entry.getValue().contains(name)))
                .map(Map.Entry::getKey) // serviceRef
                .sorted(SettingsRepository::compareServices)
                .peek(serviceRef -> log.info("Depends on {}: {}", names, serviceRef.serviceId))
                .collect(ImmutableUtil.toImmutableSet());
        } catch(final Exception failure) {
            log.warn("Failure finding instances to notify: {}", failure.getMessage());
            return ImmutableUtil.emptySet();
        }
    }
    private ImmutableSet<ServiceRef> getAllRunningServices() {
        try {
            return dispatcher.getServiceInstanceIds().entrySet().stream()
                .map(entry -> new ServiceRef(entry.getKey(), entry.getValue()))
                .sorted(SettingsRepository::compareServices)
                .collect(ImmutableUtil.toImmutableSet());
        } catch(final Exception failure) {
            log.warn("Failure finding instances to notify: {}", failure.getMessage());
            return ImmutableUtil.emptySet();
        }
    }

    private static int compareServices(ServiceRef serviceA, ServiceRef serviceB) {
        // Dispatcher last
        if("dispatcher".equalsIgnoreCase(serviceA.serviceId.name)) return  1;
        if("dispatcher".equalsIgnoreCase(serviceB.serviceId.name)) return -1;
        return serviceA.serviceId.name.compareTo(serviceB.serviceId.name);
    }
}
