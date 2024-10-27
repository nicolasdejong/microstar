package net.microstar.spring.mvc.facade.dispatcher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.mvc.RestHelper;
import net.microstar.spring.settings.DynamicPropertyRef;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static net.microstar.common.io.IOUtils.concatPath;

@Slf4j
@Component
@RequiredArgsConstructor
public class Dispatcher {
    private final RestHelper restHelper;
    private final ObjectMapper objectMapper;
    private final DynamicPropertyRef<String> dispatcherUrl = DynamicPropertyRef.of("app.config.dispatcher.url")
        .withDefault("http://localhost:8080");


    public ImmutableMap<UUID, ServiceId> getServiceInstanceIds() {
        return restHelper.get(concatPath(dispatcherUrl, "/services/instance-ids"), new ParameterizedTypeReference<ImmutableMap<String,ServiceId>>() {})
            .orElseThrow()
            .entrySet().stream()
            .map(entry -> Map.entry(UUID.fromString(entry.getKey()), entry.getValue()))
            .collect(ImmutableUtil.toImmutableMap());
    }

    public void sendConfigurationRefreshEvent(UUID serviceId) {
        sendEvent(serviceId, "refresh-settings");
    }

    public <T> List<RelayResponse<T>> relay(RelayRequest request, ParameterizedTypeReference<T> type) {
        return restHelper.post(concatPath(dispatcherUrl, "relay"), request, new ParameterizedTypeReference<List<RelayResponse<T>>>() {})
            .orElseGet(Collections::emptyList);
    }
    public <T> Optional<RelayResponse<T>> relaySingle(RelayRequest request, ParameterizedTypeReference<T> type) {
        return restHelper.post(concatPath(dispatcherUrl, "relay"), request, new ParameterizedTypeReference<List<RelayResponse<String>>>() {})
            .orElseGet(Collections::emptyList)
            .stream()
            .findFirst()
            .flatMap(resp -> resp.content.map(text -> resp.setNewContent(Optional.of(convert(text, type)))));
    }


    @SuppressWarnings("SameParameterValue")
    private void sendEvent(UUID serviceId, String eventName) {
        restHelper.get(dispatcherUrl + "/" + serviceId + "/" + eventName, Void.class);
    }

    <T> T convert(String data, ParameterizedTypeReference<T> type) {
        final TypeReference<Object> responseTypeRef = new TypeReference<>(){
            public Type getType() { return type.getType(); }
        };
        try {
            //noinspection unchecked
            return (T)objectMapper.readValue(data, responseTypeRef);
        } catch (JsonProcessingException e) {
            throw new FatalException("Relay conversion error: " + Throwables.getRootCause(e).getMessage());
        }
    }
}