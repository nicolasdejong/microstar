package net.microstar.dispatcher.controller;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.common.model.ServiceRegistrationRequest;
import net.microstar.common.model.ServiceRegistrationResponse;
import net.microstar.common.util.Threads;
import net.microstar.common.util.Utils;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.services.ServicesService;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.RequiresRole;
import net.microstar.spring.exceptions.RestartingException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.UUID;

import static net.microstar.common.MicroStarConstants.HEADER_X_SERVICE_UUID;
import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping(
    value = "/service",
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE
)
public class ServiceController {
    private final MicroStarApplication application;
    private final ServicesService services;
    private final WebClient.Builder webClientBuilder;

    @PostConstruct void started() {
        MicroStarApplication.get().ifPresent(MicroStarApplication::setIsActive);
    }

    @PostMapping("/register")
    @RequiresRole(ROLE_SERVICE)
    public ServiceRegistrationResponse register(@RequestBody ServiceRegistrationRequest reg,
                                                ServerWebExchange exchange,
                                                @Value("${spring.profiles.active:default}") String activeProfiles) {
        final InetSocketAddress address = getListenAddressOf(exchange, reg.listenPort);
        final ServiceInfoRegistered serviceInfo = services.register(reg, address);

        if("microstar-settings".equals(ServiceId.of(reg.id).name)) {
            log.info("Request settings from just registered settings service at {}", serviceInfo.baseUrl);
            Threads.execute(() -> {
                try {
                    refreshSettings(serviceInfo.baseUrl, activeProfiles);
                    services.tellServicesToRequestSettings().block();
                } catch(final RestartingException restarting) {
                    log.info("RefreshSettings caused a Spring restart");
                }
            });
        }
        return ServiceRegistrationResponse.builder()
            .serviceInstanceId(serviceInfo.serviceInstanceId)
            .isAlivePort(serviceInfo.isAlivePort)
            .build();
    }

    @PostMapping(value = "/unregister", consumes = MediaType.ALL_VALUE)
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public void unregister(@RequestHeader(HEADER_X_SERVICE_UUID) UUID serviceInstanceId) {
        services.unregister(serviceInstanceId);
    }

    @PostMapping(value = "/about-to-restart", consumes = MediaType.ALL_VALUE)
    @RequiresRole(ROLE_SERVICE)
    public void aboutToRestart(@RequestHeader(HEADER_X_SERVICE_UUID) UUID serviceInstanceId) {
        services.aboutToRestart(serviceInstanceId);
    }

    private static InetSocketAddress getListenAddressOf(ServerWebExchange exchange, int listenPort) {
        return new InetSocketAddress(Optional
            .ofNullable(exchange.getRequest().getRemoteAddress())
            .map(InetSocketAddress::getAddress)
            .orElseThrow(), // this is unlikely: an exchange always has an address that initiated the request
            listenPort);
    }

    private void refreshSettings(String settingsUrl, String activeProfiles) {
        webClientBuilder
            .baseUrl(String.join("/combined/", settingsUrl, activeProfiles))
            .defaultHeaders(application::setHeaders)
            .build()
            .get()
            .retrieve()
            .bodyToMono(String.class)
            .blockOptional()
            .ifPresent(settingsText -> {
                log.info("Received settings: " + settingsText);

                AppSettings.handleExternalSettingsText(Utils.firstNotNull(settingsText, ""));
            });
    }
}
