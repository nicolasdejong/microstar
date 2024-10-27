package net.microstar.spring.application;

import net.microstar.common.model.ServiceRegistrationResponse;
import net.microstar.spring.settings.DynamicPropertyRef;

import java.time.Duration;
import java.util.function.Supplier;

/** Dispatcher functionality with different implementations for mvc and webflux who each
  * have their own Bean implementing extending this class.
  */
public abstract class DispatcherDelegate {
    protected static final Duration RETRY_INTERVAL = Duration.ofSeconds(2);
    protected static final double   RETRY_INTERVAL_JITTER = 0.5;
    protected static final DynamicPropertyRef<String> dispatcherUrl = DynamicPropertyRef.of("app.config.dispatcher.url")
        .withDefault("http://localhost:8080");
    @SuppressWarnings("this-escape")
    public final ConnectionChecker connectionChecker = new ConnectionChecker(isDispatcherAlive());

    public abstract ServiceRegistrationResponse register(MicroStarApplication serviceToRegister);
    public abstract void unregister(MicroStarApplication serviceToUnregister);
    public abstract void aboutToRestart(MicroStarApplication serviceThatIsAboutToRestart);
    public void whenDisconnected(Runnable toCallWhenDisconnected) {
        connectionChecker.whenDisconnected(() -> {
            connectionChecker.stop();
            toCallWhenDisconnected.run();
        }).start();
    }

    protected abstract Supplier<Boolean> isDispatcherAlive();
}
