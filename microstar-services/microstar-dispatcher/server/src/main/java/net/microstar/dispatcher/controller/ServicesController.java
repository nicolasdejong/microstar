package net.microstar.dispatcher.controller;

import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.model.ServicesForClient;
import net.microstar.dispatcher.services.ServicesService;
import net.microstar.spring.authorization.RequiresRole;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

import static net.microstar.spring.authorization.UserToken.ROLE_ADMIN;
import static net.microstar.spring.authorization.UserToken.ROLE_SERVICE;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping(
    value = "/services",
    produces = MediaType.APPLICATION_JSON_VALUE
)
public class ServicesController {
    private final ServicesService services;

    @GetMapping
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public ServicesForClient getServices() {
        return services.getServicesForClient();
    }

    @GetMapping("instance-ids")
    @RequiresRole({ROLE_SERVICE,ROLE_ADMIN})
    public ImmutableMap<UUID, ServiceId> getServiceInstanceIds() {
        return services.getServiceInstanceIds();
    }
}
