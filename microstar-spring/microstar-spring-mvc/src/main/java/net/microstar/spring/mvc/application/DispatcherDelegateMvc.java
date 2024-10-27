package net.microstar.spring.mvc.application;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceRegistrationRequest;
import net.microstar.common.model.ServiceRegistrationResponse;
import net.microstar.spring.application.DispatcherDelegate;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.mvc.RestHelper;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;

@Slf4j
@Component
@RequiredArgsConstructor
public class DispatcherDelegateMvc extends DispatcherDelegate {
    private final RestHelper restHelper;
    private final AtomicBoolean registering = new AtomicBoolean(false);

    @Override
    public ServiceRegistrationResponse register(MicroStarApplication serviceToRegister) {
        if (registering.compareAndExchange(false, true)) {
            throw new IllegalStateException("Already registering");
        }
        try {
            return doRegister(serviceToRegister);
        } finally {
            registering.set(false);
        }
    }

    private ServiceRegistrationResponse doRegister(MicroStarApplication serviceToRegister) {
        log.info("Attempting to register {} on local port {} with Dispatcher at {}",
            serviceToRegister.serviceId.combined,
            serviceToRegister.getServerPort(),
            dispatcherUrl.getOptional().orElse("<unknown dispatcher location>")
        );

        final ServiceRegistrationRequest regRequest =
            ServiceRegistrationRequest.builder()
                .id(serviceToRegister.serviceId.combined)
                .instanceId(serviceToRegister.serviceInstanceId)
                .protocol(MicroStarApplication.getProtocol())
                .listenPort(serviceToRegister.getServerPort())
                .startTime(serviceToRegister.startTime)
                .build();

        for(int attempt = 1;; attempt++) {
            final Optional<ServiceRegistrationResponse> response =
                restHelper.post(dispatcherUrl + "/service/register", regRequest, ServiceRegistrationResponse.class, (status, error) -> {
                    if(status == 400) {
                        final String msg = "Registration not accepted by dispatcher -- exit";
                        log.error(msg);
                        throw new FatalException(msg);
                    }
                });

            if(response.isPresent()) return response.get();

            if (attempt == 1) {
                log.info("Failed to connect with Dispatcher -- retrying...");
            }
            //noinspection UnsecureRandomNumberGeneration,MagicNumber -- not security related here, just jitter
            sleep((long) (RETRY_INTERVAL.toMillis() + ((Math.random() - 0.5) * RETRY_INTERVAL_JITTER * RETRY_INTERVAL.toMillis())));
        }
    }

    @Override
    public void unregister(MicroStarApplication serviceToUnregister) {
        if(!serviceToUnregister.isRegistered()) return;
        log.info("Unregistering from Dispatcher: {}", serviceToUnregister.serviceId.combined);
        restHelper.post(dispatcherUrl + "/service/unregister");
    }

    @Override
    public void aboutToRestart(MicroStarApplication serviceThatIsAboutToRestart) {
        log.info("Telling Dispatcher we are about to restart");
        restHelper.post(IOUtils.concatPath(dispatcherUrl, "/service/about-to-restart"));
    }

    @Override
    protected Supplier<Boolean> isDispatcherAlive() {
        return () -> noThrow(() -> restHelper
                .get(IOUtils.concatPath(dispatcherUrl.get(), "version"), String.class)
                .map(v -> v.length() > 0)
                .orElseThrow()
        ).orElse(false);
    }
}
