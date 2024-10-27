package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.dispatcher.services.Services;
import net.microstar.spring.exceptions.IllegalInputException;
import net.microstar.spring.webflux.authorization.AuthUtil;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Component
@Order(20)
@RequiredArgsConstructor
public class SetServiceIdInRequestFilter implements WebFilter {
    private final Services services;

    @Override @Nonnull
    public Mono<Void> filter(@Nonnull ServerWebExchange exchange, @Nonnull WebFilterChain nextInChain) {
        return nextInChain.filter(setServiceIdHeaderFromServiceInstanceId(exchange));
    }

    private ServerWebExchange setServiceIdHeaderFromServiceInstanceId(ServerWebExchange exchange) {
        return getRequestHeader(exchange, MicroStarConstants.HEADER_X_SERVICE_UUID)
            .map(instanceId ->
                services.getServiceFrom(uuidFromString(instanceId))
                    .map(serviceInfo -> setRequestHeader(exchange, MicroStarConstants.HEADER_X_SERVICE_ID, serviceInfo.id.combined))
                    .orElseGet(() -> {
                        if(AuthUtil.isRequestHoldingSecret(exchange)) return exchange;
                        throw new IllegalInputException("Unknown instanceId ("+instanceId+") provided for url: " + exchange.getRequest().getPath()).log();
                    }) // don't log the instanceId (protect the log / security)
            )
            .orElseGet(() ->
                // Remove the service_id header because its validity can not be guaranteed due to missing instanceId
                setRequestHeader(exchange, MicroStarConstants.HEADER_X_SERVICE_ID, null)
            );
    }

    private static UUID uuidFromString(String uuidString) {
        try {
            return UUID.fromString(uuidString);
        } catch (final IllegalArgumentException invalidUUIDFormat) {
            throw new IllegalInputException("Invalid UUID provided"); // don't log the input (protect the log / security)
        }
    }

    private static Optional<String>  getRequestHeader(ServerWebExchange exchange, String name) {
        return Optional.ofNullable(exchange.getRequest().getHeaders().getFirst(name));
    }
    private static ServerWebExchange setRequestHeader(ServerWebExchange exchange, String name, @Nullable String value) {
        return exchange.mutate().request(
                exchange.getRequest().mutate()
                    .headers(headers -> {
                        headers.remove(name);
                        if(value != null) headers.add(name, value);
                    })
                    .build())
            .build();
    }
}
