package net.microstar.dispatcher.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.model.DispatcherProperties.ResponseAction;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Optional;

@Order()
@Slf4j
@Component
@RequiredArgsConstructor
public class ResponseActionsFilter implements WebFilter {
    final DynamicPropertiesRef<DispatcherProperties> propsRef = DynamicPropertiesRef.of(DispatcherProperties.class);

    @Override @Nonnull
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        final List<ResponseAction> responseActions = propsRef.get().responseActions;

        if(!responseActions.isEmpty()) {
            final ServerHttpResponse response = exchange.getResponse();
            final String serviceName = Optional.of(exchange.getRequest().getPath().toString().split("/")).filter(a->a.length>1).map(a->a[1]).orElse("");

            response.beforeCommit(() -> {
                final int status = Optional.ofNullable(response.getStatusCode()).map(HttpStatusCode::value).orElse(0);

                responseActions.stream()
                    .filter(ra -> isTriggered(ra, serviceName, status, exchange))
                    .findFirst()
                    .ifPresent(action -> run(action, exchange));
                return Mono.empty();
            });
        }
        return chain.filter(exchange);
    }

    private static boolean isTriggered(ResponseAction ra, String serviceName, int status, ServerWebExchange exchange) {
        if(log.isDebugEnabled()) log.debug("status={} for serviceName '{}' and path '{}'", status, serviceName, exchange.getRequest().getPath());
        return (ra.service.isEmpty() || "*".equals(ra.service) || ra.service.equals(serviceName)) && ra.status == status;
    }

    private static void run(ResponseAction action, ServerWebExchange exchange) {
        if(!"".equals(action.redirect)) {
            exchange.getResponse().getHeaders().set(HttpHeaders.LOCATION, action.redirect);
            exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        }
    }
}
