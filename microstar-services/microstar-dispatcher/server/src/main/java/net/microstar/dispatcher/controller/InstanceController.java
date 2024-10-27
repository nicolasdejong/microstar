package net.microstar.dispatcher.controller;

import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.services.Services;
import net.microstar.spring.exceptions.NotFoundException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(
    value = "/instance",
    consumes = MediaType.APPLICATION_JSON_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE
)
@RequiredArgsConstructor
public class InstanceController {
    private final Services services;

    @Builder
    public static class InstanceInfo { // TODO: move to facade/Dispatcher
        final ServiceId serviceId;
    }

    @GetMapping("/{instanceId}") // TODO: is this method used? This is probably picked up in the proxy
    public InstanceInfo getInstanceInfo(@PathVariable("instanceId") UUID instanceId) {
        log.warn("getInstanceInfo() called");
        return services.getServiceFrom(instanceId)
            .map(info -> InstanceInfo.builder().serviceId(info.id).build())
            .orElseThrow(() -> new NotFoundException("Unknown instanceId")); // don't log the instanceId
    }
}
