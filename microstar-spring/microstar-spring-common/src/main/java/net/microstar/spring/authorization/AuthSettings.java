package net.microstar.spring.authorization;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.spring.settings.DynamicProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@DynamicProperties("authentication")
@Builder @Jacksonized @ToString
public class AuthSettings {
    @Default public final Duration tokenTimeout = Duration.ofDays(7);
    @Default public final Optional<String> ssoUrl = Optional.empty();
    @Default public final Map<String,String> userPasswords = Collections.emptyMap();
    @Default public final Map<String, Set<String>> userRoles = Collections.emptyMap(); // these need to be set globally! in services.yml
}
