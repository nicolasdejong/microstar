package net.microstar.dispatcher.services;

import com.google.common.collect.ImmutableList;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.ByteSize;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.ProcessInfo;
import net.microstar.dispatcher.model.ServiceInfoRegistered;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class RestartRulesCheckerTest {
    private Services services;
    private ServiceVariations serviceVariations;

    @BeforeEach public void init() {
        services = Mockito.mock(Services.class);
        serviceVariations = Mockito.mock(ServiceVariations.class);
        when(services.getOrCreateServiceVariations(any())).thenReturn(serviceVariations);
    }
    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    private ServiceInfoRegistered createMockService(String serviceIdText, List<Object> processInfoFields) {
        final ServiceId serviceId = ServiceId.of(serviceIdText);
        final ServiceInfoRegistered service = Mockito.mock(ServiceInfoRegistered.class);
        ReflectionTestUtils.setField(service, "id", serviceId);
        ReflectionTestUtils.setField(service, "serviceInstanceId", uuidOf(Integer.parseInt(serviceId.version)));

        final ProcessInfo processInfo = Mockito.mock(ProcessInfo.class);
        ImmutableUtil.<String,Object>mapOf(
            "virtualMemorySize",  ByteSize.ofKilobytes(80),
            "residentMemorySize", ByteSize.ofMegabytes(75), // proc mem
            "heapSize",           ByteSize.ofMegabytes(10),
            "heapUsed",           ByteSize.ofMegabytes(5),
            "heapUsedPercent",    50,
            "minHeapUsed",        ByteSize.ofMegabytes(2),
            "metaSpace",          ByteSize.ofMegabytes(28),
            "uptime",             Duration.ofHours(8),
            "sysMem",             ByteSize.ofGigabytes(8),
            "sysMemAvailable",    ByteSize.ofGigabytes(3),
            "sysMemAvailablePercent", (100*3)/8
        ).forEach((key,val) -> ReflectionTestUtils.setField(processInfo, key, val));
        for(int i=0; i<processInfoFields.size(); i+=2) ReflectionTestUtils.setField(processInfo, (String)processInfoFields.get(i), processInfoFields.get(i+1));
        when(services.getProcessInfo(service)).thenReturn(Optional.of(processInfo));
        return service;
    }
    private UUID uuidOf(int n) {
        final String sn = "" + n;
        return UUID.fromString(String.join("-", sn, sn, sn, sn, sn));
    }
    private int numberOf(Object obj) {
        return obj instanceof ServiceInfoRegistered reg ? numberOf(reg.serviceInstanceId) :
               obj instanceof UUID uuid ? Integer.parseInt(uuid.toString().substring(uuid.toString().length() - 1)) : 0;
    }
    private void runTest(String configuration, ImmutableList<ServiceInfoRegistered> servicesToCheck) {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromYaml(configuration));
        when(services.getAllRunningServices()).thenReturn(servicesToCheck);

        final RestartRulesChecker rulesChecker = new RestartRulesChecker(services);
        rulesChecker.checkRulesNow();
    }


    @Test void servicesShouldBeExcludedWhenAnExcludeIsGiven() {
        runTest("""
           app.config.dispatcher.restartRules:
             - exclude: /exclude-name
               maxProcMem: 50MB
        """, ImmutableList.of(
            createMockService("exclude-name-1", List.of("residentMemorySize", ByteSize.ofMegabytes(75))), // this service should be ignored
            createMockService("include-name-2", List.of("residentMemorySize", ByteSize.ofMegabytes(75)))  // this service should restart
        ));

        verify(serviceVariations, times(1)).restartService(any());

        final Invocation inv = Mockito.mockingDetails(serviceVariations).getInvocations().stream().findFirst().orElseThrow();
        assertThat(numberOf(inv.getArgument(0)), is(2));
    }
    @Test void servicesShouldBeIncludedWhenAnIncludeIsGiven() {
        runTest("""
           app.config.dispatcher.restartRules:
             - include: /include-name
               maxProcMem: 50MB
        """, ImmutableList.of(
            createMockService("exclude-name-1", List.of("residentMemorySize", ByteSize.ofMegabytes(75))), // this service should be ignored
            createMockService("include-name-2", List.of("residentMemorySize", ByteSize.ofMegabytes(75)))  // this service should restart
        ));

        verify(serviceVariations, times(1)).restartService(any());

        final Invocation inv = Mockito.mockingDetails(serviceVariations).getInvocations().stream().findFirst().orElseThrow();
        assertThat(numberOf(inv.getArgument(0)), is(2));
    }
    @Test void servicesOverMaxProcMemShouldRestart() {
        runTest("""
           app.config.dispatcher.restartRules:
             - maxProcMem: 50MB
        """, ImmutableList.of(
            createMockService("service-1", List.of("residentMemorySize", ByteSize.ofMegabytes(25))), // this service should NOT restart
            createMockService("service-2", List.of("residentMemorySize", ByteSize.ofMegabytes(75))), // this service should restart
            createMockService("service-3", List.of("residentMemorySize", ByteSize.ofMegabytes(99)))  // this service should restart
        ));

        verify(serviceVariations, times(2)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(2));
        assertThat(numberOf(inv.get(1).getArgument(0)), is(3));
    }
    @Test void servicesOverMaxHeapUseShouldRestart() {
        runTest("""
           app.config.dispatcher.restartRules:
             - maxHeapUsed: 50MB
        """, ImmutableList.of(
            createMockService("service-1", List.of("heapUsed", ByteSize.ofMegabytes(25))), // this service should NOT restart
            createMockService("service-2", List.of("heapUsed", ByteSize.ofMegabytes(75))), // this service should restart
            createMockService("service-3", List.of("heapUsed", ByteSize.ofMegabytes(99)))  // this service should restart
        ));

        verify(serviceVariations, times(2)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(2));
        assertThat(numberOf(inv.get(1).getArgument(0)), is(3));
    }
    @Test void servicesOverMaxMinHeapUseShouldRestart() {
        runTest("""
           app.config.dispatcher.restartRules:
             - maxMinHeapUsed: 50MB
        """, ImmutableList.of(
            createMockService("service-1", List.of("minHeapUsed", ByteSize.ofMegabytes(25))), // this service should NOT restart
            createMockService("service-2", List.of("minHeapUsed", ByteSize.ofMegabytes(75))), // this service should restart
            createMockService("service-3", List.of("minHeapUsed", ByteSize.ofMegabytes(99)))  // this service should restart
        ));

        verify(serviceVariations, times(2)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(2));
        assertThat(numberOf(inv.get(1).getArgument(0)), is(3));
    }
    @Test void servicesOverMaxRuntimeShouldRestart() {
        runTest("""
           app.config.dispatcher.restartRules:
             - maxUptime: 8h
        """, ImmutableList.of(
            createMockService("service-1", List.of("uptime", Duration.ofHours(7))), // this service should NOT restart
            createMockService("service-2", List.of("uptime", Duration.ofHours(8))), // this service should restart
            createMockService("service-3", List.of("uptime", Duration.ofHours(9)))  // this service should restart
        ));

        verify(serviceVariations, times(2)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(2));
        assertThat(numberOf(inv.get(1).getArgument(0)), is(3));
    }
    @Test void servicesShouldRestartAtGivenTime() {
        final String[] now = new String[] { ""+LocalTime.now().getHour(), ""+LocalTime.now().getMinute(), ""+LocalTime.now().getSecond() };
        runTest("""
           app.config.dispatcher.restartRules:
             - time: ${now}
        """.replace("${now}", "[" + String.join(",", now) + "]"), ImmutableList.of(
            createMockService("service-1", List.of()), // this service should restart
            createMockService("service-2", List.of()), // this service should restart
            createMockService("service-3", List.of())  // this service should restart
        ));

        verify(serviceVariations, times(3)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(1));
        assertThat(numberOf(inv.get(1).getArgument(0)), is(2));
        assertThat(numberOf(inv.get(2).getArgument(0)), is(3));
    }
    @Test void multipleRuleTriggersShouldRestartOnlyOnce() {
        runTest("""
           app.config.dispatcher.restartRules:
             - maxHeapUsed: 50MB
             - maxUptime: 8h
        """, ImmutableList.of(
            createMockService("service-1", List.of("heapUsed", ByteSize.ofMegabytes(75), "uptime", Duration.ofHours(12)))
        ));

        verify(serviceVariations, times(1)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(1));
    }

    @Test void combinedTest() {
        runTest("""
           app.config.dispatcher.restartRules:
             - include: serviceA, s.*er*eB
               maxHeapUsed: 50MB
               maxUptime: 8h
             - exclude: serviceA
               maxProcMem: 300M
        """, ImmutableList.of(
            createMockService("serviceA-1", List.of("heapUsed", ByteSize.ofMegabytes(40), "uptime", Duration.ofHours(2), "residentMemorySize", ByteSize.ofMegabytes(800))),  // no restart
            createMockService("serviceB-2", List.of("heapUsed", ByteSize.ofMegabytes(40), "uptime", Duration.ofHours(12))), // restart
            createMockService("serviceC-3", List.of("heapUsed", ByteSize.ofMegabytes(40), "uptime", Duration.ofHours(12), "residentMemorySize", ByteSize.ofMegabytes(800)))  // restart
        ));

        verify(serviceVariations, times(2)).restartService(any());

        final List<Invocation> inv = new ArrayList<>(Mockito.mockingDetails(serviceVariations).getInvocations());
        assertThat(numberOf(inv.get(0).getArgument(0)), is(2));
        assertThat(numberOf(inv.get(1).getArgument(0)), is(3));
    }
}
