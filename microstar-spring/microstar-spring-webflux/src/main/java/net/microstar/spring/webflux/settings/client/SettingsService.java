package net.microstar.spring.webflux.settings.client;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.settings.model.SettingsFile;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.webflux.AbstractServiceClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class SettingsService extends AbstractServiceClient {
    public SettingsService(WebClient.Builder webClientBuilder) {
        super("microstar-settings", webClientBuilder);
    }

    // Only endpoints that have role ROLE_SERVICE are implemented here


    public Mono<String> getFile(String name) {
        return get(String.class).path("/file", name).call();
    }
    public Mono<List<SettingsFile>> getFiles() {
        return get(new ParameterizedTypeReference<List<SettingsFile>>() {}).path("/files").call();
    }

    public Mono<List<String>> getAllHistoryNames() {
        return get(new ParameterizedTypeReference<List<String>>() {}).path("/all-history-names").call();
    }

    public Mono<Map<String,Object>> getSettings(List<String> profiles, UUID serviceInstanceId, ServiceId serviceId) {
        return get(new ParameterizedTypeReference<Map<String,Object>>() {})
                .path("/combined", String.join(",",profiles))
                .header(MicroStarConstants.HEADER_X_SERVICE_UUID, serviceInstanceId)
                .header(MicroStarConstants.HEADER_X_SERVICE_ID, serviceId)
                .header(MicroStarConstants.HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET)
                .call();
    }

    public Mono<String> getSettings() {
        final String activeProfiles = DynamicPropertiesManager.getProperty("spring.profiles.active").orElse("default");
        return get(String.class).path("/combined/" + String.join(",",activeProfiles)).call();
    }

    public Mono<Void> updateSettingsFile(String filename, String newContents, UserToken userToken) {
        return post().path("/store", filename).user(userToken).bodyValue(newContents).call();
    }

    public Mono<Void> refresh(String serviceName) {
        return serviceName.isEmpty() ? Mono.empty() : post().path("/refresh/" + serviceName).call();
    }
    public Mono<Void> refreshAll() {
        return post().path("/refresh").call();
    }
}
