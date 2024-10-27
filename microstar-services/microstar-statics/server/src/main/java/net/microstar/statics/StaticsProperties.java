package net.microstar.statics;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.spring.settings.DynamicProperties;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@DynamicProperties("app.config.statics")
@Builder @Jacksonized @ToString
public class StaticsProperties {
    @Default public final boolean syncBetweenStars = true;
    @Default public final List<Target> targets = Collections.emptyList();
    @Default public final Optional<String> fallback = Optional.empty();
    @Default public final Map<String,List<String>> userTargets = new HashMap<>();
    @Default public final Map<String,List<String>> userGroups = new HashMap<>();
    @Default public final Map<String,String> notFoundMapping = new HashMap<>();

    @Builder @Jacksonized @ToString @EqualsAndHashCode
    public static class Target {
        @Default public final String from = "";
        @Default public final String to = "";
    }
}
