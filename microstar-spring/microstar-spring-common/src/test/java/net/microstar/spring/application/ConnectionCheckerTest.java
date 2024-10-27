package net.microstar.spring.application;

import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;

class ConnectionCheckerTest {

    @AfterAll static void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void shouldFollowConnectionState() {
        final int checkTime = 500; // high times are slow but are more resistant to system jitter
        DynamicPropertiesManager.setProperty("app.config.connection-check-interval", (checkTime - 50) + "ms");
        final AtomicLong lastCheckTime = new AtomicLong(0);
        final AtomicBoolean connected = new AtomicBoolean(false);
        final ConnectionChecker conState = new ConnectionChecker(() -> { lastCheckTime.set(now()); return connected.get(); });
        conState.start();

        assertThat(conState.isConnected(), is(false));
        sleep(checkTime);
        assertThat(ago(lastCheckTime.get()), lessThanOrEqualTo(checkTime));
        assertThat(conState.isConnected(), is(false));

        connected.set(true);
        sleep(checkTime);
        assertThat(conState.isConnected(), is(true));
        assertThat(ago(lastCheckTime.get()) + " > " + checkTime, ago(lastCheckTime.get()), lessThanOrEqualTo(checkTime));

        sleep(checkTime);
        assertThat(conState.isConnected(), is(true));
        assertThat(ago(lastCheckTime.get()), lessThanOrEqualTo(checkTime));

        connected.set(false);
        sleep(checkTime);
        assertThat(conState.isConnected(), is(false));
        assertThat(ago(lastCheckTime.get()), lessThanOrEqualTo(checkTime));

        conState.stop();
        connected.set(true);
        sleep(checkTime);
        assertThat(ago(lastCheckTime.get()), greaterThanOrEqualTo(checkTime));
    }

    private static long now() { return System.currentTimeMillis(); }
    private static int ago(long t) { return (int)(now() - t); }
}