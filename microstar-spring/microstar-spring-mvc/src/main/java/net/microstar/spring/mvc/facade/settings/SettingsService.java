package net.microstar.spring.mvc.facade.settings;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.spring.mvc.RestHelper;
import net.microstar.spring.settings.DynamicPropertyRef;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SettingsService {
    private final RestHelper restHelper;
    private final DynamicPropertyRef<String> dispatcherUrl = DynamicPropertyRef.of("app.config.dispatcher.url")
        .withDefault("http://localhost:8080");
    private final DynamicPropertyRef<String> activeProfiles = DynamicPropertyRef.of("spring.profiles.active")
        .withDefault("default");

    public String getSettings() {
        return restHelper.get(dispatcherUrl + "/microstar-settings/combined/" + activeProfiles, String.class).orElse("");
    }
}
