package net.microstar.dispatcher.controller;

import jakarta.annotation.PreDestroy;
import net.microstar.common.MicroStarConstants;
import net.microstar.common.model.ServiceId;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.services.Services;
import net.microstar.spring.DataStores;
import net.microstar.spring.logging.MicroStarLogAppender;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static net.microstar.common.MicroStarConstants.HEADER_X_CLUSTER_SECRET;
import static net.microstar.common.MicroStarConstants.HEADER_X_SERVICE_UUID;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SpringBootTest
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ServiceControllerIT {
    private static final String ENDPOINT = "/service/";
    private static final UUID serviceInstanceId = UUID.randomUUID();

    @Autowired private ApplicationContext applicationContext;
    @Autowired private WebTestClient webClient;

    @BeforeEach void init() {
        DynamicPropertiesManager.setProperty("microstar.dataStores", Map.of("jars", Map.of("type", "memory")));
    }
    @AfterEach void cleanup() {
        callPreDestroys();
        // Spring exit closes the LogAppenders, so we need to re-init (for the other unit-tests that will follow)
        MicroStarLogAppender.reset();
        MicroStarLogAppender.init();
        DataStores.closeAll();
        DynamicPropertiesManager.clearAllState();
    }

    // Call PreDestroy of all beans
    // Also called with: @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
    // but that one runs after the cleanup() which leads to many errors in the log
    // so call the PreDestroy of all MicroStar classes here
    private void callPreDestroys() {
        final ConfigurableListableBeanFactory beans = ((ConfigurableApplicationContext)applicationContext).getBeanFactory();
        StreamSupport.stream(Spliterators.spliteratorUnknownSize(beans.getBeanNamesIterator(), Spliterator.ORDERED), false)
            .map(beans::getBean)
            .filter(bean -> bean.getClass().toString().contains(".microstar."))
            .forEach(bean -> {
                Arrays.stream(bean.getClass().getDeclaredMethods())
                    .filter(method -> method.isAnnotationPresent(PreDestroy.class))
                    .findFirst()
                    .ifPresent(method -> {
                        noThrow(() -> method.setAccessible(true));
                        noThrow(() -> method.invoke(bean));
                    });
            });
    }

    @Test
    void callingUnregisterShouldRemoveRegisteredService() {
        final Services services = applicationContext.getBean(Services.class);

        final ServiceInfoRegistered serviceInfoRegisteredMock = Mockito.mock(ServiceInfoRegistered.class);
        ReflectionTestUtils.setField(serviceInfoRegisteredMock, "id", ServiceId.of("testing/service/0"));
        ReflectionTestUtils.setField(serviceInfoRegisteredMock, "serviceInstanceId", serviceInstanceId);
        ReflectionTestUtils.setField(serviceInfoRegisteredMock, "jarInfo", Optional.empty());
        ReflectionTestUtils.setField(serviceInfoRegisteredMock, "baseUrl", "http://mock.localhost:80");

        // Register the mock service
        services.register(serviceInfoRegisteredMock);

        // The mock service should be registered now
        assertThat(services.getServiceVariationsInGroup("testing").stream().findFirst().orElseThrow().getVariations().size(), is(1));

        // unregister the just added mock service via the web client
        webClient.post()
            .uri(ENDPOINT + "unregister")
            .header(HEADER_X_CLUSTER_SECRET, MicroStarConstants.CLUSTER_SECRET)
            .header(HEADER_X_SERVICE_UUID, serviceInstanceId.toString())
            .exchange()
            .expectStatus().isOk();

        // check that the mock service indeed has been unregistered
        assertThat(services.getServiceVariationsInGroup("testing").stream().findFirst().orElseThrow().getVariations().size(), is(0));
    }
}
