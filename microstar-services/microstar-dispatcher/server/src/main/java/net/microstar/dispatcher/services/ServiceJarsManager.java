package net.microstar.dispatcher.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.CollectionUtils;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.controller.StarController.JarStarInfo;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.ServiceInfo;
import net.microstar.dispatcher.model.ServiceInfoJar;
import net.microstar.spring.DataStores;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.exceptions.IllegalInputException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.EventEmitter.ServiceEvent;
import net.microstar.spring.webflux.MiniBus;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Type;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static net.microstar.common.util.CollectionUtils.disjunctionLeft;
import static net.microstar.common.util.CollectionUtils.disjunctionRight;
import static net.microstar.common.util.ThreadUtils.debounceFuture;
import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.ADDED;
import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.ALL;
import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.DELETED;
import static net.microstar.dispatcher.model.DispatcherProperties.StarsProperties.JarSyncType.RUNNING;

@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings({"squid:S5411"/*Boolean autoboxing*/, "squid:S2065"/*transient and not serializable*/})
public class ServiceJarsManager {
    private final Services services;
    private final StarsManager starsManager;
    private final EventEmitter eventEmitter;
    private final ObjectMapper objectMapper;
    private final MiniBus miniBus;
    private final AtomicReference<List<DynamicReferenceNotNull<DataStore>>> jarSources = new AtomicReference<>(Collections.emptyList());
    private final AtomicReference<List<JarInfo>> knownJars = new AtomicReference<>(new ArrayList<>());
    @SuppressWarnings("this-escape")
    private final DynamicPropertiesRef<DispatcherProperties> props = DynamicPropertiesRef
        .of(DispatcherProperties.class)
        .onChange((newProps, changes) -> updateJarSources(changes));
    private Future<Void> jarsPolling = CompletableFuture.completedFuture(null);

    @Builder @EqualsAndHashCode @ToString
    public static class JarInfo {
        public final transient DynamicReferenceNotNull<DataStore> store; // not included in equals and hash
        public final String name;
    }

    @PostConstruct void init() {
        updateJarSources(Set.of("jars.stores"));
        starsManager.onAddedStar(star -> {
            if(props.get().stars.syncJars.contains(ALL)) copyJarsFromOtherStar(star);
        });
        eventEmitter.<ServiceInfo>onEmit("SERVICE-STARTING", event -> event.data.jarInfo.ifPresent(this::jarWasStarted));
        miniBus.subscribe("DataStoreChanged", event -> { if(props.get().jars.stores.contains(event.name)) checkJars(); });
    }
    @PreDestroy void close() {
        jarsPolling.cancel(false);
        props.removeChangeListeners();
    }

    public ImmutableList<JarInfo> getJars() { return ImmutableList.copyOf(knownJars.get()); }
    public Optional<JarInfo> getJar(ServiceId serviceId) {
        return knownJars.get().stream()
            .filter(jarInfo -> jarInfo.name.equals(serviceId.combined + ".jar"))
            .findFirst();
    }
    public Optional<JarInfo> getJar(String name) {
        return knownJars.get().stream()
            .filter(jarInfo -> jarInfo.name.equals(name.replace("/","-"))
                            || jarInfo.name.equals(name.replace("/","-") + ".jar"))
            .findFirst();
    }
    public Mono<Void> addJar(Mono<FilePart> filePart) {
        return filePart
            .doOnNext(fp -> log.info("Received jar file: {}", fp.filename()))
            .filter(fp -> {
                if(fp.filename().matches("^.*[/\\\\|:].*$")) {
                    log.warn("REJECTED received file because illegal characters in filename: {}", fp.filename());
                    throw new IllegalInputException("Invalid jar filename: " + fp.filename());
                }
                if(!fp.filename().endsWith(".jar")) {
                    log.warn("REJECTED received file because name should end with .jar: {}", fp.filename());
                    throw new IllegalInputException("Invalid jar filename: " + fp.filename());
                }
                if(getJar(fp.filename()).isPresent()) {
                    log.warn("REJECTED received jar because already exists: {}", fp.filename());
                    throw new IllegalInputException("Jar already exists on this star: " + fp.filename());
                }
                return true;
            })
            .flatMap(fp -> {
                final Path tempFile = createTempDownloadFile();
                return fp.transferTo(tempFile)
                    .then(Mono.defer(() -> Mono.fromFuture(DataStoreUtils.copy(tempFile, getDefaultStore(), fp.filename()))))
                    .doFinally(sig -> IOUtils.del(tempFile));
            })
            .then();
    }

    public void deleteJar(String jarToDelete) {
        getJarInfo(jarToDelete)
            .ifPresentOrElse(jarInfo -> {
                log.info("Deleting jar: {}", jarToDelete);

                // Deletion will fail on Windows when jar is currently running.
                // To work around that for now, the admin should stop the service first.
                //
                jarInfo.store.get().remove(jarInfo.name)
                    .thenCompose(wasRemoved -> wasRemoved
                        ? jarInfo.store.get().exists(jarInfo.name)
                        : CompletableFuture.completedFuture(false))
                    .thenAccept(wasRemoved -> {
                        if(!wasRemoved) throw new FatalException("Unable to delete " + jarInfo.name);
                        jarWasRemoved(jarInfo, /*informOtherStars=*/true);
                    });
            }, () ->
                log.warn("Did not delete unknown jar: {}", jarToDelete)
            );
    }
    public void deleteJar(JarInfo jarToDelete) {
        deleteJar(jarToDelete.name); // this jarInfo may refer to a different store which is ignored here
    }


    private DataStore getDefaultStore() {
        return getDefaultStoreRef().get();
    }
    private DynamicReferenceNotNull<DataStore> getDefaultStoreRef() {
        return DataStores.get("jars");
    }

    private void updateJarSources(Set<String> changes) {
        if(changes.contains("jars") || changes.contains("jars.stores")) updateJarSources();
    }
    void updateJarSources() {
        final List<String> storeNames = props.get().jars.stores;

        jarSources.set(storeNames.stream().map(DataStores::get).toList());
        jarSources.get().forEach(ref -> ref.whenChanged(store -> checkJars()));
        jarsPolling.cancel(false);
        jarsPolling = Threads.executePeriodically(props.get().jars.pollPeriod, true, this::checkJars);
    }

    private CompletableFuture<Void> checkJars() {
        class Local {
            private Local() {}
            public static final Duration DEBOUNCE_TIME = Duration.ofMillis(250);
        }
        return debounceFuture(Local.DEBOUNCE_TIME, () -> collectAllJars().thenAcceptAsync(newJars -> {
                synchronized (knownJars) {
                    final Set<JarInfo> addedJars = disjunctionRight(knownJars.get(), newJars);
                    final Set<JarInfo> removedJars = disjunctionLeft(knownJars.get(), newJars);

                    addedJars.forEach(jar -> jarWasAdded(jar, /*informOtherStars:*/true));
                    removedJars.forEach(jar -> jarWasRemoved(jar, /*informOtherStars:*/true));
                }
        }));
    }

    private CompletableFuture<List<JarInfo>> collectAllJars() {
        return jarSources.get().stream()
                .map(dataStoreRef -> list(dataStoreRef.get())
                    .thenApply(names -> names.stream()
                        .filter(ServiceJarsManager::isJar)
                        .map(name -> JarInfo.builder().name(name).store(dataStoreRef).build()).toList())
                )
                .reduce((fut1, fut2) -> fut1.thenCombine(fut2, CollectionUtils::concat))
                .orElseGet(() -> CompletableFuture.completedFuture(Collections.emptyList()));
    }
    private CompletableFuture<List<String>> list(DataStore store) {
        try { return store.listNames("/"); }
        catch(final RuntimeException e) { return CompletableFuture.completedFuture(Collections.emptyList()); }
    }

    private Optional<JarInfo> getJarInfo(String jarName) {
        return knownJars.get().stream()
            .filter(jarInfo -> jarInfo.name.equals(jarName))
            .findFirst();
    }


    private void jarWasAdded(JarInfo jarInfo, boolean informOtherStars) {
        if (getJar(jarInfo.name).isPresent()) return;
        knownJars.get().add(jarInfo);

        if(!isWatchdog(jarInfo)) {
            final ServiceId serviceId = ServiceId.of(jarInfo.name);
            final ServiceVariations variations = services.getOrCreateServiceVariations(serviceId);
            if (!variations.isKnownService(serviceId)) {
                services.register(new ServiceInfoJar(serviceId, jarInfo));
            }
        }
        if(informOtherStars) sendJarEventToOtherStars(jarInfo, "star-added-jar");
        sendJarEventToDashboard(jarInfo, "ADDED");
    }
    private void jarWasRemoved(JarInfo jarInfo, boolean informOtherStars) {
        if (getJarInfo(jarInfo.name).isEmpty()) return;
        knownJars.get().remove(jarInfo);

        final ServiceId serviceId = ServiceId.of(jarInfo.name);

        final ServiceVariations variations = services.getOrCreateServiceVariations(serviceId);
        if(variations.isKnownService(serviceId)) {
            variations.removeAllDormants(serviceId);
        }
        if(informOtherStars) sendJarEventToOtherStars(jarInfo, "star-removed-jar");
        sendJarEventToDashboard(jarInfo, "REMOVED");
    }
    public void jarWasStarted(JarInfo jarInfo) {
        sendJarEventToOtherStars(jarInfo, "star-started-jar");
    }

    private static boolean isJar(String name) {
        return name.toLowerCase(Locale.ROOT).endsWith(".jar");
    }
    private static boolean isWatchdog(JarInfo jarInfo) {
        return jarInfo.name.toLowerCase(Locale.ROOT).contains("watchdog");
    }

    @Jacksonized @Builder
    public static class JarEvent {
        public final String name;
    }
    private void sendJarEventToDashboard(JarInfo jarInfo, String type) {
        eventEmitter.next(new ServiceEvent<>(type + "-JAR", JarEvent.builder().name(jarInfo.name).build()));
    }
    private void sendJarEventToOtherStars(JarInfo jarInfo, String type) {
        Threads.execute(() -> starsManager.sendEventToOtherStars("", type + "/" + jarInfo.name).block());
    }

    /** Some other star added a jar. If this star doesn't have that jar yet and syncAddedJars is enabled, get it */
    public void anotherStarAddedJar(String starName, String jarName) {
        if(getJar(jarName).isEmpty() && isSyncJars(ADDED, ALL)) {
            log.info("star {} added jar: {} -- The jar will be copied here (syncJars ADDED or ALL)", starName, jarName);
            Threads.execute(() -> {
                try {
                    downloadJar(starName, jarName).block();
                } catch(final Exception e) {
                    log.warn("Failed to download jar that was added on star {}: {}", starName, e, e);
                }
            });
        }
    }
    /** Some other star removed a jar. If this star has that jar and syncRemovedJars is enabled, remove it here as well */
    public void anotherStarRemovedJar(String starName, String jarName) {
        if(getJar(jarName).isPresent() && isSyncJars(DELETED)) {
            log.info("star {} removed jar: {} -- The jar will be removed here as well (syncJars DELETED)", starName, jarName);
            Threads.execute(() -> removeJar(jarName));
        }
    }
    public void anotherStarStartedJar(String starName, String jarName) {
        if(getJar(jarName).isEmpty() && isSyncJars(RUNNING)) {
            log.info("star {} started jar: {} -- The jar will be copied here (syncJars RUNNING)", starName, jarName);
            Threads.execute(() -> downloadJar(starName, jarName).block());
        }
    }

    public Mono<Void> downloadJar(String starName, String jarName) {
        if(getJar(jarName).isPresent()) {
            log.warn("Ignored request for download because already exists: {}", jarName);
            return Mono.empty();
        }
        log.info("Download jar {} from star {}" ,jarName, starName);
        final Optional<WebClient.ResponseSpec> response = starsManager.getResponseFor(starName, RelayRequest
                .forGet()
                .star(starName)
                .servicePath("jars/" + jarName)
                .build());
        if(response.isEmpty()) {
            log.info("Failed to download jar {} from star {} -- no data", jarName, starName);
            return Mono.empty();
        }
        final Flux<DataBuffer> downloadedData = response.get()
            .bodyToFlux(DataBuffer.class);
        final Path tempFile = createTempDownloadFile();

        return DataBufferUtils.write(downloadedData, tempFile, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            .publishOn(Schedulers.boundedElastic()) // in case IO (like the move) is slow
            .then(Mono.defer(() -> Mono.fromFuture(DataStoreUtils.copy(tempFile, getDefaultStore(), jarName)))
                .doOnSuccess(unused -> {
                    final JarInfo addedJar = JarInfo.builder().name(jarName).store(getDefaultStoreRef()).build();
                    jarWasAdded(addedJar, /*informOtherStars=*/false);
                    log.info("Jar was imported: {}", jarName);
                })
                .doOnError(error -> log.warn("Download error: {}", error.toString()))
                .doFinally(sig -> IOUtils.del(tempFile))
            ).then();
    }

    public Mono<List<JarStarInfo>> getAvailableJarsOnStars() {
        return starsManager.relay(RelayRequest.forGet("")
                .servicePath("jar").build())
            .map(resp -> resp.content.map(bytes -> convert(bytes, new ParameterizedTypeReference<List<String>>() {})).orElse(Collections.emptyList()).stream()
                    .map(n -> JarStarInfo.builder().name(n).star(resp.starName).build())
                    .toList())
                .concatMap(names -> Flux.fromStream(names.stream()))
                .sort()
                .distinct()
                .collectList()
            ;
    }

    private void copyJarsFromOtherStar(Star star) {
        Threads.execute(() ->
            getListOfJarsFromStar(star)
                .filter(jarInfo -> getJar(jarInfo.name).isEmpty())
                .flatMap(jarInfo -> downloadJar(jarInfo.star, jarInfo.name))
                .collectList()
                .block()
        );
    }
    private Flux<JarStarInfo> getListOfJarsFromStar(Star star) { return getListOfJars(RelayRequest.forGet("all-jars").star(star.name).build()); }
    private Flux<JarStarInfo> getListOfJars(RelayRequest request) {
        return starsManager.relay(request)
            .flatMap(resp -> {
                try {
                    return Mono.just(objectMapper.readValue(resp.content.orElse("[]"), new TypeReference<List<JarStarInfo>>() {}));
                } catch (JsonProcessingException e) {
                    log.warn("Unable to handle /all-jars result: {}", e.getMessage());
                    return Mono.empty();
                }
            })
            .flatMapIterable(list -> list)
            .distinct();
    }

    private <T> T convert(String textToConvert, ParameterizedTypeReference<T> type) {
        final TypeReference<Object> responseTypeRef = new TypeReference<>(){
            @Override public Type getType() { return type.getType(); }
        };
        try {
            //noinspection unchecked
            return (T)objectMapper.readValue(textToConvert, responseTypeRef);
        } catch (JsonProcessingException e) {
            throw new FatalException("Unable to convert data to type " + type);
        }
    }

    private void removeJar(String jarName) {
        getJarInfo(jarName).ifPresent(jarInfo -> {
            knownJars.get().remove(jarInfo);
            jarInfo.store.get().remove(jarName);
            jarWasRemoved(jarInfo, /*informOtherStars=*/false);
        });

    }
    private boolean isSyncJars(DispatcherProperties.StarsProperties.JarSyncType... types) {
        return Arrays.stream(types).anyMatch(type -> props.get().stars.syncJars.contains(type));
    }

    private static Path createTempDownloadFile() {
        return IOUtils.createAndDeleteTempFile("download_" ,".tmp"); // download target should be unique a ndnot exist (no overwrite)
    }
}
