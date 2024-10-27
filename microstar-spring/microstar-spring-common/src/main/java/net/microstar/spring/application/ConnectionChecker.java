package net.microstar.spring.application;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.ThreadBuilder;
import net.microstar.spring.settings.DynamicPropertyRef;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
public class ConnectionChecker {
    private static final DynamicPropertyRef<Duration> checkIntervalRef = DynamicPropertyRef.of("app.config.connection-check-interval", Duration.class).withDefault(Duration.ofSeconds(5));
    private static final Supplier<Duration> maxConnectionIntervalRef = () -> Duration.ofMillis(checkIntervalRef.get().toMillis() * 2);
    private final Supplier<Boolean> isConnectedSupplier;
    private Runnable whenDisconnectedRunner = () -> {};
    private long lastCheckTime = 0;
    private long lastConnectedTime = 0;
    private @Nullable Thread thread;
    private final Set<Thread> threadsToStop = new HashSet<>();

    public ConnectionChecker(Supplier<Boolean> isConnectedSupplier) {
        this.isConnectedSupplier = isConnectedSupplier;
    }

    public ConnectionChecker whenDisconnected(Runnable whenDisconnectedRunner) {
        this.whenDisconnectedRunner = whenDisconnectedRunner;
        return this;
    }

    public ConnectionChecker start() {
        if(thread == null) thread = new ThreadBuilder().isDaemon(true).name("ConnectionChecker").run(this::loop);
        return this;
    }
    public void stop() {
        final @Nullable Thread t = thread;
        thread = null;
        if(t != null) {
            synchronized(threadsToStop) { threadsToStop.add(t); threadsToStop.notifyAll(); }
        }
    }
    public boolean isRunning() { return thread != null; }
    public void setIsConnected() { lastCheckTime = lastConnectedTime = now(); }
    public boolean isConnected() { return connectedAgo() < maxConnectionIntervalRef.get().toMillis(); }

    private void loop() {
        try {
            while (!threadShouldStop()) {
                sleep();
                if (threadShouldStop()) break; // in case stopped during sleep

                if (!isConnected() || checkedAgo() >= checkIntervalRef.get().toMillis()) {
                    if (!checkIsConnected()) {
                        lastConnectedTime = now() - maxConnectionIntervalRef.get().toMillis();
                        whenDisconnectedRunner.run();
                    } else setIsConnected();
                }
            }
        } finally {
            threadsToStop.remove(Thread.currentThread());
        }
    }
    private boolean threadShouldStop() {
        synchronized (threadsToStop) {
            return threadsToStop.contains(Thread.currentThread());
        }
    }
    private void sleep() {
        synchronized (threadsToStop) {
            noThrow(() -> threadsToStop.wait(checkIntervalRef.get().toMillis() / 3));
        }
    }

    private boolean checkIsConnected() { return noThrow(isConnectedSupplier::get).orElse(false); }
    private long checkedAgo() { return now() - lastCheckTime; }
    private long connectedAgo() { return now() - lastConnectedTime; }

    private static long now() { return System.currentTimeMillis(); }
}
