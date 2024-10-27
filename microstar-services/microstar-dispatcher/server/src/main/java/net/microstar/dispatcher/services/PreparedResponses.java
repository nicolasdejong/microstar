package net.microstar.dispatcher.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.dispatcher.services.Services.ClientCallMethod;
import net.microstar.spring.webflux.util.FluxUtils;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/** When a request is performed that is cached or should not go through to the actual service, a prepared
  * response can be created. It will return a value as if the target service was called.
  */
@Slf4j
@Component
@RequiredArgsConstructor
public class PreparedResponses {
    private final ObjectMapper objectMapper;
    private final ServiceProcessInfos serviceProcessInfos;

    /** The Dispatcher keeps a map of all processInfos from the running services, so
      * requesting a processInfo from a service can be handled in the Dispatcher without
      * calling that service.
      */
    public Mono<ClientCallMethod> getPrepared(RequestInfo reqInfo) {
        if("/processInfo".equals(reqInfo.restPath))
            return Mono.justOrEmpty(reqInfo.serviceInfo
                    .flatMap(serviceProcessInfos::getProcessInfo)
                    .flatMap(this::forJson)
            );
        return Mono.empty();
    }

    private Optional<ClientCallMethod> forJson(Object data) {
        final String json = noThrow(() -> objectMapper.writeValueAsString(data)).orElse("{}");
        return noThrow(() -> ClientCallMethod.forPreparedResponse(PreparedResponse.ofJson(json)));
    }

    @RequiredArgsConstructor
    public static class PreparedResponse {
        final MediaType contentType;
        final byte[] data;

        public Mono<ResponseEntity<Flux<DataBuffer>>> get() {
            return Mono.just(ResponseEntity.ok().contentType(contentType).body(FluxUtils.fluxFrom(data)));
        }
        public static PreparedResponse ofJson(String json) { return new PreparedResponse(MediaType.APPLICATION_JSON, json.getBytes(StandardCharsets.UTF_8)); }
    }
}
