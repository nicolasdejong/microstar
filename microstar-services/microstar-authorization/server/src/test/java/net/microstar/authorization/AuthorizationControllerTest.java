package net.microstar.authorization;

import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.authorization.UserToken;
import net.microstar.spring.exceptions.NotAuthorizedException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(OutputCaptureExtension.class)
class AuthorizationControllerTest {
    private static final String USER_NAME = "someUser";
    private static final String USER_EMAIL = "someUser@someDomain.net";
    private static final String USER_PASSWORD = "secret";
    private final EncryptionSettings encSettings = EncryptionSettings.builder().build();

    @AfterEach
    void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void loginShouldReturnToken() {
        final AuthorizationController controller = new AuthorizationController();
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of("authentication.userPasswords", Map.of(
            USER_NAME, encSettings.hash(USER_PASSWORD)
        ))));
        final String tokenString = controller.login(USER_NAME, Optional.of(USER_EMAIL), USER_PASSWORD).blockOptional().orElseThrow();
        final UserToken token = UserToken.fromTokenString(tokenString);

        assertThat(token.name, is(USER_NAME));
        assertThat(token.email, is(USER_EMAIL));
    }
    @Test void loginShouldDelayAndFailOnWrongPassword() {
        final AuthorizationController controller = new AuthorizationController();
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of("authentication.userPasswords", Map.of(
            USER_NAME, encSettings.hash(USER_PASSWORD)
        ))));
        final long t1 = System.currentTimeMillis();
        assertThrows(NotAuthorizedException.class, () -> controller.login(USER_NAME, Optional.empty(), "wrong password").blockOptional().orElseThrow());
        final long t2 = System.currentTimeMillis();
        assertThat(t2 - t1, is(greaterThanOrEqualTo(100L)));
    }
    @Test void hashShouldReturnHashString() {
        final String hash = encSettings.hash(USER_PASSWORD);
        assertThat(hash.length(), greaterThan(USER_PASSWORD.length()));
    }

    @Test void defaultAdminPasswordShouldLeadToWarning(CapturedOutput output) {
        final Mono<String> token = new AuthorizationController().login("user", Optional.empty(), "dummyPassword");
        assertThat(token.map(String::length).block(), greaterThan(30));
        assertThat(output.getOut(), containsString("NO USERS CONFIGURED"));
    }
}