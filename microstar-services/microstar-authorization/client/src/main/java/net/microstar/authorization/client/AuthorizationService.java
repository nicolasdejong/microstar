package net.microstar.authorization.client;

import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.webflux.AbstractServiceClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Slf4j
@Service
public class AuthorizationService extends AbstractServiceClient {
    public AuthorizationService(WebClient.Builder webClientBuilder) {
        super("microstar-authorization", webClientBuilder);
    }

    public Mono<String> login(String username, String email, String password) {
        return post(String.class)
                .user(UserToken.GUEST_TOKEN)
                .path("/login", username, email)
                .header("X-AUTH-PASSWORD", password)
                .call();
    }
}
