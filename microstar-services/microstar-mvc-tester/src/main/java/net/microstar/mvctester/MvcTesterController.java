package net.microstar.mvctester;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.mvc.facade.dispatcher.Dispatcher;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping(
    consumes = MediaType.ALL_VALUE,
    produces = MediaType.APPLICATION_JSON_VALUE
)
@AllArgsConstructor
public class MvcTesterController {
    private final MicroStarApplication microstarApplication;
    private final Dispatcher dispatcher;

    @RequestMapping(value = "/ok", produces = MediaType.TEXT_PLAIN_VALUE)
    public String ok(UserToken userToken) {
        return "ok -- token: " + userToken;
    }

    @RequestMapping(value ="/version", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<ServiceId> version() {
        return ResponseEntity.ok(microstarApplication.serviceId);
    }

    @GetMapping(value = "/test-relay", produces = MediaType.TEXT_PLAIN_VALUE)
    public String testRelay() {
        return dispatcher.relay(RelayRequest.forGet("microstar-settings/files").build(), new ParameterizedTypeReference<List<Map<String,Object>>>() {})
            .stream()
            .map(resp -> resp == null ? "NODATA" : resp.content.orElseGet(Collections::emptyList).toString())
            .reduce((a, b) -> a + "\n---\n" + b)
            .orElse("");
    }

}
