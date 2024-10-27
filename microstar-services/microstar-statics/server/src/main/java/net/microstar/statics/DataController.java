package net.microstar.statics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.spring.DataStores;
import net.microstar.spring.WildcardParam;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import net.microstar.statics.model.OverviewItem;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static net.microstar.common.MicroStarConstants.HEADER_X_STAR_NAME;
import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;
import static net.microstar.statics.DataService.DATASTORE_NAME;

@Slf4j
@RestController()
@RequiresRole({ROLE_ADMIN, ROLE_SERVICE, "DEPLOYER"})
@RequiredArgsConstructor
public class DataController {
    private final List<CompletableFuture<Void>> propsUpdateFutures = new CopyOnWriteArrayList<>();
    private final DynamicPropertiesRef<StaticsProperties> props = DynamicPropertiesRef.of(StaticsProperties.class)
        .onChange(sp -> propsUpdateFutures.removeIf(fut -> { fut.complete(null); return true; }));
    private final DynamicReferenceNotNull<DataStore> filesRoot = DataStores.get(DATASTORE_NAME);
    private final UserTargetsConfiguration userTargetsConfiguration;
    private final DataService dataService;
    private final DispatcherService dispatcher;
    private final WebClient.Builder webClientBuilder;
    private final MicroStarApplication application;

    @GetMapping("/user-targets")
    public Map<String,List<String>> getUserTargets() {
        return ImmutableUtil.copyAndMutate(props.get().userTargets, map ->
            props.get().userGroups.keySet().forEach(userGroup -> map.computeIfAbsent(userGroup, key -> Collections.emptyList()))
        );
    }

    @PostMapping("/user-targets")
    public Mono<Void> setUserTargets(@RequestBody Map<String,List<String>> newUserTargets, UserToken userToken) {
        final CompletableFuture<Void> waitForProps = new CompletableFuture<>();
        propsUpdateFutures.add(waitForProps);
        return userTargetsConfiguration.set(newUserTargets, userToken).then();
    }

    @PostMapping("/user-target/{user}/**")
    public Mono<Map<String,List<String>>> setUserTarget(@PathVariable("user") String user, @WildcardParam String target, UserToken userToken) {
        log.info("set /user/target/{}/{}", user, target);
        final CompletableFuture<Void> waitForProps = new CompletableFuture<>();
        propsUpdateFutures.add(waitForProps);
        return userTargetsConfiguration.set(user, target, userToken.hasRole("DEPLOYER") ? UserToken.SERVICE_TOKEN : userToken);
    }

    @GetMapping("/dir/**")
    public Mono<List<String>> getDirectoryContents(@WildcardParam String name) {
        return Mono.fromFuture(dataService.getDirectoryContents(name));
    }

    @PostMapping("/dir/**")
    public Mono<Void> createDirectory(@WildcardParam String name, ServerWebExchange exchange) {
        return Mono.fromFuture(dataService.createDirectory(name))
            .then(sync(exchange));
    }

    @PostMapping("/dir/rename/**")
    public Mono<Void> rename(@WildcardParam String from, @RequestParam("to") String to, ServerWebExchange exchange) {
        return Mono.fromFuture(dataService.rename(from, to))
            .then(sync(exchange, Map.of("to", to)));
    }

    @DeleteMapping("/dir/**")
    public Mono<Void> delete(@WildcardParam String pathToDelete, ServerWebExchange exchange) {
        return Mono.fromFuture(dataService.delete(pathToDelete))
            .then(sync(exchange));
    }

    @PostMapping(value ="/upload/**", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Void> handleUpload(@WildcardParam String targetDir, ServerWebExchange exchange) {
        return dataService.handleUpload(targetDir, exchange.getMultipartData());
    }

    @GetMapping("/list")
    public List<OverviewItem> getOverview(ServerWebExchange exchange) {
        final String fromStar = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(HEADER_X_STAR_NAME)).orElse("unknown");
        log.info("Received request from {} for listing of statics resources", fromStar);
        return Overview.list(/*includeCrc:*/false);
    }

    @PostMapping("/file-change/**")
    public Mono<Void> downloadAddedFile(@WildcardParam String pathText, ServerWebExchange exchange) {
        final String fromStar = Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(HEADER_X_STAR_NAME)).orElseThrow();
        log.info("Received 'file-change' from star '{}' and file {}", fromStar, pathText);

        return Mono.fromFuture(filesRoot.get().exists(pathText))
            .flatMap(kf -> dispatcher.getStarInfos())
            .map(starInfos -> starInfos.stream().filter(si -> si.name.equals(fromStar)).findFirst().orElseThrow())
            .flatMap(starInfo -> webClientBuilder.build()
                .get()
                .uri(IOUtils.concatPath(starInfo.url, "microstar-statics", pathText))
                .headers(application::setHeaders)
                .headers(httpHeaders -> httpHeaders.remove(UserToken.HTTP_HEADER_NAME))
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .collectList()
                .publishOn(Schedulers.boundedElastic())
                .map(buffers -> {
                    log.info("download {} from {}", pathText, fromStar);
                    final Path tempFile = IOUtils.createAndDeleteTempFile();
                    return DataBufferUtils.write(Flux.fromIterable(buffers), tempFile)
                        .doOnSuccess(v -> DataStoreUtils.copy(tempFile, filesRoot.get(), pathText)
                            .thenRun(() -> IOUtils.del(tempFile))
                            .thenRun(dataService::emitChanged))
                        .subscribe();
                })
                .onErrorResume(ex -> { log.error("download failed of {}: {}", pathText, ex.getMessage()); return Mono.empty(); })
            ).then();
    }

    private Mono<Void> sync(ServerWebExchange exchange) { return sync(exchange, Collections.emptyMap()); }
    private Mono<Void> sync(ServerWebExchange exchange, Map<String,String> params) {
        boolean onlyLocal = "true".equals(exchange.getRequest().getQueryParams().toSingleValueMap().get("onlyLocal"));
        return onlyLocal ? Mono.empty() : dispatcher.relay(RelayRequest
            .forThisService()
            .method(Optional.of(exchange.getRequest().getMethod()).orElse(HttpMethod.POST))
            .servicePath(exchange.getRequest().getPath().toString())
            .params(params)
            .param("onlyLocal", "true")
            .build(), String.class).then();
    }
}
