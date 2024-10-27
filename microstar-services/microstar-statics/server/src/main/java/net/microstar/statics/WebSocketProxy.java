package net.microstar.statics;

import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.AbstractHandlerMapping;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.RequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.WebsocketServerSpec;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static io.netty.handler.codec.http.HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL;
import static org.springframework.http.server.reactive.ServerHttpResponseDecorator.getNativeResponse;

@Slf4j
@Configuration
@Qualifier("WebSocketProxy")
public class WebSocketProxy implements RequestUpgradeStrategy {
    private static final DynamicPropertiesRef<StaticsProperties> props = DynamicPropertiesRef.of(StaticsProperties.class);
    private final WebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
    private Optional<Targets.Target> fallbackTarget = Optional.empty();

    public WebSocketProxy(Targets targets) {
        props.onChange(props -> {
                if(fallbackTarget.isEmpty() || !Optional.of(fallbackTarget.get().to).equals(props.fallback)) {
                    fallbackTarget = props.fallback.map(
                        fallback -> targets.createTarget("", fallback)
                    );
                }
            })
            .callOnChangeHandlers();
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter(WebSocketService wss) {
        return new WebSocketHandlerAdapter(wss);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @Qualifier("proxyWebsocketService")
    public WebSocketService webSocketService() {
        return new HandshakeWebSocketService(this);
    }

    @Bean
    public HandlerMapping webSocketHandlerMapping(Targets targets, EventEmitter eventEmitter) {
        final AbstractHandlerMapping handlerMapping = new AbstractHandlerMapping() {
            @Override
            protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
                final ServerHttpRequest request = exchange.getRequest();
                final HttpHeaders       headers = request.getHeaders();
                final boolean    asksForUpgrade = headers.containsKey("Upgrade");

                // Due to the high order, all requests will come through here. But we are only interested in websocket connections.
                if(!asksForUpgrade) return Mono.empty();

                if("/event-emitter".equals(request.getPath().toString())) return Mono.just(eventEmitter.getWebSocketHandler());

                return targets.getTarget(exchange.getRequest().getPath().toString())
                    .filter(target -> target.webClient.isPresent())
                    .or(() -> fallbackTarget.filter(target -> target.webClient.isPresent()))
                    .map(target ->
                        Mono.just(new TargetWebSocketHandler(URI.create(target.to), webSocketClient, exchange.getRequest().getHeaders()))
                    )
                    .orElseThrow(() ->
                        new NotFoundException(exchange.getRequest().getPath().toString())
                    );
            }
        };
        handlerMapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return handlerMapping;
    }

    @Override
    public Mono<Void> upgrade(ServerWebExchange exchange, @Nonnull WebSocketHandler webSocketHandler,
                              @Nullable String subProtocol, Supplier<HandshakeInfo> handshakeInfoFactory) {
        final ServerHttpResponse response = exchange.getResponse();
        final HttpServerResponse reactorResponse = getNativeResponse(response);
        final HandshakeInfo          handshakeInfo = handshakeInfoFactory.get();
        final NettyDataBufferFactory bufferFactory = (NettyDataBufferFactory) response.bufferFactory();

        final WebsocketServerSpec.Builder websocketServerSpecBuilder = WebsocketServerSpec.builder();
        if(subProtocol != null) websocketServerSpecBuilder.protocols(subProtocol);
        final WebsocketServerSpec websocketServerSpec = websocketServerSpecBuilder.build();

        return reactorResponse.sendWebsocket(
            (in, out) -> {
                final ReactorNettyWebSocketSession session = new ReactorNettyWebSocketSession(in, out,
                    handshakeInfo,
                    bufferFactory,
                    128 * 1024);
                return webSocketHandler.handle(session).onErrorStop();
            }, websocketServerSpec);
    }

    /** Proxy incoming websocket to target by creating a WebSocketClient to target and pipe data between them */
    static class TargetWebSocketHandler implements WebSocketHandler {
        // Copied from:
        // https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/WebsocketRoutingFilter.java
        // CALLER <---> TARGET
        private final URI targetUri;
        private final WebSocketClient client;
        private final HttpHeaders headers;
        private final List<String> subProtocols;

        TargetWebSocketHandler(URI targetUri, WebSocketClient client, HttpHeaders headers) {
            this.targetUri = targetUri;
            this.client = client;
            this.headers = headers;
            this.subProtocols = Optional.ofNullable(headers.getFirst(SEC_WEBSOCKET_PROTOCOL.toString()))
                .map(Collections::singletonList)
                .orElseGet(Collections::emptyList);
        }

        @Override
        public List<String> getSubProtocols() {
            return subProtocols;
        }

        /** Proxy callerSession to proxySession -- don't interpret data, just proxy */
        @Override
        public Mono<Void> handle(@Nonnull WebSocketSession callerSession) {
            // pass headers along so custom headers can be sent through
            return client.execute(targetUri, this.headers, new WebSocketHandler() {
                @Override
                public List<String> getSubProtocols() { // we don't need to interpret, only proxy, so allow all subProtocols
                    return subProtocols;
                }

                @Override
                public Mono<Void> handle(WebSocketSession proxySession) {
                    final Mono<Void> serverClose = proxySession
                        .closeStatus()
                        .filter(unused -> callerSession.isOpen())
                        .flatMap(callerSession::close);
                    final Mono<Void> proxyClose = callerSession
                        .closeStatus()
                        .filter(unused -> proxySession.isOpen())
                        .flatMap(proxySession::close);

                    // Use retain() for Reactor Netty
                    final Mono<Void> proxySessionSend  =  proxySession.send(callerSession.receive().doOnNext(WebSocketMessage::retain));
                    final Mono<Void> serverSessionSend = callerSession.send( proxySession.receive().doOnNext(WebSocketMessage::retain));

                    // Ensure closeStatus from one propagates to the other
                    //noinspection CallingSubscribeInNonBlockingScope -- copied from SpringCloud
                    Mono.when(serverClose, proxyClose).subscribe();

                    // Complete when both sessions are done
                    return Mono.zip( // Mono<Void> won't emit a value, so it won't zip. Therefore, emit a token value
                        proxySessionSend .then(Mono.just(new Object())),
                        serverSessionSend.then(Mono.just(new Object()))
                    ).then(); // Make it emit Void instead of Object
                }
            });
        }
    }
}
