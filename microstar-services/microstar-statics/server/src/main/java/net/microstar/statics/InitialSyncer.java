package net.microstar.statics;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.spring.DataStores;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService.StarInfo;
import net.microstar.spring.webflux.util.FluxUtils;
import net.microstar.statics.model.OverviewItem;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.statics.DataService.DATASTORE_NAME;

/**
 * Synchronize the file systems with other microstar-statics services.
 * This full synchronization is only performed at registration time.<p></p>
 *
 * Possible states:<p></p>
 *
 * NEWER HERE<br>
 * This service may have newer files that were not yet synchronized (e.g.
 * because the file system was updated while this service was not running)
 * and need to be downloaded by the other services.<p></p>
 *
 * NEWER THERE<br>
 * Other services may have newer files that need to be downloaded here.<p></p>
 *
 * NOT HERE<br>
 * Files may have been deleted here that were not yet synchronized (e.g.
 * because the file system was updated while this service was not running)
 * and need to be deleted by the other services as well. Or a file
 * was added there while this star was offline. Here assume the latter.<p></p>
 *
 * NOT THERE<br>
 * Files may have been deleted on other services that need to be deleted
 * here as well. Or here a file may have been added. Here assume the latter.<p></p>
 *
 * KNOWN LIMITATION:<br>
 * Issue is that if a file is missing on one service it is unknown if that
 * service is behind or ahead. In other words: was the file deleted from A
 * or added to B? Because of time restraints this issue is left for later.
 * Result of this will be that deletions will be undone when another star
 * was not running while the deletions took place. Sync will put them back.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InitialSyncer {
    private final DynamicReferenceNotNull<DataStore> filesRoot = DataStores.get(DATASTORE_NAME);
    private final DynamicPropertiesRef<StaticsProperties> props = DynamicPropertiesRef.of(StaticsProperties.class);
    private final DispatcherService dispatcher;
    private final WebClient.Builder webClient;
    private final MicroStarApplication application;
    private final ObjectMapper objectMapper;

    @PostConstruct void init() {
        MicroStarApplication.get().ifPresentOrElse(app -> {
            log.info("InitialSyncer will run when registered");
            app.onRegistered(this::sync);
        }, () -> log.warn("InitialSyncer couldn't find MicroStarApplication"));
    }

    /* This sync will run when registered but can be called any other time as well -- won't do anything when syncBetweenStars is set to false */
    public void sync() {
        if(!props.get().syncBetweenStars) return;
        Threads.execute(() ->
            getListsFromOtherStars()
                .flatMap(this::sync)
                .doOnComplete(() -> log.info("Sync finished"))
                .subscribe());
    }

    @Builder @ToString
    private static class StarItems {
        public final StarInfo star;
        public final List<OverviewItem> items;
    }

    private Flux<StarItems> getListsFromOtherStars() {
        return dispatcher.getStarInfos()
            .flatMapMany(Flux::fromIterable)
            .filter(starInfo -> !starInfo.isLocal)
            .filter(starInfo -> starInfo.isActive)
            .doOnNext(starInfo -> log.info("Getting list for star: {} on {}", starInfo.name, starInfo.url))
            .switchMap(starInfo -> {
                final Flux<DataBuffer> listFlux = webClient.build()
                        .get()
                        .uri(IOUtils.concatPath(starInfo.url, "microstar-statics", "list"))
                        .headers(application::setHeaders)
                        .header(MicroStarConstants.HEADER_X_STAR_NAME, starInfo.name)
                        .retrieve()
                        .bodyToFlux(DataBuffer.class);
                // bodyToMono fails if the json length is > 256KB so do it ourselves.
                return FluxUtils.toString(listFlux)
                    .map(json -> noThrow(() -> objectMapper.readValue(json, new TypeReference<List<OverviewItem>>(){})).orElseThrow(() -> new IllegalStateException("Illegal list json")))
                    .onErrorContinue((ex, val) -> log.warn("Listing star {} failed: {} -- val: {}", starInfo.name, ex.getMessage(), val))
                    .onErrorResume(ex -> { log.error("Listing of star {} failed: {}", starInfo.name, ex.getMessage(), ex); return Mono.just(Collections.emptyList()); })
                    .map(list -> {
                        log.info("Received list of size {} from {}", list.size(), starInfo.name);
                        return StarItems.builder().star(starInfo).items(list).build();
                    })
                    .filter(starItems -> !starItems.items.isEmpty())
                    .onErrorContinue((ex, mono) -> log.warn("Continue on star listing failure: {}", ex.getMessage()))
                    ;
            });
    }
    private Flux<Void> sync(StarItems starItems) {
        final StarInfo otherStar = starItems.star;
        final List<OverviewItem> otherItems = starItems.items;
        final List<OverviewItem> localItems = Overview.list(/*includeCrc:*/true); // expensive!

        log.info("Synchronizing with microstar-statics on star '{}'", otherStar.name);
        final List<Mono<Void>> actions = new ArrayList<>();

        otherItems.forEach(otherItem -> {
            final Optional<OverviewItem> localItem = findItemForSameName(localItems, otherItem);

            // newer there -> download here
            // not here    -> download here
            if(localItem.isEmpty() || (otherItem.lastModified > localItem.get().lastModified && otherItem.crc != localItem.get().crc)) {
                if(otherItem.lastModified > 100) { // NOSONAR -- if inside if for readability
                    actions.add(download(otherStar, otherItem.path, otherItem.lastModified));
                }
            }

            // newer here -> tell other to update itself
            if(localItem.filter(li -> li.lastModified > otherItem.lastModified && li.crc != otherItem.crc).isPresent()) {
                actions.add(upload(otherStar.name, otherItem.path));
            }
        });

        // items missing on other were not in the previous block, so handle them here
        localItems.forEach(localItem -> {
            final Optional<OverviewItem> otherItem = findItemForSameName(otherItems, localItem);

            // not there  -> tell other to update itself
            if(otherItem.isEmpty()) {
                actions.add(upload(otherStar.name, localItem.path));
            }
        });
        log.info("{} actions on sync with star '{}'", actions.size(), otherStar.name);

        return Flux.fromIterable(actions)
            .flatMap(v -> v)
            .doOnComplete(() -> log.info("Synchronizing with star '{}' finished", otherStar.name))
            .onErrorContinue((ex, obj) -> log.warn("Failed: {} -- obj: {}", ex.getMessage(), obj));
    }

    private Mono<Void> download(StarInfo star, String pathNameIn, long lastModifiedLong) {
        final Instant lastModified =  Instant.ofEpochSecond(lastModifiedLong);
        final String path = pathNameIn.replace("\\","/");
        return logOnStart(() -> log.info("Downloading {} from star {}", path, star.name),
            webClient.build()
                .get()
                .uri(IOUtils.concatPath(star.url, "microstar-statics", path))
                .headers(application::setHeaders)
                .headers(httpHeaders -> httpHeaders.remove(UserToken.HTTP_HEADER_NAME))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .collectList()
                .publishOn(Schedulers.boundedElastic())
                .map(buffers -> {
                    final Path tempFile = IOUtils.createAndDeleteTempFile();
                    return DataBufferUtils.write(Flux.fromIterable(buffers), tempFile)
                        .then(Mono.defer(() -> Mono.fromFuture(
                            DataStoreUtils.copy(tempFile, filesRoot.get(), path)
                                .thenComposeAsync(ok -> filesRoot.get().touch(path, lastModified))
                        )))
                        .subscribe();
                })
                .onErrorResume(ex -> { log.error("download failed of {}: {}", path, ex.getMessage()); return Mono.empty(); })
            ).then();
    }
    private Mono<Void> upload(String starName, String pathNameIn) {
        final String pathName = pathNameIn.replace("\\","/");
        // actually not an upload but a tell-other-to-download
        return logOnStart(() -> log.info("Telling star {} to download {}", starName, pathName),
            dispatcher.relaySingle(RelayRequest
                .forPost()
                .servicePath(IOUtils.concatPath("/file-change/", pathName))
                .star(starName)
                .build(), String.class))
                .onErrorComplete(ex -> { log.error("upload failed of {}: {}", pathName, ex.getMessage()); return true; } )
                .then();
    }

    private static Optional<OverviewItem> findItemForSameName(List<OverviewItem> items, OverviewItem forItem) {
        return items.stream().filter(item -> forItem.path.equals(item.path)).findFirst();
    }
    private static <T> Mono<T> logOnStart(Runnable logger, Mono<T> mono) {
        return Mono.just(0).doOnNext(i -> logger.run()).then(mono);
    }
}
