package net.microstar.spring.webflux;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/** Service events to be sent to a client via websocket. An instance will be created by Spring. */
@Slf4j
@Component
public class EventEmitter {
    private final ObjectMapper objectMapper;
    private final Sinks.Many<ServiceEvent<?>> sink;
    private static final Sinks.EmitFailureHandler emitFailureHandler = (signalType, emitResult) -> emitResult == Sinks.EmitResult.FAIL_NON_SERIALIZED;
    private static final Duration RECENT_KEEP_DURATION = Duration.ofSeconds(10);
    private final LinkedList<ServiceEvent<?>> recentEvents = new LinkedList<>();
    private final List<ListenerInfo> listenerInfos = new CopyOnWriteArrayList<>();

    @RequiredArgsConstructor
    private static class ListenerInfo {
        final String type;
        final Consumer<ServiceEvent<?>> handler;
    }

    public EventEmitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        sink = Sinks.many().multicast().directBestEffort();
        sink.asFlux().subscribe(event ->{
            final long killTime = System.currentTimeMillis() - RECENT_KEEP_DURATION.toMillis();
            synchronized (recentEvents) {
                recentEvents.add(event);
                while(!recentEvents.isEmpty() && recentEvents.getFirst().timestamp < killTime) recentEvents.removeFirst();
            }
        });
    }

    public Optional<ServiceEvent<?>> getFirstRecentEventSince(long timestamp) { // NOSONAR -- ? can be any datatype
        synchronized (recentEvents) {
            return recentEvents.stream()
                .filter(evt -> evt.timestamp >= timestamp)
                .findFirst();
        }
    }

    public void next(String type) { next(new ServiceEvent<>(type, null)); }
    public void next(String type, String message) {
        next(new ServiceEvent<>(type, Map.of("message", message)));
    }
    public void next(String type, Map<String,String> data) {
        next(new ServiceEvent<>(type, data));
    }
    public void next(ServiceEvent<?> eventIn) {
        final ServiceEvent<?> event;
        if(eventIn.data instanceof EventDataSource source) {
            event = new ServiceEvent<>(eventIn.type, source.getEventData());
        } else {
            event = eventIn;
        }
        callOnEmitFor(event);
        final Sinks.EmitResult result = sink.tryEmitNext(event);
        if(result == Sinks.EmitResult.OK) return;

        if(result == Sinks.EmitResult.FAIL_NON_SERIALIZED) {
            log.error("Unable to serialize event of type {}: {}", eventIn.data.getClass(), eventIn.data);
        } else {
            // retry
            sink.emitNext(event, emitFailureHandler);
        }
    }

    public <T> EventEmitter onEmit(String type, Consumer<ServiceEvent<T>> handler) {
        //noinspection unchecked -- <T> to <?>
        listenerInfos.add(new ListenerInfo(type, ((Consumer<ServiceEvent<?>>) (Object) handler)));
        return this;
    }
    private void callOnEmitFor(ServiceEvent<?> event) {
        listenerInfos.forEach(li -> {
            if(li.type.isEmpty() || li.type.equals(event.type)) li.handler.accept(event);
        });
    }

    public WebSocketHandler getWebSocketHandler() {
        return new EventsSession(sink.asFlux(), objectMapper);
    }
    public Flux<ServiceEvent<?>> getEvents() { // NOSONAR -- ? can be any data type
        return sink.asFlux();
    }

    public interface EventDataSource {
        Map<String,Object> getEventData();
    }

    @RequiredArgsConstructor
    public static class ServiceEvent<T> {
        public final long timestamp = System.currentTimeMillis();
        public final String type;
        public final T data;
    }

    @RequiredArgsConstructor
    private static final class EventsSession implements WebSocketHandler {
        private final Flux<ServiceEvent<?>> events;
        private final ObjectMapper objectMapper;
        private final AtomicReference<Set<String>> eventTypesToInclude = new AtomicReference<>(Set.of("*"));

        @Override
        public Mono<Void> handle(WebSocketSession session) {
            return session
                .send(events.filter(this::shouldBeIncluded).map(this::toJsonText).map(session::textMessage))
                .and(session.receive().map(WebSocketMessage::getPayloadAsText).map(this::handleMessage));
        }

        private String toJsonText(ServiceEvent<?> event) {
            return noThrow(() ->
                objectMapper.writeValueAsString(event), ex -> log.error("Unable to serialize event", ex)) // NOSONAR -- wrong compiler error without braces
                .orElseGet(() -> toJsonText(new ServiceEvent<>("error", "Unable to serialize " + event.getClass().getSimpleName())));
        }

        private boolean shouldBeIncluded(ServiceEvent<?> event) {
            final Set<String> types = eventTypesToInclude.get();
            if(types.contains("*")) return true;
            return types.contains(event.type);
        }

        private Mono<Void> handleMessage(String message) {
            // events:values -- example: events:REGISTERED,UNREGISTERED
            if(message.startsWith("events:")) {
                final Set<String> values = Set.of(message.split(":", 2)[1]);
                eventTypesToInclude.set(values);
            }
            return Mono.empty();
        }
    }
}
