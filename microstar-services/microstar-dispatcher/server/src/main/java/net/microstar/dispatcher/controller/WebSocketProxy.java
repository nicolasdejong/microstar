package net.microstar.dispatcher.controller;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.dispatcher.services.Services;
import net.microstar.dispatcher.services.StarsManager;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.authorization.AuthUtil;
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
public class WebSocketProxy implements RequestUpgradeStrategy {
    private final DynamicPropertiesRef<DispatcherProperties> props = DynamicPropertiesRef.of(DispatcherProperties.class);

    @Bean
    public WebSocketHandlerAdapter handlerAdapter(WebSocketService wss) {
        return new WebSocketHandlerAdapter(wss);
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public WebSocketService webSocketService(RequestUpgradeStrategy upgradeStrategy) {
        return new HandshakeWebSocketService(upgradeStrategy);
    }

    @Bean
    public HandlerMapping webSocketHandlerMapping(Services services, EventEmitter eventEmitter, StarsManager starsManager) {
        final AbstractHandlerMapping handlerMapping = new AbstractHandlerMapping() {
            @Override
            protected Mono<?> getHandlerInternal(ServerWebExchange exchange) {
                final ServerHttpRequest request = exchange.getRequest();
                final HttpHeaders headers = request.getHeaders();
                final boolean asksForUpgrade = headers.containsKey("Upgrade");

                // Due to the high order, all requests will come through here. But we are only interested in websocket connections.
                if (!asksForUpgrade) return Mono.empty();

                final String selector = request.getPath().value().replace(MicroStarConstants.URL_DUMMY_PREVENT_MATCH + "/", "");

                // If an authentication check is needed, that can be added here (before proxying)
                //final UserToken token = UserToken.fromTokenString(headers.getFirst(UserToken.HTTP_HEADER_NAME))

                final @Nullable String targetStar = headers.getFirst(MicroStarConstants.HEADER_X_STAR_TARGET);
                final boolean isForOtherStar = targetStar != null && !targetStar.equals(starsManager.getLocalStar().name);

                if(!isForOtherStar) {
                    // Websocket endpoints on this Dispatcher
                    if ("/event-emitter".equals(selector)) return Mono.just(eventEmitter.getWebSocketHandler());
                }

                // Websocket endpoints on (services or another star) that should be proxied there
                return services.getTargetUriFor(request)
                    .map(uri -> new ProxyWebSocketHandler(URI.create(uri), new ReactorNettyWebSocketClient(), headers));
            }
        };
        // Make sure requests come through here before being handled by the 'I accept all requests' web handler.
        // WebSockets are in the form ws://etc but are initially as http://etc call with a header called 'Upgrade'.
        handlerMapping.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return handlerMapping;
    }

    @Override
    public Mono<Void> upgrade(ServerWebExchange exchange, @Nonnull WebSocketHandler webSocketHandler,
                              @Nullable String subProtocol, Supplier<HandshakeInfo> handshakeInfoFactory) {
        final ServerHttpResponse          response = exchange.getResponse();
        final HttpServerResponse   reactorResponse = getNativeResponse(response);
        final HandshakeInfo          handshakeInfo = handshakeInfoFactory.get();
        final NettyDataBufferFactory bufferFactory = (NettyDataBufferFactory) response.bufferFactory();

        final boolean userIsAllowedToUseWebsockets = AuthUtil.isRequestHoldingSecret(exchange)
            || AuthUtil.userTokenFrom(exchange)
                .hasAnyRoles(props.get().websocketAccessRoles);

        if(!userIsAllowedToUseWebsockets) {
            log.warn("User ({}) is not allowed to use WebSockets", AuthUtil.userTokenFrom(exchange).name);
            throw new NotAuthorizedException("This user is not allowed to use WebSockets");
        }

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
    static class ProxyWebSocketHandler implements WebSocketHandler {
        // Copied from:
        // https://github.com/spring-cloud/spring-cloud-gateway/blob/main/spring-cloud-gateway-server/src/main/java/org/springframework/cloud/gateway/filter/WebsocketRoutingFilter.java
        // CALLER <---> TARGET
        private final URI targetUri;
        private final WebSocketClient client;
        private final HttpHeaders headers;
        private final List<String> subProtocols;

        ProxyWebSocketHandler(URI targetUri, WebSocketClient client, HttpHeaders headers) {
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
