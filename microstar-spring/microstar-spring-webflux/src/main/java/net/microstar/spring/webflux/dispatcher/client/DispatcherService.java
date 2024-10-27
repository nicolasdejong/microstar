package net.microstar.spring.webflux.dispatcher.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.webflux.AbstractServiceClient;
import net.microstar.spring.webflux.MiniBus;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.microstar.common.io.IOUtils.concatPath;

@Slf4j
@Component
public class DispatcherService extends AbstractServiceClient {
    private final ObjectMapper objectMapper;

    public DispatcherService(MicroStarApplication application, WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        super("/", application, webClientBuilder);
        this.objectMapper = objectMapper;
    }

    public ImmutableMap<UUID, ServiceId> getServiceInstanceIds() {
        return getWebClient()
            .get()
            .uri(concatPath(dispatcherUrl, "/services/instance-ids"))
            .retrieve()
            .bodyToMono(new ParameterizedTypeReference<ImmutableMap<String,ServiceId>>() {})
            .map(map -> map.entrySet().stream()
                .map(entry -> Map.entry(UUID.fromString(entry.getKey()), entry.getValue()))
                .collect(ImmutableUtil.toImmutableMap()))
            .onErrorResume(thrown -> {
                log.error("Failed to get instanceIds from Dispatcher: {}", thrown.getMessage());
                return Mono.just(ImmutableUtil.emptyMap());
            })
            .blockOptional()
            .orElseGet(ImmutableUtil::emptyMap);
    }

    public void sendConfigurationRefreshEvent(UUID serviceId) {
        sendEvent(serviceId, "refresh-settings");
    }

    public <T> Flux<RelayResponse<T>> relay(RelayRequest request, Class<T> type) { return relay(request, ParameterizedTypeReference.forType(type)); }
    public <T> Flux<RelayResponse<T>> relay(RelayRequest request, ParameterizedTypeReference<T> type) {
        return getWebClient()
            .post()
            .uri(concatPath(dispatcherUrl, "relay"))
            .bodyValue(request)
            .retrieve()
            .onRawStatus(status -> status != 200, resp -> resp.bodyToMono(new ParameterizedTypeReference<Map<String,String>>() {}).map(map -> new IllegalStateException("POST failed: " + map.get("error"))))
            .bodyToFlux(new ParameterizedTypeReference<RelayResponse<String>>() {})
            .mapNotNull(resp -> resp.content.map(text -> resp.setNewContent(Optional.ofNullable(convert(text, type)))).orElse(null))
            ;
    }
    public <T> Mono<RelayResponse<T>> relaySingle(RelayRequest request, Class<T> type) { return relaySingle(request, ParameterizedTypeReference.forType(type)); }
    public <T> Mono<RelayResponse<T>> relaySingle(RelayRequest request, ParameterizedTypeReference<T> type) {
        return getWebClient()
            .post()
            .uri(concatPath(dispatcherUrl, "relay-single"))
            .bodyValue(request)
            .retrieve()
            .onRawStatus(status -> status != 200, resp -> resp.bodyToMono(new ParameterizedTypeReference<Map<String,String>>() {}).map(map ->
                new IllegalStateException(request.method + " " + IOUtils.concatPath(request.serviceName, request.servicePath) + " failed: " + map.get("error"))))
            .bodyToFlux(new ParameterizedTypeReference<RelayResponse<String>>() {})
            .take(1)
            .next()
            .mapNotNull(resp -> resp.content.map(text -> resp.setNewContent(Optional.ofNullable(convert(text, type)))).orElse(null))
            ;
    }

    public Mono<String> getLocalStarName() {
        return getWebClient()
            .get().uri(concatPath(dispatcherUrl.get(), "version")).retrieve()
            .toBodilessEntity()
            .mapNotNull(resp -> resp.getHeaders().getFirst(MicroStarConstants.HEADER_X_STAR_NAME))
            .switchIfEmpty(Mono.just("main"))
            .cache(Duration.ofSeconds(30))
            ;
    }

    @Jacksonized @Builder @ToString
    public static final class StarInfo {
        public final String name;
        public final String url;
        public final boolean isActive;
        public final boolean isLocal;
    }

    public Mono<List<StarInfo>> getStarInfos() {
        return get(new ParameterizedTypeReference<List<StarInfo>>() {}).uri(concatPath(dispatcherUrl.get(), "stars")).call();
    }

    public Mono<Void> postBusMessage(MiniBus.BusMessage message) { return postBusMessage(message, /*localStarOnly=*/false); }
    public Mono<Void> postBusMessage(MiniBus.BusMessage message, boolean localStarOnly) {
        return post(message.getClass())
            .bodyValue(message)
            .param("className", message.getClass().getName())
            .param("localStarOnly", localStarOnly)
            .call(concatPath(dispatcherUrl, "miniBusDispatcher"))
            .then();
    }

    @SuppressWarnings("SameParameterValue")
    private void sendEvent(UUID serviceId, String eventName) {
        get().uri(serviceId, eventName).call().block();
    }

    @SuppressWarnings("unchecked")
    @Nullable <T> T convert(@Nullable String data, ParameterizedTypeReference<T> type) {
        if(type.getType() == String.class) return (T)data;
        if(type.getType() == Void.class) return null;
        if(data == null) return null;
        final TypeReference<Object> responseTypeRef = new TypeReference<>(){
            @Override
            public Type getType() { return type.getType(); }
        };
        try {
            return (T)objectMapper.readValue(data, responseTypeRef);
        } catch (JsonProcessingException e) {
            throw new FatalException("Relay conversion error: " + Throwables.getRootCause(e).getMessage());
        }
    }
}