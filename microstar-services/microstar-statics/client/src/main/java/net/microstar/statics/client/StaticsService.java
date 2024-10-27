package net.microstar.statics.client;

import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.webflux.AbstractServiceClient;
import net.microstar.statics.model.OverviewItem;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StaticsService extends AbstractServiceClient {
    public StaticsService(WebClient.Builder webClientBuilder) {
        super("microstar-statics/", webClientBuilder);
    }

    public Mono<Map<String, List<String>>> getUserTargets(UserToken user) {
        return get(new ParameterizedTypeReference<Map<String,List<String>>>() {}).path("/user-targets").user(user).call();
    }
    public Mono<Void> setUserTargets(UserToken user, Map<String,List<String>> newUserTargets) {
        return post().path("/user-targets").user(user).bodyValue(newUserTargets).call();
    }
    public Mono<Void> setUserTarget(String username, String target) {
        return post().path("/user-target", username, target).call();
    }

    public Mono<List<String>> getDirectoryContents(String name) {
        return get(new ParameterizedTypeReference<List<String>>() {}).path("/dir", name).call();
    }
    public Mono<Void> createDirectory(String name) {
        return post().path("/dir", name).call();
    }
    public Mono<Void> renameDirectory(String from, String to) {
        return post().path("/dir/rename", from).param("to", to).call();
    }
    public Mono<Void> deleteDirectory(String name) {
        return delete().path("/dir", name).call();
    }

    public Mono<List<OverviewItem>> list() {
        return get(new ParameterizedTypeReference<List<OverviewItem>>(){}).path("/list").call();
    }

    public Mono<Void> fileWasChanged(String name) {
        return post().path("/file-change", name).call();
    }
}
