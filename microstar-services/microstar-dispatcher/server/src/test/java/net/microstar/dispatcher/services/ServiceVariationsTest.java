package net.microstar.dispatcher.services;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.google.common.collect.ImmutableList;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.ServiceInfo;
import net.microstar.dispatcher.model.ServiceInfoJar;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.dispatcher.model.ServiceInfoStarting;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.TimeoutException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.webflux.EventEmitter;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.sleep;
import static net.microstar.testing.TestUtils.waitUntilCondition;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class ServiceVariationsTest {
    private static final String RESULT_TEXT = "result";
    private static final String FALLBACK_TEXT = "fallback";
    private static final String DEFAULT_GROUP = "serviceGroup";
    private static final String DEFAULT_NAME = "serviceName";
    private static final EventEmitter mockEventEmitter = Mockito.mock(EventEmitter.class);
    private final JarRunner jarRunner = Mockito.mock(JarRunner.class);
    private final List<ServiceInfoRegistered> createdRegistered = new ArrayList<>();
    private ServiceVariations variations;
    private final ServiceInfoRegistered[] startedServiceJar = { null };

    @BeforeAll static void hideNettyDebugLogging() {
        final Logger netty = (Logger) LoggerFactory.getLogger("io.netty");
        netty.setLevel(Level.INFO);
    }

    @BeforeEach void setup() {
        variations = new ServiceVariations(DEFAULT_GROUP, DEFAULT_NAME, jarRunner, mockEventEmitter);
        TempApp.newActive30s();
    }
    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
        createdRegistered.forEach(ServiceInfoRegistered::cleanup);
        createdRegistered.clear();
        TempApp.clean();
        Mockito.reset(jarRunner, mockEventEmitter);
    }

    @Test void timedOutFutureShouldLeadToFallback() {
        final CompletableFuture<Void> future = new CompletableFuture<>();
        final Mono<String> mono = Mono
            .fromFuture(future)
            .thenReturn(RESULT_TEXT)
            .timeout(Duration.ofMillis(100), Mono.just(FALLBACK_TEXT))
            ;
        StepVerifier
            .create(mono)
            .expectNext(FALLBACK_TEXT)
            .verifyComplete();
    }
    @Test void inTimeFutureShouldLeadToResult() {
        final CompletableFuture<String> future = new CompletableFuture<>();
        Threads.execute(() -> { sleep(25); future.complete(RESULT_TEXT); });
        final Mono<String> mono = Mono
            .fromFuture(future)
            .timeout(Duration.ofMillis(5000), Mono.just(FALLBACK_TEXT))
            ;
        StepVerifier
            .create(mono)
            .expectNext(RESULT_TEXT)
            .verifyComplete();
    }

    @Test void addingVariationShouldBeAdded() {
        final ServiceInfo variation = createServiceInfoJar("123");
        variations.add(variation);

        assertThat(variations.getVariations().size(), is(1));
        assertThat(variations.getVariations().get(0), is(variation));
    }
    @Test void stoppedVariationShouldBeRemoved() {
        DynamicPropertiesManager.setProperty("app.config.dispatcher.services.initialRegisterTime", "1500ms");
        final ServiceInfo variation1 = new ServiceInfo(ServiceId.of(DEFAULT_GROUP, DEFAULT_NAME, "1"), Optional.empty()) {};
        final ServiceInfo variation2 = new ServiceInfo(ServiceId.of(DEFAULT_GROUP, DEFAULT_NAME, "2"), Optional.empty()) {};
        final ServiceInfoRegistered runningVariation1 = createRegistered("1", 1);
        variations.add(variation1);
        variations.add(runningVariation1);

        assertThat(new HashSet<>(variations.getVariations()), is(Set.of(variation1, runningVariation1)));
        assertThat(variations.getServiceToCall().block(), is(runningVariation1));

        variations.add(variation2);

        assertThat(new HashSet<>(variations.getVariations()), is(Set.of(variation1, variation2, runningVariation1)));
        assertThat(variations.getServiceToCall().block(), is(runningVariation1));

        variations.stopped(runningVariation1);

        assertThat(new HashSet<>(variations.getVariations()), is(Set.of(variation1, variation2)));
        assertThat(variations.getServiceToCall().block(), is(nullValue()));
    }
    @Test void removingVariationShouldLeaveJarFile() {
        final ServiceInfoRegistered variation = createRegistered("123", 123, Optional.of(JarInfo.builder().name("dummy.jar").build()),reg -> {});
        variations.add(variation);
        variations.stopped(variation);

        assertThat(variations.getVariations().size(), is(1));
        assertThat(variations.getVariations().get(0), instanceOf(ServiceInfoJar.class));
        assertThat(variations.getVariations().get(0).jarInfo.orElseThrow().name, matchesPattern("^dummy.*?\\.jar"));
    }
    @Test void removingAllDormantsShouldKeepRunningServices() {
        final ServiceInfo           dormantVariation = createDormant   ("123", JarInfo.builder().name("dummyDormant.jar").build());
        final ServiceInfoRegistered runningVariation = createRegistered("123", 123, Optional.of(JarInfo.builder().name("dummyRunning.jar").build()),reg -> {});

        variations.add(dormantVariation);
        variations.add(runningVariation);
        assertThat(variations.getVariations().size(), is(2));

        variations.removeAllDormants(ServiceId.of(DEFAULT_GROUP, DEFAULT_NAME, "123"));
        assertThat(variations.getVariations().size(), is(1));
        assertThat(variations.getVariations().get(0), is(instanceOf(ServiceInfoRegistered.class)));
    }
    @Test void addedVariationsShouldBeOrderedLatestFirst() {
        final ServiceInfo variation1 = createServiceInfoJar("1");
        final ServiceInfo variation2 = createServiceInfoJar("2");
        final ServiceInfo variation3 = createServiceInfoJar("3");

        variations.add(variation2);
        variations.add(variation3);
        variations.add(variation1);
        assertThat(variations.getVariations(), is(List.of(variation3, variation2, variation1)));
    }
    @Test void highestRegisteredServiceShouldBeUsed() {
        final ServiceInfo variation1 = createServiceInfoJar("1");
        final ServiceInfo variation2 = createServiceInfoJar("2");
        final ServiceInfo variation3 = createServiceInfoJar("3");

        jarRunnerShouldPretendItIsStartingJar("3", 100);

        variations.add(variation2);
        variations.add(variation3);
        variations.add(variation1);
        assertThat(Objects.requireNonNull(variations.getServiceToCall().block()).id.version, is("3"));
    }
    @Test void restartingServiceShouldBeRemoved() {
        final ServiceInfoRegistered variation1 = createRegistered("1", 1);
        final ServiceInfoRegistered variation2 = createRegistered("2", 2);
        variations.add(variation1);
        variations.add(variation2);

        assertThat(variations.getVariations().size(), is(2));
        assertThat(variations.getVariations().stream().anyMatch(v -> v == variation2), is(true));
        variations.starting(variation2); // replaces REGISTERED by RESTARTING info
        assertThat(variations.getVariations().size(), is(2));
        assertThat(variations.getVariations().stream().anyMatch(v -> v == variation2), is(false));
        assertThat(variations.getVariations().stream()
            .filter(v -> v instanceof ServiceInfoStarting)
            .map(r -> ((ServiceInfoStarting)r).serviceInstanceId)
            .map(sid -> sid.equals(variation2.serviceInstanceId))
            .findFirst()
            .orElse(false), is(true));
    }
    @Test void restartingServiceShouldTimeoutIfNotRegisteredInTime() {
        DynamicPropertiesManager.setProperty("app.config.dispatcher.services.startupTimeout", "1s");
        jarRunnerShouldPretendItIsStartingJar("1", 150);
        final ServiceInfo variation1 = createServiceInfoJar("1");
        variations.add(variation1);

        variations.starting(variation1);
        assertThrows(TimeoutException.class, () -> variations.getServiceToCall().block());
    }
    @Test void requestsShouldWaitForRestartingService() {
        final ServiceInfoRegistered variation1 = createRegistered("1", 1);
        variations.add(variation1);

        variations.starting(variation1);
        Threads.execute(() -> { sleep(10); variations.add(variation1); });
        assertThat(variations.getServiceToCall().block(), is(variation1));
    }
    @Test void concurrentMultipleGetServiceToCallShouldStartOneServiceOnly() {
        DynamicPropertiesManager.setProperty("app.config.dispatcher.services.startupTimeout", "99s");
        DynamicPropertiesManager.setProperty("app.config.dispatcher.services.initialRegisterTime", "5s");
        final String version = "1.0";

        jarRunnerShouldPretendItIsStartingJar(version, 1000);
        variations.add(createServiceInfoJar(version));

        final Mono<ServiceInfoRegistered> service = variations.getServiceToCall();
        final AtomicInteger unblockedCount = new AtomicInteger(0);
        final int threadCount = 50;

        Threads.execute(() -> {
            final Thread[] threads = new Thread[threadCount];
            final AtomicInteger threadsRunningCount = new AtomicInteger(0);
            for(int i=0; i<threads.length; i++) threads[i] = new Thread(() -> {
                threadsRunningCount.incrementAndGet();
                synchronized(threads) { noThrow(() -> threads.wait()); }

                sleep((int)(Math.random()*10));
                assertThat(variations.getServiceToCall().block(), is(startedServiceJar[0]));
                unblockedCount.incrementAndGet();
            });
            for(final Thread t : threads) t.start();

            while(threadsRunningCount.get() < threads.length) sleep(10);
            synchronized(threads) { threads.notifyAll(); } // awake all threads

            while(startedServiceJar[0] == null) sleep(100);
        });

        assertThat(service.block(), is(startedServiceJar[0]));
        unblockedCount.incrementAndGet();

        // wait for all threads to finish
        int tries = 400;
        while(unblockedCount.get() <= threadCount && tries-->0) sleep(25);
        assertThat(unblockedCount.get(), is(threadCount + 1));

        verify(jarRunner, times(1)).run(any(), any(), any());

        assertThat(String.valueOf(variations.getVariations()), variations.getVariations().size(), is(2));
        assertThat(String.valueOf(variations.getVariations()), variations.getVariations().get(0) instanceof ServiceInfoJar, is(true));
        assertThat(String.valueOf(variations.getVariations()), variations.getVariations().get(1) instanceof ServiceInfoRegistered, is(true));
    }

    @ExtendWith(OutputCaptureExtension.class)
    @Test void callsReceivedJustAfterStartingDispatcherShouldNotStartServices(CapturedOutput output) {
        TempApp.clean(); TempApp.newNotActive();
        DynamicPropertiesManager.setProperty("app.config.dispatcher.services.initialRegisterTime", "1500ms");

        final long l0 = System.currentTimeMillis();
        final Mono<ServiceInfoRegistered> reg = variations.getServiceToCall();
        reg.block();
        final long l1 = System.currentTimeMillis();
        assertThat(l1 - l0, is(Matchers.greaterThan(1500L)));
        assertThat(output.getOut(), containsString("Attempt to start service")); // a service *is* started, but not before the app is running for a while
    }

    @ExtendWith(OutputCaptureExtension.class)
    @Test void callsReceivedLongerAfterStartingShouldStartServices(CapturedOutput output) {
        new TempApp(Duration.ofSeconds(300)); // needed so MicroStarApplication.get() is present -- use always 300s since start

        final Mono<ServiceInfoRegistered> reg = variations.getServiceToCall();
        assertTimeout(Duration.ofSeconds(3), () -> reg.block());
        assertThat(output.getOut(), containsString("Attempt to start service"));
    }

    @ExtendWith(OutputCaptureExtension.class)
    @Test void callsReceivedJustAfterStartingTargetServiceShouldNotStartItAgain(CapturedOutput output) {
        final List<AtomicReference<ServiceInfoRegistered>> regs = new ArrayList<>();
        for(int i=0; i<10; i++) regs.add(new AtomicReference<>(null));
        final ServiceId serviceId = ServiceId.of("main", "unit-test", "1.0");
        final ServiceInfoJar jar = new ServiceInfoJar(serviceId, JarInfo.builder().name("test").build());
        final ServiceInfoRegistered reg = Mockito.mock(ServiceInfoRegistered.class);
        ReflectionTestUtils.setField(reg, "id", serviceId);
        ReflectionTestUtils.setField(reg, "baseUrl", "https://localhost:99999");

        ReflectionTestUtils.setField(variations, "mostCurrentServicesRef", new AtomicReference<>(ImmutableList.of(jar)));

        // When jarRunner.run() is called for the jar to start, act as if an
        // actual jar is starting. (The 'jar' 'registering' itself is below)
        //noinspection unchecked
        doAnswer(inv -> {
            ReflectionTestUtils.setField(reg, "serviceInstanceId", UUID.fromString((String)inv.getArgument(2, Map.class).get("instanceId")));
            sleep(150); // time it takes for this fake jar to start
            return null; // run(...) returns void
        }).when(jarRunner).run(any(ServiceId.class), any(JarInfo.class), any(Map.class));

        // Do multiple calls. The service should start only once
        final AtomicInteger callCount = new AtomicInteger(0);
        Threads.execute(() -> {
            while (callCount.get() < regs.size()) {
                final int callCountInt = callCount.getAndIncrement();
                Threads.execute(() -> {
                    regs.get(callCountInt).set(variations.getServiceToCall().block());
                });
                sleep(50);
            }
        });
        // Wait a bit until registered
        while(callCount.get() < regs.size()/2) sleep(10);

        // Pretend the just started service registers itself
        Threads.execute(() -> {
            variations.add(reg);
        });

        // Wait until all 'getServiceToCall()' calls have a value.
        waitUntilCondition(() -> regs.size() == regs.stream().map(AtomicReference::get).filter(Objects::nonNull).count());

        // All registered services should be the same (meaning: no more than one was started)
        final ServiceInfoRegistered reg0 = regs.get(0).get();
        assertThat(reg0, is(instanceOf(ServiceInfoRegistered.class)));
        for(final AtomicReference<ServiceInfoRegistered> regRef : regs)  assertThat(regRef.get(), is(reg0));

        // Sanity check -- the log message 'start service-jar' should appear only once
        assertThat(output.getOut().split("Start service-jar").length, is(2));
    }

    @Test void addingDormantServicesShouldNotChangeAvailableRunningServices() {
        final ServiceInfoRegistered variation1 = createRegistered("1", 1);
        variations.add(variation1);
        variations.add(createServiceInfoJar("2"));

        assertThat(variations.getServiceToCall().block(), is(variation1));

        variations.add(createServiceInfoJar("3"));
        variations.add(createServiceInfoJar("4"));

        assertThat(variations.getServiceToCall().block(), is(variation1));
    }

    @SuppressWarnings("SameParameterValue") // for now
    private static ServiceInfo createServiceInfoJar(String version) {
        return new ServiceInfoJar(ServiceId.of(DEFAULT_GROUP, DEFAULT_NAME, version), JarInfo.builder().name("dummy-" + version + ".jar").build());
    }
    private ServiceInfoRegistered createRegistered(String version, int instanceId) {
        return createRegistered(version, instanceId, reg -> {});
    }
    private ServiceInfoRegistered createRegistered(String version, int instanceId, Consumer<ServiceInfoRegistered> onDisconnect) {
        return createRegistered(version, instanceId, Optional.empty(), onDisconnect);
    }
    private ServiceInfoRegistered createRegistered(String version, int instanceId, Optional<JarInfo> jarInfo, Consumer<ServiceInfoRegistered> onDisconnect) {
        return createRegistered(version,
            UUID.fromString(String.format("%d-%d-%d-%d-%d", instanceId, instanceId, instanceId, instanceId, instanceId)),
            jarInfo, onDisconnect);
    }
    private ServiceInfoRegistered createRegistered(String version, UUID instanceId, Optional<JarInfo> jarInfo, Consumer<ServiceInfoRegistered> onDisconnect) {
        final ServiceInfoRegistered reg = new ServiceInfoRegistered(
            ServiceId.of(DEFAULT_GROUP, DEFAULT_NAME, version),
            instanceId,
            1000,
            "http",
            jarInfo,
            Optional.empty(),
            InetSocketAddress.createUnresolved("localhost", 12345),
            WebClient.builder(),
            onDisconnect
        );
        createdRegistered.add(reg);
        return reg;
    }
    private ServiceInfo createDormant(String version, JarInfo jarInfo) {
        final ServiceInfoJar serviceInfoJar =new ServiceInfoJar(ServiceId.of(DEFAULT_GROUP, DEFAULT_NAME, version), jarInfo);
        return serviceInfoJar;
    }
    private void jarRunnerShouldPretendItIsStartingJar(String versionStarted, long startupTime) {
        // When the 'jar' is started, pretend it registers itself
        doAnswer(inv -> {
            final JarInfo jar = inv.getArgument(1);
            final Map<String,String> map = inv.getArgument(2);
            final String filename = "dummy-" + versionStarted + ".jar";
            assertThat(jar.name, is(filename));

            Threads.execute(() -> {
                sleep(startupTime); // pretend starting the jar takes some time
                final String instanceId = map.get("instanceId");
                startedServiceJar[0] = createRegistered(versionStarted, UUID.fromString(instanceId), Optional.of(JarInfo.builder().name(filename).build()),regService -> { throw new IllegalStateException("DISCONNECTED!"); });
                variations.add(startedServiceJar[0]);
            });
            return null;
        }).when(jarRunner).run(any() ,any(), any());
    }
    private static class TempApp extends MicroStarApplication {
        static { clean(); }
        static void clean() { instanceOpt = Optional.empty(); } // in case MicroStarApplication was instantiated before
        private final Duration runTime;
        public TempApp(Duration runTime) { this.runTime = runTime; }
        @Override
        public Duration getRunTime() {  return runTime.isZero() ? super.getRunTime() : runTime; }
        @Override
        public Duration getStartupTime() { return Duration.ZERO; }

        static TempApp newActive30s() { return new TempApp(Duration.ofSeconds(300)); } // needed so MicroStarApplication.get() is present -- use always 300s since start
        static TempApp newNotActive() { return new TempApp(Duration.ZERO);           } // needed so MicroStarApplication.get() is present -- use always 0s since start
    }
}