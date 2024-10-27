package net.microstar.spring.webflux;

import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class MiniBusTest {

    @Test void testSubscriptions() {
        final DispatcherService dispatcher = Mockito.mock(DispatcherService.class);
        final MiniBus miniBus = new MiniBus(dispatcher);
        final MiniBus.Event event = MiniBus.Event.builder().topic("someTopic").message("someMessage").build();
        final AtomicInteger callCount = new AtomicInteger(0);
        final Consumer<MiniBus.Event> eventHandler = evt -> {
            assertThat(evt, is(event));
            callCount.incrementAndGet();
        };
        when(dispatcher.postBusMessage(any(MiniBus.BusMessage.class)))
            .thenAnswer(inv -> Mono.fromRunnable(() -> miniBus.handleExternalMessage(inv.getArgument(0))));

        miniBus.handleExternalMessage(event); // not yet subscribed
        assertThat(callCount.get(), is(0));

        final Runnable unsubscribe = miniBus.subscribe("someTopic", eventHandler);
        miniBus.handleExternalMessage(event);
        assertThat(callCount.get(), is(1));

        unsubscribe.run();

        miniBus.handleExternalMessage(event); // no longer subscribed
        assertThat(callCount.get(), is(1));

        final Runnable unsubscribe2 = miniBus.subscribe("someOtherTopic", eventHandler);
        miniBus.handleExternalMessage(event);
        assertThat(callCount.get(), is(1)); // different topic

        unsubscribe2.run();
    }
}