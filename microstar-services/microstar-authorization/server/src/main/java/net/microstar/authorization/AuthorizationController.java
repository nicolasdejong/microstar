package net.microstar.authorization;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.util.UserTokenBase;
import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.authorization.AuthSettings;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@RestController
@RequestMapping
public class AuthorizationController {

    private final DynamicPropertiesRef<AuthSettings> settings = DynamicPropertiesRef.of(AuthSettings.class)
        .onChange(unused -> warnWhenNoUsersConfigured());
    private final DynamicPropertiesRef<EncryptionSettings> encSettings = DynamicPropertiesRef.of(EncryptionSettings.class);


    @PostMapping(value = { "/login/{name}/{email}", "/login/{name}" }, produces = "text/plain") // also matches on "/file"
    public Mono<String> login(@PathVariable("name") String username,
                              @PathVariable(name = "email", required = false) Optional<String> email,
                              @RequestHeader("X-AUTH-PASSWORD") String password) {
        if(!settings.get().userPasswords.isEmpty()) {
            final String pwHash = encSettings.get().hash(password);
            final String expectedHash = Optional.ofNullable(settings.get().userPasswords.get(username)).orElse("noHash");

            if (!pwHash.equals(expectedHash)) {
                log.warn("Failed login attempt for user {}", username);
                // noinspection UnsecureRandomNumberGeneration
                return Mono // NOSONAR -- add a delay before error so there is a penalty to giving a wrong password
                    .delay(Duration.ofMillis(100 + (long) (Math.random() * 1000)))
                    .then(Mono.error(new NotAuthorizedException("Incorrect username / password combination")));
            }
        } else {
            log.warn("NO USERS CONFIGURED! Accepting all logins without checking password");
            log.info("Accepting login for user {} without checking password because NO USERS HAVE BEEN CONFIGURED!", username);
        }

        return Mono.just(UserTokenBase.builder()
            .name(username)
            .email(email.orElse(""))
            .build()
            .toTokenString(MicroStarConstants.CLUSTER_SECRET));
    }

    @GetMapping("/token")
    public ResponseEntity<Object> getToken() {
        return settings.get().ssoUrl.map(ssoUrl -> ResponseEntity
            .status(HttpStatus.TEMPORARY_REDIRECT)
            .location(URI.create(ssoUrl))
            .build()
        ).orElseThrow(() -> new NotAuthorizedException("No SSO configured"));
    }

    private void warnWhenNoUsersConfigured() {
        if(settings.get().userPasswords.isEmpty()) {
            log.warn("WARNING: !NO USERS CONFIGURED! Add at least one user in authentication.userPasswords and give it the ADMIN role");
        }
    }
}
