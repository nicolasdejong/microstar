package net.microstar.spring.mvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.logging.LogFiles;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
@Component
public class EventEmitter extends TextWebSocketHandler {
    private final ObjectMapper objectMapper;
    private final SimpMessagingTemplate messagingTemplate;
    private final AtomicReference<Set<String>> eventTypesToInclude = new AtomicReference<>(Set.of("*"));

    @SuppressWarnings("this-escape")
    public EventEmitter(ObjectMapper objectMapper, SimpMessagingTemplate messagingTemplate) {
        this.objectMapper = objectMapper;
        this.messagingTemplate = messagingTemplate;
        LogFiles.getInstance().setServiceEventsObserver(this::next);
    }

    public void next(String type) { next(new ServiceEvent<>(type, null)); }
    public void next(String type, Map<String,String> data) {
        next(new ServiceEvent<>(type, data));
    }
    public void next(String type, String message) {
        next(new ServiceEvent<>(type, Map.of("message", message)));
    }
    public void next(ServiceEvent<?> eventIn) {
        final ServiceEvent<?> event;
        if(eventIn.data instanceof EventDataSource source) {
            event = new ServiceEvent<>(eventIn.type, source.getEventData());
        } else {
            event = eventIn;
        }

        if(shouldBeIncluded(event)) {
            messagingTemplate.convertAndSend("/event-emitter", toJsonText(event));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage textMessage) {
        final String message = textMessage.getPayload();

        // events:values -- example: events:REGISTERED,UNREGISTERED
        if(message.startsWith("events:")) {
            final Set<String> values = Set.of(message.split(":", 2)[1]);
            eventTypesToInclude.set(values);
        }
    }

    public interface EventDataSource {
        Map<String,Object> getEventData();
    }

    @RequiredArgsConstructor
    public static class ServiceEvent<T> {
        public final String type;
        public final T data;
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
}
