package net.microstar.statics;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.throwingfunctionals.ThrowingConsumer;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.StringUtils;
import net.microstar.common.util.Threads;
import net.microstar.spring.ContentTypes;
import net.microstar.spring.DataStores;
import net.microstar.spring.ResourceScanner;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.util.FluxUtils;
import net.microstar.statics.Targets.Target;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.PathContainer;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;
import static net.microstar.statics.DataService.DATASTORE_NAME;

@Slf4j
@RestController
public class ResourcesProxy {
    private final Targets targets;
            final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
    private static final String INDEX_HTML = "index.html";
    private static final String PUBLIC = "public";
    private static final String FRONTEND = "frontend";
    private static final String NAME_404 = "404.html";
    private static final DynamicReferenceNotNull<DataStore> dataStore = DataStores.get(DATASTORE_NAME);
    private static final DynamicPropertiesRef<StaticsProperties> propsRef = DynamicPropertiesRef.of(StaticsProperties.class);
    private @Nullable Targets.Target fallbackTarget;

    private final UserTargetsConfiguration userTargetsConfiguration;

    public ResourcesProxy(Targets targets, UserTargetsConfiguration userTargetsConfiguration) {
        this.targets = targets;
        this.userTargetsConfiguration = userTargetsConfiguration;
        propsRef.onChange((props, changedKeys) -> {
            DataStores.updateStoresForChangedConfiguration(); // in case this one is called *after* this configuration change handler
            if(DataStores.isFailingDataStore(dataStore.get())) return; // no datastore configured
            final BlockingDataStore store = BlockingDataStore.forStore(dataStore);
            final List<String> rootNames = store.listNames("");
            final boolean storeContainsPublic = rootNames.contains(PUBLIC+"/");
            final boolean storeContainsFrontend = rootNames.contains(FRONTEND+"/");

            // When the store is empty, copy a few default files there (from jar resources)
            if(!storeContainsPublic || !storeContainsFrontend) {
                // By setting the time to zero, sync is prevented
                final ThrowingConsumer<Path> setTimeToZero = p -> Files.setLastModifiedTime(p, FileTime.fromMillis(0));
                final Path tempDir = IOUtils.createTempDir();
                try {
                    if(!storeContainsPublic)   ResourceScanner.copyResources(concatPath("default", PUBLIC),   tempDir.resolve(PUBLIC),   setTimeToZero);
                    if(!storeContainsFrontend) ResourceScanner.copyResources(concatPath("default", FRONTEND), tempDir.resolve(FRONTEND), setTimeToZero);
                    noThrow(() -> DataStoreUtils.copy(tempDir, dataStore.get(), "").get());
                } finally {
                    IOUtils.delTree(tempDir);
                }
            }

            if(fallbackTarget == null || !Optional.of(fallbackTarget.to).equals(props.fallback)) {
                fallbackTarget = props.fallback.map(
                    fallback -> targets.createTarget("", fallback)
                ).orElse(null);
            }
        })
        .callOnChangeHandlers();
    }

    @RequestMapping("/**")
    public  Mono<ResponseEntity<Flux<DataBuffer>>> getResource(ServerWebExchange exchange, UserToken userToken) {
        return getResource(getRequestPath(exchange), exchange, userToken);
    }
    private Mono<ResponseEntity<Flux<DataBuffer>>> getResource(String pathIn, ServerWebExchange exchange, UserToken userToken) {
        final String path = dataStore.get().normalizePath(pathIn);
        return
            // First try if there are target mappings
            getMappedResource(path, exchange)

                // Get resource for current user
                .switchIfEmpty(Mono.defer(() -> getUserResource(path, userToken)))

                // Try to read from resources
                .switchIfEmpty(Mono.defer(() -> getJarResource(path)))

                // Try fallback if still no resource to return
                .switchIfEmpty(Mono.defer(() -> Mono.justOrEmpty(fallbackTarget)
                    .flatMap(fallback -> fallback.webClient.isPresent()
                        ? proxy(fallback, path, exchange)
                        : getFileResource(fallback.toPathFor(path))
                    )))

                .onErrorResume(t -> Mono.empty())

                // Not found? Then return the 'not found' custom page, if any
                .switchIfEmpty(Mono.defer(() -> get404Page(exchange)))

                // If nothing works, return 404 not found
                .switchIfEmpty(Mono.error(() -> notFound(exchange)))
            ;
    }


    /** Returns configured or found 404 page for given user and path */
    private Mono<ResponseEntity<Flux<DataBuffer>>> get404Page(ServerWebExchange exchange) {
        final String path = exchange.getRequest().getPath().toString().replace("\\","/");
        final CompletableFuture<String> notFoundTextFuture = CompletableFuture.supplyAsync(() -> {
            final String notFoundTemplate = get404ConfiguredTemplate(path)
                .or(() -> get404Template(path))
                .orElse("Requested resource not found: ${path}");
            final Map<String, String> vars = Map.of(
                "path", exchange.getRequest().getPath().toString(),
                "uri", exchange.getRequest().getURI().toString()
            );
            return StringUtils.replaceVariables(notFoundTemplate, vars);
        }, Threads.getExecutor());
        return Mono.fromFuture(notFoundTextFuture).map(notFoundText -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            DataBufferUtils.readInputStream(() -> new ByteArrayInputStream(notFoundText.getBytes(StandardCharsets.UTF_8)), bufferFactory, 10_240)
        )).onErrorReturn(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /** Returns any configured 404 contents from app.config.statics.notFoundMapping that maps from path pattern to
      * 404 location (either a file directly or a directory that should contain a 404.html). Empty if no configuration
      * set or no file found at configured location.
      */
    private Optional<String> get404ConfiguredTemplate(String path) {
        return propsRef.get().notFoundMapping.keySet().stream()
            .filter(pat -> noThrow(() -> path.matches(pat)).or(() -> noThrow(() -> parentOfPath(path).matches(pat))).orElse(false))
            .map(key -> propsRef.get().notFoundMapping.get(key))
            .flatMap(configuredPath -> read404TemplateFromGivenOrAnyParentPath("", configuredPath)
                              .or(() -> get404Template(configuredPath)).stream())
            .findFirst();
    }

    /** Returns 404 contents for given path and user, if any. Given path can be directory that
      * contains a "404.html" file or can point to a file directly. Checks the following locations:<pre>
      *
      * - any parent of given path + /404.html as file relative to /public/
      * - any parent of given path + /404.html as file relative to /frontend/
      * - given path as resource relative to /public/
      * - any parent of given path + /404.html as resource relative to /public/
      * - 404.html file in /public/
      * - 404.html resource in /public/
      * </pre>
      */
    private Optional<String> get404Template(String path) {
        return Optional.<String>empty()
            .or(() -> read404TemplateFromGivenOrAnyParentPath(PUBLIC, relPath(path)))
            .or(() -> read404TemplateFromGivenOrAnyParentPath(FRONTEND, relPath(path)))
            .or(() -> noThrow(() -> dataStore.get().readString(concatPath(PUBLIC, NAME_404)).get()).flatMap(opt -> opt))
            .or(() -> IOUtils.getResource(concatPath("/", PUBLIC, NAME_404)).map(b -> new String(b, StandardCharsets.UTF_8)));
    }
    private Optional<String> read404TemplateFromGivenOrAnyParentPath(String root, String relPath) {
        final BlockingDataStore store = BlockingDataStore.forStore(dataStore);
        final String normRoot = dataStore.get().normalizePath(root);

        // Given path is either a file (like /foo/bar/error.html) or a path (like /foo/bar/)
        final String dir = dataStore.get().normalizePath(concatPath(root, relPath));
        if(!dir.startsWith(normRoot)) return Optional.empty();
        return Stream.concat(Stream.of(dir), splitTraversal(dir).stream().map(path -> concatPath(path, NAME_404)))
            .flatMap(path -> store.readString(path).stream())
            .findFirst();
    }

    /** a/b/c/d -> a/b/c/d, a/b/c, a/b, a */
    private List<String> splitTraversal(String path) {
        final List<String> parts = Arrays.asList(path.split("/"));
        final List<String> paths = new ArrayList<>();
        for(int i=parts.size(); i>0; i--) paths.add(parts.stream().limit(i).collect(Collectors.joining("/")));
        return paths;
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> getMappedResource(String path, ServerWebExchange exchange) {
        return Mono.justOrEmpty(targets.getTarget(path))
            .flatMap(target -> target.webClient.isPresent()
                ? proxy(target, path, exchange)
                : getFileResource(target.toPathFor(path))
            );
    }
    private Mono<ResponseEntity<Flux<DataBuffer>>> getUserResource(String path, UserToken userToken) {
        return Mono.just(getTargetPath(path, userToken))
            .flatMap(this::getFileResource)
            .switchIfEmpty(Mono.defer(() -> // try public resource is user resource was not found
                Mono.just(getTargetPath(path, ""))
                    .flatMap(this::getFileResource)))
            ;
    }
    private Mono<ResponseEntity<Flux<DataBuffer>>> getFileResource(String path) {
        return Mono.fromFuture(getFilePath(path))
                .flatMap(fsPath ->
                    FluxUtils.fluxFromStore(dataStore.get(), fsPath)
                    .map(flux -> ResponseEntity
                        .ok()
                        .contentType(ContentTypes.mediaTypeOfName(fsPath))
                        .body(flux)
                    )
                );
    }
    private Mono<ResponseEntity<Flux<DataBuffer>>> getJarResource(String resourcePath) {
        @Nullable URL publicUrl = this.getClass().getResource("/public/");
        if(publicUrl == null) return Mono.empty();

        @Nullable URL url = this.getClass().getResource("/public/" + resourcePath);
        if(url == null) url = this.getClass().getResource(concatPath(resourcePath, INDEX_HTML));
        if(url == null) return Mono.empty();
        if(!url.toString().startsWith(publicUrl.toString())) return Mono.empty(); // prevent ../ above /public
        final String path = url.toString();

        return FluxUtils.fluxFromStore(dataStore.get(), path)
                .map(flux -> ResponseEntity
                    .ok()
                    .contentType(ContentTypes.mediaTypeOfName(path))
                    .body(flux)
                );
    }

    private CompletableFuture<String> getFilePath(String relativePath) {
        if(relativePath.isEmpty()) return getFilePath("/");
        return dataStore.get().exists(relativePath)
                .thenCompose(exists -> relativePath.endsWith(INDEX_HTML) || (exists && !mayBeDirName(relativePath)) // NOSONAR -- Boolean
                    ? CompletableFuture.completedFuture(relativePath)
                    : getFilePath(concatPath(relativePath, INDEX_HTML))
                );
    }
    private static boolean mayBeDirName(String path) {
        return path.endsWith("/") || !path.replaceFirst("^.*/", "").contains(".");
    }

    private String getTargetPath(String path, UserToken userToken) {
        if(userToken.hasRole(ROLE_SERVICE)) return path;
        if(!userToken.isLoggedIn()) return concatPath(PUBLIC, path);
        return getTargetPath(path, userToken.getEmailName());
    }
    private String getTargetPath(String path, String user) {
        if(user.isEmpty()) return concatPath(PUBLIC, path);
        final String target = userTargetsConfiguration.getTargetForUser(user, path);
        return concatPath(target, dataStore.get().normalizePath(path));
    }

    Mono<ResponseEntity<Flux<DataBuffer>>> proxy(Target target, String uri, ServerWebExchange exchange) {
        return Mono.just(prepareWebClient(target.webClient.orElseThrow(), target.toPathFor(uri), exchange))
            .map(WebClient.RequestHeadersSpec::retrieve)
            .flatMap(ResourcesProxy::mapToResponseEntity)
            ;
    }
    private static WebClient.RequestHeadersSpec<?> prepareWebClient(WebClient webClient, String uri, ServerWebExchange exchange) {
        final String query = Optional.ofNullable(exchange.getRequest().getURI().getRawQuery()).map(q -> "?" + q).orElse("");
        return webClient
            .method(Optional.of(exchange.getRequest().getMethod()).orElse(HttpMethod.GET))
            .uri(uri + query)
            .headers(newHeaders -> setHeaders(uri, exchange, newHeaders));
    }
    private static void setHeaders(String uri, ServerWebExchange exchange, HttpHeaders newHeaders) {
        final String targetHost = (uri.replaceFirst("^\\w+://", "").replaceFirst("/.*$", "")).replaceAll(":80$", "");
        final String thisHost = (exchange.getRequest().getURI().getHost() + ":" + exchange.getRequest().getURI().getPort()).replaceAll(":80$", "");

        exchange.getRequest().getHeaders().entrySet().stream()
            .map(entry -> Map.entry(
                entry.getKey(), // Replace host so the call appears to come from the origin
                entry.getValue().stream().map(val -> val.replace(thisHost, targetHost)).toList()
            ))
            .forEach(entry -> newHeaders.add(entry.getKey(), entry.getValue().stream().findFirst().orElse("")));
    }

    private static NotFoundException notFound(ServerWebExchange exchange) {
        return new NotFoundException(exchange.getRequest().getPath().toString());
    }

    private static Mono<ResponseEntity<Flux<DataBuffer>>> mapToResponseEntity(WebClient.ResponseSpec response) {
        return response.onStatus(HttpStatusCode::isError, t -> Mono.empty()).toEntityFlux(DataBuffer.class);
    }


    private static String getRequestPath(ServerWebExchange exchange) {
        final String name = exchange.getRequest().getPath().elements().stream()
            .filter(value -> !(value instanceof PathContainer.Separator))
            .map(PathContainer.Element::value)
            .collect(Collectors.joining("/"));

        return pathWithFile(name);
    }
    private static String pathWithFile(String path) {
        return path.endsWith("/") ? path + INDEX_HTML :
               path.isEmpty()     ? "/"  + INDEX_HTML : path;
    }
    private static String parentOfPath(String path) {
        return path.replaceFirst("/[^/]+$", "");
    }
    private static String relPath(String path) { return path.startsWith("/") ? path.substring(1) : path; }
}

