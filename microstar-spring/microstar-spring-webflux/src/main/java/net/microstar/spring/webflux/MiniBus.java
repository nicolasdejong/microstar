package net.microstar.spring.webflux;

import lombok.RequiredArgsConstructor;
import lombok.experimental.SuperBuilder;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.util.Threads;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Simple bus to all services. For now events only. In the future also request/response
  * and worker queues may be added. But for now this bus just supports posting events
  * and subscribing for events via topics.<p>
  *
  * Current implementation is only for Spring-webflux (due to the dependence on DispatcherService).
  */
@Service
@RequiredArgsConstructor
public class MiniBus {
    private final Map<String, List<Consumer<BusMessage>>> topicToSubscribers = new ConcurrentHashMap<>();
    private final DispatcherService dispatcher;

    @Jacksonized @SuperBuilder
    public static class BusMessage {
        public final String topic;
        public final String message;
    }

    @Jacksonized @SuperBuilder
    public static class Event extends BusMessage {
        public final String name;
    }

    /** Post the given event on the bus and distribute it to all services connected to the bus */
    public void post(Event event) {
        // Dispatcher will distribute this event over the minibus *including* the current service
        Threads.execute(() -> dispatcher.postBusMessage(event).block());
    }

    /** Calls subscribers of this instance for the received event */
    public <M extends BusMessage> void handleExternalMessage(M busMessage) {
        topicToSubscribers.computeIfAbsent(busMessage.topic, k -> new ArrayList<>())
            .forEach(handler -> handler.accept(busMessage));
    }

    /** Subscribe to a topic for handlers to be called when events are received.
      * Returns a runnable that will unsubscribe.
      */
    public Runnable subscribe(String topic, Consumer<Event> handler) {
        final Consumer<BusMessage> messageHandler = message -> {
            if(message instanceof Event event) handler.accept(event);
        };
        final Runnable unsubscribe = () -> getHandlers(topic).remove(messageHandler);
        getHandlers(topic).add(messageHandler);
        return unsubscribe;
    }

    private List<Consumer<BusMessage>> getHandlers(String topic) {
        return topicToSubscribers.computeIfAbsent(topic, k -> new ArrayList<>());
    }
}
