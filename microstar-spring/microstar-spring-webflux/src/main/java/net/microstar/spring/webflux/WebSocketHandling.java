package net.microstar.spring.webflux;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.NettyDataBufferFactory;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.HandshakeInfo;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.adapter.ReactorNettyWebSocketSession;
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
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.springframework.http.server.reactive.ServerHttpResponseDecorator.getNativeResponse;

@Slf4j
@Configuration
@Qualifier("webSocketHandling")
public class WebSocketHandling implements RequestUpgradeStrategy {

    @Bean
    @ConditionalOnMissingBean
    public WebSocketHandlerAdapter handlerAdapter(WebSocketService wss) {
        return new WebSocketHandlerAdapter(wss);
    }

    @Bean
    @ConditionalOnMissingBean
    public WebSocketService webSocketService() {
        return new HandshakeWebSocketService(this);
    }

    @Bean
    @ConditionalOnMissingBean
    public HandlerMapping webSocketHandlerMapping(EventEmitter eventEmitter) {
        final Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/event-emitter", eventEmitter.getWebSocketHandler());

        final SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setOrder(1);
        handlerMapping.setUrlMap(map);
        return handlerMapping;
    }

    @Override
    public Mono<Void> upgrade(ServerWebExchange exchange, @Nonnull WebSocketHandler webSocketHandler,
                              @Nullable String subProtocol, Supplier<HandshakeInfo> handshakeInfoFactory) {
        final ServerHttpResponse          response = exchange.getResponse();
        final HttpServerResponse   reactorResponse = getNativeResponse(response);
        final HandshakeInfo          handshakeInfo = handshakeInfoFactory.get();
        final NettyDataBufferFactory bufferFactory = (NettyDataBufferFactory) response.bufferFactory();

        // If an authentication check is needed, that can be added here
        //final UserToken token = UserToken.fromTokenString(handshakeInfo.getHeaders().getFirst(UserToken.HTTP_HEADER_NAME));

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
}
