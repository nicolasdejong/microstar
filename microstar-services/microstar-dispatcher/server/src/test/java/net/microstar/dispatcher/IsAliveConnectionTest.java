package net.microstar.dispatcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class IsAliveConnectionTest {
    private static final Duration MAX_INIT_DURATION = Duration.ofMillis(500);
    private static final Duration MAX_SILENCE_DURATION = Duration.ofMillis(100);
    private static final long CONDITION_TIMEOUT_MS = 500;
    private static final long CONDITION_LOOP_SLEEP_MS = 25;
    private IsAliveConnection serverNode;
    private IsAliveConnection clientNode;

    @BeforeEach void init() {
        serverNode = new IsAliveConnection(MAX_INIT_DURATION, MAX_SILENCE_DURATION, "TestServer").start();
        waitUntilCondition(() -> !serverNode.isInitializing());
        clientNode = new IsAliveConnection(MAX_INIT_DURATION, MAX_SILENCE_DURATION, serverNode.getAddress(), "TestClient").start();
        waitUntilCondition(clientNode::isRunning);
    }
    @AfterEach void cleanup() {
        serverNode.stop();
        clientNode.stop();
    }

    @Test void connectionShouldBeEstablished() {
        assertThat(serverNode.isConnected(), is(true));
        assertThat(clientNode.isConnected(), is(true));
    }

    @Test void connectionShouldDisconnectOnStop() {
        serverNode.stop();
        clientNode.stop();
        waitUntilCondition(() -> !serverNode.isConnected() && !clientNode.isConnected());
        assertThat(serverNode.isConnected(), is(false));
        assertThat(clientNode.isConnected(), is(false));
    }

    @Test void stoppingServerNodeShouldBeDetectedByClientNode() {
        sleep(MAX_SILENCE_DURATION.toMillis() * 3); // wait several cycles
        assertThat(serverNode.isConnected(), is(true));
        assertThat(clientNode.isConnected(), is(true));

        final int[] failCallCount = { 0 };
        clientNode.whenConnectionIsLost(() -> failCallCount[0]++);

        serverNode.stop();
        waitUntilCondition(() -> !serverNode.isConnected());
        sleep(MAX_SILENCE_DURATION.toMillis() + 100); // takes interval time before client notices
        assertThat(clientNode.isConnected(), is(false));
        assertThat(failCallCount[0], is(1));
    }

    @Test void stoppingClientNodeShouldBeDetectedByServerNode() {
        sleep(MAX_SILENCE_DURATION.toMillis() * 3); // wait several cycles
        assertThat(serverNode.isConnected(), is(true));
        assertThat(clientNode.isConnected(), is(true));

        final int[] failCallCount = { 0 };
        serverNode.whenConnectionIsLost(() -> failCallCount[0]++);

        clientNode.stop();
        waitUntilCondition(() -> !clientNode.isConnected());
        sleep(MAX_SILENCE_DURATION.toMillis() + 100); // takes interval time before server notices
        assertThat(serverNode.isConnected(), is(false));
        assertThat(failCallCount[0], is(1));
    }

    private static void waitUntilCondition(BooleanSupplier conditionToWaitFor) {
        if(conditionToWaitFor.getAsBoolean()) return;
        final long timeout = System.currentTimeMillis() + CONDITION_TIMEOUT_MS;
        while( System.currentTimeMillis() < timeout && !conditionToWaitFor.getAsBoolean()) sleep(CONDITION_LOOP_SLEEP_MS);
        if(!conditionToWaitFor.getAsBoolean()) throw new IllegalStateException("Timeout waiting for condition");
    }
}