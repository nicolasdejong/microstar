package net.microstar.spring.webflux;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.Singular;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.io.IOUtils;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertyRef;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

@Slf4j
public class AbstractServiceClient {
    protected final DynamicPropertyRef<String> dispatcherUrl = DynamicPropertyRef.of("app.config.dispatcher.url")
                                                                                 .withDefault("http://localhost:8080");
    @Nullable private WebClient webClient = null;
    protected final String serviceLocation;

    protected AbstractServiceClient(String serviceLocation, WebClient.Builder webClientBuilder) {
        dispatcherUrl.onChange(baseUrl -> {
            try {
                webClient = webClientBuilder.build();
            } catch (final Exception failed) {
                log.error("WebClient won't init", failed);
                webClient = null;
            }
        }).callOnChangeHandlers();
        this.serviceLocation = IOUtils.concatPath(serviceLocation.startsWith("http") ? null : dispatcherUrl.get(), serviceLocation);
    }
    protected AbstractServiceClient(String serviceLocation, MicroStarApplication application, WebClient.Builder webClientBuilder) {
        dispatcherUrl.onChange(baseUrl -> {
            try {
                webClient = webClientBuilder
                    .baseUrl(IOUtils.concatPath(baseUrl, serviceLocation))
                    .defaultHeaders(application::setHeaders)
                    .build();
            } catch (final Exception failed) {
                log.error("WebClient won't init for address: " + baseUrl, failed);
                webClient = null;
            }
        }).callOnChangeHandlers();
        this.serviceLocation = IOUtils.concatPath(serviceLocation.startsWith("http") ? null : dispatcherUrl.get(), serviceLocation);
    }

    protected WebClient getWebClient() {
        if(webClient == null) throw new IllegalStateException("No webClient available");
        return webClient;
    }

    public <T> Client.Builder<T> method(HttpMethod m, ParameterizedTypeReference<T> resultType) {
        return Client.<T>builder().resultType(Optional.of(resultType)).webClient(webClient).serviceLocation(serviceLocation).method(m);
    }
    public <T> Client.Builder<T> method(HttpMethod m, Class<T> resultType) {
        return method(m, ParameterizedTypeReference.forType(resultType));
    }

    public     Client.Builder<Void> get()                                            { return method(HttpMethod.GET, Void.class); }
    public <T> Client.Builder<T>    get(Class<T> resultType)                         { return method(HttpMethod.GET, resultType); }
    public <T> Client.Builder<T>    get(ParameterizedTypeReference<T> resultType)    { return method(HttpMethod.GET, resultType); }
    public     Client.Builder<Void> post()                                           { return method(HttpMethod.POST, Void.class); }
    public <T> Client.Builder<T>    post(Class<T> resultType)                        { return method(HttpMethod.POST, resultType); }
    public <T> Client.Builder<T>    post(ParameterizedTypeReference<T> resultType)   { return method(HttpMethod.POST, resultType); }
    public     Client.Builder<Void> put()                                            { return method(HttpMethod.PUT, Void.class); }
    public <T> Client.Builder<T>    put(Class<T> resultType)                         { return method(HttpMethod.PUT, resultType); }
    public <T> Client.Builder<T>    put(ParameterizedTypeReference<T> resultType)    { return method(HttpMethod.PUT, resultType); }
    public     Client.Builder<Void> delete()                                         { return method(HttpMethod.DELETE, Void.class); }
    public <T> Client.Builder<T>    delete(Class<T> resultType)                      { return method(HttpMethod.DELETE, resultType); }
    public <T> Client.Builder<T>    delete(ParameterizedTypeReference<T> resultType) { return method(HttpMethod.DELETE, resultType); }


    @SuppressWarnings("cast") // A redundant cast in Lombok generated code leads to warning which leads to build failure
    @Builder(builderClassName = "Builder")
    public static class Client<T> {
        @Nullable private final WebClient webClient;
        private final String serviceLocation;
        @Default public final HttpMethod method = HttpMethod.GET;
        @Default public final UserToken user = UserToken.SERVICE_TOKEN;
        @Default public final String path = "/";
        @Default public final Optional<ParameterizedTypeReference<T>> resultType = Optional.empty();
        @Default public final Optional<Object> bodyValue = Optional.empty();
        @Default @Nullable public final UnaryOperator<WebClient.RequestBodySpec> customizer = null;
        /** If you have multiple values for the same header, use a Collection */
        @Singular public final Map<String,Object> headers;
        /** If you have multiple values for the same param, use a Collection */
        @Singular public final Map<String,Object> params;

        public static class Builder<T> {
            public Mono<T> call(String path, Object bodyValue) { return path(path).bodyValue(bodyValue).call(); }
            public Mono<T> call(String path) { return path(path).call(); }
            public Mono<T> call() {
                final Client<T> client = build();
                if(client.webClient == null) return Mono.empty();

                WebClient.RequestBodySpec req = webClient
                        .method(client.method)
                        .uri((client.path.matches("^https?://.*$") ? client.path : IOUtils.concatPath(client.serviceLocation, client.path)) + (
                            client.params.isEmpty() ? "" : ("?" + client.params.entrySet().stream()
                                .filter(e->e.getValue()!=null)
                                .map(e -> paramToString(e.getKey(), e.getValue()))
                                .collect(Collectors.joining("&"))
                            ))
                        )
                        .headers(httpHeaders -> {
                            if(!httpHeaders.containsKey(MicroStarConstants.HEADER_X_CLUSTER_SECRET)) { // application::setHeaders was called
                                httpHeaders.set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
                                if (client.user == UserToken.SERVICE_TOKEN) MicroStarApplication.get().orElseThrow().setHeaders(httpHeaders);
                            }
                            client.headers.forEach((key,val) -> { if(val != null) httpHeaders.set(key,val.toString()); });
                            if(client.user != UserToken.SERVICE_TOKEN && !httpHeaders.containsKey(UserToken.HTTP_HEADER_NAME)) {
                                httpHeaders.set(UserToken.HTTP_HEADER_NAME, client.user.toTokenString());
                            }
                        });
                client.bodyValue.ifPresent(req::bodyValue);

                //noinspection ConstantValue
                if(client.customizer != null) req = client.customizer.apply(req);

                //noinspection unchecked
                return client.resultType.isPresent() ? req.retrieve().bodyToMono(client.resultType.get()) :
                                                       (Mono<T>)req.retrieve().toBodilessEntity().then();
            }
            public Builder<T> path(Object... parts) { path$value = IOUtils.concatPath(parts); path$set = true; return this; }
            public Builder<T> uri(Object... parts) { return path(parts); }
            public Builder<T> bodyValue(@Nullable Object obj) { bodyValue$value = Optional.ofNullable(obj); bodyValue$set = true; return this; }
        }

        private static String paramToString(String key, Object value) {
            return value instanceof Collection<?> collection
                ? paramToString(key, collection)
                : paramToString(key, List.of(value));
        }
        private static String paramToString(String key, Collection<?> collection) {
            final String paramName = URLEncoder.encode(key, StandardCharsets.UTF_8);
            return collection.stream().map(val -> paramName + "=" + URLEncoder.encode(Objects.toString(val), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        }
    }
}
