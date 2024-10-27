package net.microstar.dispatcher.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.dispatcher.model.ServiceInfoJar;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.DataStores;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.MiniBus;
import net.microstar.testing.TestUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static net.microstar.common.util.ExceptionUtils.noCheckedThrow;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceJarsManagerTest {
    private static final String SERVICE1_JAR = "group_name_123.jar";
    private static final String SERVICE2_JAR = "group_name2_234.jar";
    private static final String SERVICE3_JAR = "group3_name3_345.jar";
    @SuppressWarnings({"NotNullFieldNotInitialized", "InstanceVariableMayNotBeInitialized"})
    private ServiceJarsManager serviceJarsManager; // set by each test
    @SuppressWarnings({"NotNullFieldNotInitialized", "InstanceVariableMayNotBeInitialized"})
    private DynamicReferenceNotNull<DataStore> store; // set by each test

    @Mock private Services services;
    @Mock private ServiceVariations serviceVariations;
    @Mock private StarsManager starsManager;
    @Mock private EventEmitter eventEmitter;
    @Mock private MiniBus miniBus;

    @AfterEach void teardown() {
        serviceJarsManager.close();
        DataStores.closeAll();
        DynamicPropertiesManager.clearAllState();
    }

    private void initTest() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "microstar.dataStores.unit-test", Map.of("type", "memory"),
            "app.config.dispatcher.jars.stores", "unit-test"
        )));
        store = DataStores.get("unit-test");
        when(starsManager.sendEventToOtherStars(any(String.class), any(String.class))).thenReturn(Mono.empty());
        serviceJarsManager = new ServiceJarsManager(services, starsManager, eventEmitter, new ObjectMapper(), miniBus);
        serviceJarsManager.init();
    }

    @Test void shouldIncludeExistingJarsWhenStarting() {
        when(services.getOrCreateServiceVariations(any(ServiceId.class))).thenReturn(serviceVariations);
        when(services.register(any(ServiceInfoJar.class))).thenReturn(null);
        when(serviceVariations.isKnownService(any())).thenReturn(false);

        initTest();
        createDummyJar(SERVICE1_JAR);

        assertThat(serviceJarsManager.getJars().stream().map(ji->ji.name).toList(), is(List.of(SERVICE1_JAR)));

        verify(services)
            .register(
                new ServiceInfoJar(
                    ServiceId.of("group", "name", "123"),
                    JarInfo.builder().name(SERVICE1_JAR).store(store).build()
                )
            );
    }
    @Test void newJarsShouldBeAddedAsService() {
        when(services.getOrCreateServiceVariations(any(ServiceId.class))).thenReturn(serviceVariations);
        when(serviceVariations.isKnownService(any())).thenReturn(false);

        initTest();
        createDummyJar(SERVICE1_JAR);

        verify(services, timeout(5000))
            .register(
                new ServiceInfoJar(
                    ServiceId.of("group", "name", "123"),
                    JarInfo.builder().name(SERVICE1_JAR).store(store).build()
                )
            );

        createDummyJar(SERVICE2_JAR);
        verify(services, timeout(5000))
            .register(
                new ServiceInfoJar(
                    ServiceId.of("group", "name2", "234"),
                    JarInfo.builder().name(SERVICE2_JAR).store(store).build()
                )
            );

        createDummyJar(SERVICE3_JAR);
        verify(services, timeout(5000))
            .register(
                new ServiceInfoJar(
                    ServiceId.of("group3", "name3", "345"),
                    JarInfo.builder().name(SERVICE3_JAR).store(store).build()
                )
            );
    }
    @Test void removedJarsShouldHaveTheirServiceRemoved() {
        when(services.getOrCreateServiceVariations(any(ServiceId.class))).thenReturn(serviceVariations);
        when(serviceVariations.isKnownService(any())).thenReturn(true);

        initTest();
        createDummyJar(SERVICE1_JAR);
        createDummyJar(SERVICE2_JAR);
        createDummyJar(SERVICE3_JAR);

        assertThat(serviceJarsManager.getJars().size(), is(3));

        removeJar(SERVICE1_JAR);
        removeJar(SERVICE2_JAR);

        verify(serviceVariations)
            .removeAllDormants(ServiceId.of("group", "name", "123"));
        verify(serviceVariations)
            .removeAllDormants(ServiceId.of("group", "name2", "234"));

        removeJar(SERVICE3_JAR);

        verify(serviceVariations)
            .removeAllDormants(ServiceId.of("group3", "name3", "345"));
    }

    private void createDummyJar(String jarName) {
        // Write the jar
        noCheckedThrow(() -> store.get().write(jarName, "dummy jar content").get());

        // Tell the jars manager to update its internal list of jars.
        // This is performed async, so we have to wait until it is done.
        serviceJarsManager.updateJarSources();

        assertContainsJar(jarName);
    }
    private void removeJar(String jarName) {
        // Exception ignored because this is a test. It will fail if removal fails.
        noThrow(() -> store.get().remove(jarName).get());

        // Tell the jars manager to update its internal list of jars.
        // This is performed async, so we have to wait until it is done.
        serviceJarsManager.updateJarSources();

        assertNotContainsJar(jarName);
    }

    private void assertContainsJar(String jarName) {
        TestUtils.waitUntilCondition(() -> jarManagerContains(jarName));
    }
    private void assertNotContainsJar(String jarName) {
        TestUtils.waitUntilCondition(() -> !jarManagerContains(jarName));
    }

    private boolean jarManagerContains(String jarName) {
        return noThrow(() -> serviceJarsManager.getJars().stream().map(ji->ji.name).anyMatch(name -> name.contains(jarName))).orElse(false);
    }
}