package net.microstar.dispatcher.controller;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.datastore.FailingDataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.spring.ContentTypes;
import net.microstar.spring.DataStores;
import net.microstar.spring.DataStores.DataStoreChange;
import net.microstar.spring.WildcardParam;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertyRef;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.EventEmitter.ServiceEvent;
import net.microstar.spring.webflux.util.FluxUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.lang.Boolean.TRUE;
import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;

@Slf4j
@RestController
@RequestMapping(value = "/datastore", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiresRole({ROLE_ADMIN, ROLE_SERVICE})
@RequiredArgsConstructor
public class DataStoreController {
    private final EventEmitter eventEmitter;
    private final DynamicPropertyRef<Map<String,Object>> configuration = DynamicPropertyRef.<Map<String,Object>>of("microstar.dataStores", new ParameterizedTypeReference<>(){})
        .withDefault(Collections.emptyMap());

    @PostConstruct void init() {
        configuration.onChange(rawMap -> eventEmitter.next("DATA-STORES-CHANGED"));
    }

    @GetMapping
    public List<String> getStoreNames() {
        return configuration.get().keySet().stream().sorted().toList();
    }

    @GetMapping("/{storeName}/get/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> get(@PathVariable("storeName") String storeName, @WildcardParam String path) {
        return Mono.fromFuture(getStore(storeName).readStream(path))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(FluxUtils::fluxFrom)
            .map(flux -> ResponseEntity
                .ok()
                .contentType(ContentTypes.mediaTypeOfName(path))
                .body(flux)
            );
    }

    @GetMapping("/{storeName}/list/**")
    public Mono<List<DataStore.Item>> list(@PathVariable("storeName") String storeName, @WildcardParam String path, @RequestParam(value="recursive", defaultValue = "false") boolean recursive) {
        return Mono.fromFuture(getStore(storeName).list(path, recursive));
    }

    @PostMapping("/{storeName}/copy/**") // /datastore/storeA/copy/some/path?targetStore=storeB&targetPath=/some/path
    public UUID copy(@PathVariable("storeName") String sourceStoreName, @WildcardParam String sourcePath,
                     @RequestParam("targetStore") String targetStoreName, @RequestParam("targetPath") String targetPath) {
        log.info("Copy from {}/{} to {}/{}", sourceStoreName, sourcePath, targetStoreName, targetPath);

        return DataStoreUtils.copy(getStore(sourceStoreName), "/" + sourcePath, getStore(targetStoreName), targetPath, progressInfo -> {
            eventEmitter.next(new ServiceEvent<>("DATA-STORE-PROGRESS", progressInfo));
            emitChangeFor(targetStoreName, targetPath);
        });
    }

    @PostMapping("/stop/{id}")
    public void stopCopy(@PathVariable("id") UUID id) {
        DataStoreUtils.stopCopy(id);
    }

    @PostMapping("/{storeName}/delete/**")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("storeName") String storeName, @WildcardParam String pathToDelete) {
        return Mono.fromFuture(getStore(storeName).remove(pathToDelete))
            .map(success -> ResponseEntity.ok().build());
    }

    @PostMapping("/{storeName}/rename/**") // /datastore/storeA/rename/some/path?newPath=/some/other/path
    public Mono<ResponseEntity<Void>> rename(@PathVariable("storeName") String storeName, @WildcardParam String path,
                                             @RequestParam("to") String newPath) {
        return Mono.fromFuture(getStore(storeName).move(path, newPath))
            .map(success -> (TRUE == success ? ResponseEntity.ok() : ResponseEntity.status(404)).build());
    }

    @PostMapping("/{storeName}/createDir/**")
    public Mono<ResponseEntity<Void>> createDir(@PathVariable("storeName") String storeName, @WildcardParam String pathToCreate) {
        return Mono.fromFuture(getStore(storeName).write(IOUtils.concatPath(pathToCreate, "dummy.txt"),
                "Dummy file created because empty directories are not possible"))
            .map(success -> ResponseEntity.ok().build());
    }

    @PostMapping(value ="/{storeName}/upload/**", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Void> handleUpload(@PathVariable("storeName") String storeName, @WildcardParam String targetPath, ServerWebExchange exchange) {
        final DataStore store = getStore(storeName);
        final Path tempFile = noCheckedThrow(() -> Files.createTempFile("uploading.", ".temp"));
        final List<String> paths = new ArrayList<>();
        return exchange.getMultipartData()
            .flatMapIterable(map -> map.toSingleValueMap().values())
            .filter(FilePart.class::isInstance)
            .map(FilePart.class::cast)
            .flatMap(file -> {
                noThrow(() -> IOUtils.del(tempFile));
                return file.transferTo(tempFile)
                    .then(Mono.defer(() -> {
                        final String path = IOUtils.concatPath(targetPath, file.filename());
                        final InputStream fileIn = noCheckedThrow(() -> new FileInputStream(tempFile.toFile()));
                        return Mono.fromFuture(store.write(path, fileIn))
                            .doOnSuccess(b -> noThrow(fileIn::close))
                            .map(b -> path);
                    }));
            })
            .doOnNext(paths::add)
            .doOnComplete(() -> emitChangeFor(storeName, paths))
            .doFinally(s -> noThrow(() -> IOUtils.del(tempFile)))
            .then();
    }


    private DataStore getStore(String storeName) {
        final DataStore store = DataStores.get(storeName).get();
        if (store instanceof FailingDataStore) throw new NotFoundException("Unknown dataStore: " + storeName);
        return store;
    }
    private void emitChangeFor(String storeName, String path) {
        eventEmitter.next(new ServiceEvent<>("DATA-STORE-CHANGED", DataStoreChange.builder().name(storeName).path(path).build()));
    }
    private void emitChangeFor(String storeName, List<String> paths) {
        final List<String> parentPaths = paths.stream().map(path -> path.replaceAll("/[^/]+/?$","")).distinct().sorted().toList();
        eventEmitter.next(new ServiceEvent<>("DATA-STORE-CHANGED", DataStoreChange.builder().name(storeName).paths(parentPaths).build()));
    }
}
