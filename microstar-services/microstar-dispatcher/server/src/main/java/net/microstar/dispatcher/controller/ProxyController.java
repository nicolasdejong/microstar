package net.microstar.dispatcher.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.filter.MappingsWebFilter;
import net.microstar.dispatcher.services.PreparedResponses.PreparedResponse;
import net.microstar.dispatcher.services.RequestInfo;
import net.microstar.dispatcher.services.Services;
import net.microstar.spring.ContentTypes;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.webflux.authorization.AuthUtil;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static net.microstar.common.util.Utils.firstNotNull;

@Slf4j
@RequiredArgsConstructor
@RestController
@SuppressWarnings("squid:S1121")
public class ProxyController  {
    private final Services services;
    private final ResourceData resourceData;


    // All requests for which no Dispatcher rest handler exists end up here.
    // They will be proxied to a service if a service can be found for it
    @RequestMapping("/**")
    public Mono<ResponseEntity<Flux<DataBuffer>>> proxyRequest(ServerWebExchange exchange) {
        // Almost *all* requests come through here. So this code should be as fast as possible.

        return createClasspathResourceResponse(exchange)
            .switchIfEmpty(proxyRemapped(exchange))
            .switchIfEmpty(callService(exchange))
            .switchIfEmpty(Mono.error(() -> new NotFoundException("Not found: " + exchange.getRequest().getPath())));
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> callService(ServerWebExchange exchange) {
        final AtomicReference<RequestInfo> usedRequestInfo = new AtomicReference<>(null);
        return services
            .getClientForRequest(exchange.getRequest(), usedRequestInfo::set)
            .flatMap(clientCallMethod ->
                clientCallMethod.preparedResponse
                    .map(PreparedResponse::get)
                    .orElseGet(() -> callService(exchange, clientCallMethod.requestBodySpec.orElseThrow(), usedRequestInfo))
            );
    }
    private Mono<ResponseEntity<Flux<DataBuffer>>> callService(ServerWebExchange exchange, Mono<WebClient.RequestBodySpec> reqSpec, AtomicReference<RequestInfo> usedRequestInfo) {
        return reqSpec
            .map(req -> prepareRequest(exchange, req))
            .map(WebClient.RequestHeadersSpec::retrieve)
            .doOnNext(resp -> Optional.ofNullable(usedRequestInfo.get()).ifPresent(this::handleServiceCall))
            .flatMap(resp -> resp.onStatus(HttpStatusCode::isError, t -> Mono.empty())
                .toEntityFlux(DataBuffer.class))
            .doOnError(WebClientRequestException.class, ex -> Optional.ofNullable(usedRequestInfo.get()).ifPresent(this::handleServiceFailure)); // perhaps later add a retry here
    }
    private WebClient.RequestHeadersSpec<?> prepareRequest(ServerWebExchange exchange, WebClient.RequestBodySpec req) {
        return req
            .headers(newHeaders -> {
                setHeadersOf(newHeaders, exchange, /*keepHost=*/true);
                getCallerServiceId(exchange).ifPresent(serviceId ->
                    newHeaders.set(MicroStarConstants.HEADER_X_SERVICE_ID, serviceId.combined)
                );
            })
            .body(exchange.getRequest().getBody(), DataBuffer.class);
    }

    private void setHeadersOf(HttpHeaders newHeaders, ServerWebExchange exchange, boolean keepHost) {
        exchange.getRequest().getHeaders().entrySet().stream()
            .filter(e -> keepHost || !"host".equals(e.getKey().toLowerCase(Locale.ROOT)))
            .forEach(e -> newHeaders.addAll(e.getKey(), e.getValue()));

        final URI uri = exchange.getRequest().getURI();
        Map.of(
            "X-Forwarded-Path", uri.getPath()
        ).forEach((key, value) -> {
            if(!newHeaders.containsKey(key)) newHeaders.set(key, value);
        });
    }
    private Optional<ServiceId> getCallerServiceId(ServerWebExchange exchange) { // caller may not be service
        if(AuthUtil.isRequestHoldingSecret(exchange)) {
            return Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(MicroStarConstants.HEADER_X_SERVICE_ID))
                .map(ServiceId::of);
        }
        return Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(MicroStarConstants.HEADER_X_SERVICE_UUID))
            .map(UUID::fromString)
            .flatMap(services::getServiceFrom)
            .map(service -> service.id);
    }

    private Mono<ResponseEntity<Flux<DataBuffer>>> createClasspathResourceResponse(ServerWebExchange exchange) {
        final class $ { // NOSONAR - false positive on constructor
            static final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
            static final int BUFFER_SIZE = 16 * 1024;
        }
        return Mono.justOrEmpty(
                resourceData.getResource(exchange.getRequest().getPath().value())
                    .map(resource -> ResponseEntity.ok()
                        .contentType(ContentTypes.mediaTypeOfName(firstNotNull(resource.getFilename(), "")))
                        .body(DataBufferUtils.read(resource, 0L, $.bufferFactory, $.BUFFER_SIZE))
                    )
        );
    }
    private Mono<ResponseEntity<Flux<DataBuffer>>> proxyRemapped(ServerWebExchange exchange) {
        final class Local { // NOSONAR -- false positive on constructor
            static final Map<String,WebClient> domainToWebClient = new HashMap<>();
            static String domainOf(String url) { return url.split("//",2)[1].split("/",2)[0]; }
        }
        return Mono.justOrEmpty(Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(MappingsWebFilter.REMAP_PROXY_KEY)))
            .flatMap(url ->
                Local.domainToWebClient.computeIfAbsent(Local.domainOf(url), domain -> WebClient.builder().baseUrl(domain).build())
                    .method(exchange.getRequest().getMethod())
                    .uri(url)
                    .headers(newHeaders -> setHeadersOf(newHeaders, exchange, /*keepHost=*/false))
                    .body(exchange.getRequest().getBody(), DataBuffer.class)
                    .retrieve()
                    .toEntityFlux(DataBuffer.class)
            );

    }

    private void handleServiceCall(RequestInfo requestInfo) {
        // Connection checker can skip the next check because we now know the service is alive
        requestInfo.serviceInfo.ifPresent(reg -> reg.connectionChecker.setIsConnected());
    }
    private void handleServiceFailure(RequestInfo requestInfo) {
        // When a service fails to answer, unregister it. If it manages to repair or restart it can register itself again.
        requestInfo.serviceVariations.ifPresent(variations ->
            requestInfo.serviceInfo.ifPresent(failedService -> {
                variations.stopped(failedService);
                log.warn("Stopped service that failed to answer: {}", failedService.id.combined);
            })
        );
    }
}
