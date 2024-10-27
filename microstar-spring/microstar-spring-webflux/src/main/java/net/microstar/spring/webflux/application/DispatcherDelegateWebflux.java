package net.microstar.spring.webflux.application;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.model.ServiceRegistrationRequest;
import net.microstar.common.model.ServiceRegistrationResponse;
import net.microstar.spring.application.DispatcherDelegate;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.exceptions.MicroStarException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

@Slf4j
@Component
public class DispatcherDelegateWebflux extends DispatcherDelegate {
    private final WebClient webClient;
    private final AtomicBoolean registering = new AtomicBoolean(false);

    public DispatcherDelegateWebflux(WebClient.Builder webClientBuilder) {
        try {
            webClient = webClientBuilder
                .build();
        } catch (final Exception failed) {
            throw new MicroStarException("Unknown address: " + dispatcherUrl, failed);
        }
    }

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
        DynamicPropertiesManager.getProperty("app.config.localUrl").ifPresent(localUrl -> log.info("Using local url: {}", localUrl));

        return webClient
            .post()
            .uri(IOUtils.concatPath(dispatcherUrl, "/service/register"))
            .contentType(MediaType.APPLICATION_JSON)
            .headers(serviceToRegister::setHeaders)
            .bodyValue(ServiceRegistrationRequest.builder()
                .id(serviceToRegister.serviceId.combined)
                .instanceId(serviceToRegister.serviceInstanceId)
                .protocol(MicroStarApplication.getProtocol())
                .listenPort(serviceToRegister.getServerPort())
                .url(DynamicPropertiesManager.getProperty("app.config.localUrl").orElse(null))
                .startTime(serviceToRegister.startTime)
                .build())
            .retrieve()
            .bodyToMono(ServiceRegistrationResponse.class)
            .retryWhen(Retry
                .fixedDelay(Integer.MAX_VALUE, RETRY_INTERVAL)
                .jitter(RETRY_INTERVAL_JITTER)
                .filter(DispatcherDelegateWebflux::isAcceptableServerError)
            )
            .onErrorMap(t -> new FatalException("Registration failed: " + t.getMessage()))
            .blockOptional()
            .orElseThrow()
            ;
    }

    @Override
    public void unregister(MicroStarApplication serviceToUnregister) {
        if(!serviceToUnregister.isRegistered()) return;
        log.info("Unregistering from Dispatcher: {}", serviceToUnregister.serviceId.combined);
        webClient.post()
            .uri(dispatcherUrl + "/service/unregister")
            .headers(serviceToUnregister::setHeaders)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> logErrorResponseAndReturnEmpty("unregister", response))
            .toBodilessEntity()
            .block();
    }

    @Override
    public void aboutToRestart(MicroStarApplication serviceThatIsAboutToRestart) {
        final String dispatcherUrl = DynamicPropertiesManager.getProperty("app.config.dispatcher.url", "http://localhost:8080");
        log.info("Telling Dispatcher we are about to restart");
        webClient
            .post()
            .uri(IOUtils.concatPath(dispatcherUrl, "/service/about-to-restart"))
            .headers(serviceThatIsAboutToRestart::setHeaders)
            .retrieve()
            .onStatus(HttpStatusCode::isError, response -> logErrorResponseAndReturnEmpty("aboutToRestart", response))
            .toBodilessEntity()
            .block();
    }

    @Override
    protected Supplier<Boolean> isDispatcherAlive() {
        return () -> webClient
                .get()
                .uri(IOUtils.concatPath(dispatcherUrl, "/version"))
                .retrieve()
                .toEntity(String.class)
                .filter(resp -> resp.getStatusCode().is2xxSuccessful())
                .mapNotNull(HttpEntity::getBody)
                .map(v -> v.length() > 0)
                .switchIfEmpty(Mono.just(false))
                .onErrorResume(t -> Mono.just(false))
                .block(); // called by separate thread so blocking is ok
    }

    private static Mono<Throwable> logErrorResponseAndReturnEmpty(String reqName, ClientResponse response) {
        return response.bodyToMono(String.class)
            .publishOn(Schedulers.boundedElastic())
            .map(PropsMap::fromYaml)
            .doOnNext(bodyMap -> log.error("Sending '{}' FAILED: {}", reqName, bodyMap.get("error")))
            .flatMap(body -> Mono.empty());
    }

    private static boolean isAcceptableServerError(Throwable throwable) {
        return  !(throwable instanceof WebClientResponseException.BadRequest);
    }
}
