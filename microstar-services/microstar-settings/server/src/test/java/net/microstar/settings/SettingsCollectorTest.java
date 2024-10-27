package net.microstar.settings;

import net.microstar.common.model.ServiceId;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.MiniBus;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.microstar.common.io.IOUtils.pathOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("SpellCheckingInspection")
class SettingsCollectorTest {
    final SettingsRepository repo;
    final SettingsCollector collector;
    final MicroStarApplication app;

    // Mix of .ylm and .yaml is intentional: it should handle both

    SettingsCollectorTest() {
        final Path testDir = getTestResourcePath();
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores", Map.of(
                "settings", Map.of("type", "filesystem", "root", testDir.toFile().getAbsolutePath())
            ))
        ));
        app = Mockito.mock(MicroStarApplication.class);
        repo = new SettingsRepository(Mockito.mock(DispatcherService.class), app, Mockito.mock(MiniBus.class));
        collector = new SettingsCollector(repo);
    }

    @AfterEach
    void cleanup() {
        repo.close();
        DynamicPropertiesManager.clearAllState();
    }

    @Test void shouldCollectFilesForServiceId() {
        final SettingsCollector.OrderedSettings settings = collector.getOrderedSettings(ServiceId.of("main/reflector"));
        assertThat(settings.files, is(Set.of("main-reflector.yaml", "reflector.yml", "parent.yml")));
        assertThat(settings.settings.getInteger("number").orElse(0), is(5));
        assertThat(settings.settings.get("name").orElse(""), is("reflector"));
        assertThat(settings.settings.get("parent"), is(Optional.of(true)));
    }
    @Test void shouldFilterOnActiveProfiles() {
        final SettingsCollector.OrderedSettings settingsA = collector.getOrderedSettings(ServiceId.of("main/reflector"), "a");
        final SettingsCollector.OrderedSettings settingsB = collector.getOrderedSettings(ServiceId.of("main/reflector"), "b");
        final SettingsCollector.OrderedSettings settingsC = collector.getOrderedSettings(ServiceId.of("main/reflector"), "c");
        final SettingsCollector.OrderedSettings settingsBC= collector.getOrderedSettings(ServiceId.of("main/reflector"), "b", "c");
        final SettingsCollector.OrderedSettings settingsD = collector.getOrderedSettings(ServiceId.of("main/reflector"), "d");
        final SettingsCollector.OrderedSettings settingsP = collector.getOrderedSettings(ServiceId.of("main/reflector"), "prof");

        assertThat(settingsA.settings.get("name").orElseThrow(), is("reflector:ab"));
        assertThat(settingsA.settings.get("abnum").orElseThrow(), is(1));
        assertThat(settingsA.settings.get("bcnum").isPresent(), is(false));
        assertThat(settingsA.settings.get("foo").orElseThrow(), is(true));
        assertThat(settingsA.files, is(Set.of("parent.yml", "reflector.yml", "main-reflector.yaml", "foo.yaml")));

        assertThat(settingsB.settings.get("name").orElseThrow(), is("reflector:bc"));
        assertThat(settingsB.settings.get("abnum").orElseThrow(), is(1));
        assertThat(settingsB.settings.get("bcnum").orElseThrow(), is(1));
        assertThat(settingsB.settings.get("foo").isPresent(), is(false));
        assertThat(settingsC.files, is(Set.of("parent.yml", "reflector.yml", "main-reflector.yaml")));

        assertThat(settingsC.settings.get("name").orElseThrow(), is("reflector:bc"));
        assertThat(settingsC.settings.get("abnum").isPresent(), is(false));
        assertThat(settingsC.settings.get("bcnum").orElseThrow(), is(1));
        assertThat(settingsC.settings.get("foo").isPresent(), is(false));

        assertThat(settingsBC.settings.get("name").orElseThrow(), is("reflector:bc"));
        assertThat(settingsBC.settings.get("abnum").orElseThrow(), is(1));
        assertThat(settingsBC.settings.get("bcnum").orElseThrow(), is(1));
        assertThat(settingsBC.settings.get("foo").isPresent(), is(false));

        assertThat(settingsD.settings.get("name").orElseThrow(), is("reflector"));
        assertThat(settingsD.settings.get("abnum").isPresent(), is(false));
        assertThat(settingsD.settings.get("bcnum").isPresent(), is(false));
        assertThat(settingsD.settings.get("foo").isPresent(), is(false));

        assertThat(settingsP.settings.get("name").orElseThrow(), is("reflector_prof"));
        assertThat(settingsP.settings.get("reflector-prof").orElseThrow(), is(true));
        assertThat(settingsP.settings.get("foo").isPresent(), is(false));
    }
    @Test void shouldIgnoreReferenceToSelf() {
        final SettingsCollector.OrderedSettings settings = collector.getOrderedSettings(ServiceId.of("recursive-self"));
        assertThat(settings.settings.get("recursive-self").orElseThrow(), is(true));
        assertThat(settings.files, is(Set.of("recursive-self.yaml")));
    }
    @Test void shouldLimitEndlessRecursion() {
        final SettingsCollector.OrderedSettings settings = collector.getOrderedSettings(ServiceId.of("recursive/1"));
        assertThat(settings.settings.get("recursive-1").orElseThrow(), is(true));
        assertThat(settings.settings.get("recursive-2").orElseThrow(), is(true));
        assertThat(settings.settings.get("recursive-3").orElseThrow(), is(true));
        assertThat(settings.settings.get("number").orElseThrow(), is(93));
        assertThat(settings.files, is(Set.of("recursive-1.yaml", "recursive-2.yaml", "recursive-3.yaml")));
    }

    private static Path getTestResourcePath() {
        return Optional.ofNullable(SettingsCollectorTest.class.getResource( "/" + SettingsCollectorTest.class.getSimpleName()))
            .map(r-> pathOf(r.getPath()))
            .filter(path -> Files.exists(path) && Files.isDirectory(path))
            .orElseThrow(() -> new IllegalStateException("No test resource dir found"));
    }
}