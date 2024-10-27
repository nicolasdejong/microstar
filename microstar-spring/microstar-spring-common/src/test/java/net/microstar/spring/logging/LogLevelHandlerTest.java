package net.microstar.spring.logging;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@Slf4j
class LogLevelHandlerTest {

    @BeforeEach
    void init() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.a.b.c", "INFO"
        )));
        LogLevelHandler.update(); // also let its static constructor run
    }
    @AfterEach
    void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void changingConfigurationLevelShouldChangeLevelsOfLoggers() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.net.microstar.spring.logging.LogLevelHandlerTest", "DEBUG"
        )));
        assertThat(log.isDebugEnabled(), is(true));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.net.microstar.spring.logging.LogLevelHandlerTest", "INFO"
        )));
        assertThat(log.isDebugEnabled(), is(false));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.net.microstar.spring.logging.LogLevelHandlerTest", "DEBUG"
        )));
        assertThat(log.isDebugEnabled(), is(true));

        DynamicPropertiesManager.clearAllState(); // clear previous setting
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.net.microstar.spring.*", "ERROR"
        )));
        assertThat(log.isDebugEnabled(), is(false));
        assertThat(log.isErrorEnabled(), is(true));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.net.microstar.spring", "DEBUG"
        )));
        assertThat(log.isDebugEnabled(), is(true));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(ImmutableUtil.mapOf(
            "logging.level.net.microstar.spring", "INFO"
        )));
        assertThat(log.isDebugEnabled(), is(false));
    }
}