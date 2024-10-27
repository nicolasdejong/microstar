package net.microstar.spring.authorization;

import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class AuthSettingsTest {

    @AfterEach
    void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void shouldSupportStringRoles() {
        final String settingsText = "{`authentication.userRoles`:{`admin`:`ADMIN`,`foo`:`BAR`,`[some.user@domain.org]`:`ADMIN`,`another`:[`ADMIN`,`ALSO`]}}".replace("`", "\"");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromYaml(settingsText));
        final AuthSettings authSettings = DynamicPropertiesManager.getInstanceOf(AuthSettings.class);

        assertThat(authSettings.userRoles.get("some.user@domain.org"), is(Set.of("ADMIN")));
        assertThat(authSettings.userRoles.get("another"), is(Set.of("ADMIN","ALSO")));
    }
}