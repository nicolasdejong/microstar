package net.microstar.dispatcher.model;

import net.microstar.common.model.ServiceId;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class ServiceInfoTest {

    @Test void highestVersionComparatorShouldSortHighestFirst() {
        final List<ServiceInfo> infos = serviceInfosFor(
            ServiceInfo.serviceWithHighestVersionFirstComparator,
            "1.2",
            "1.2-SNAPSHOT"
        );
        assertThat(serviceInfoVersionsOf(infos), is(List.of(
            "1.2",
            "1.2-SNAPSHOT"
        )));
    }

    @Test void lowestVersionComparatorShouldSortLowestFirst() {
        final List<ServiceInfo> infos = serviceInfosFor(
            ServiceInfo.serviceWithLowestVersionFirstComparator,
            "1.2",
            "1.2-SNAPSHOT"
        );
        assertThat(serviceInfoVersionsOf(infos), is(List.of(
            "1.2-SNAPSHOT",
            "1.2"
        )));
    }

    private static List<String> serviceInfoVersionsOf(List<ServiceInfo> serviceInfos) {
        return serviceInfos.stream().map(si -> si.id.version).toList();
    }
    private static List<ServiceInfo> serviceInfosFor(Comparator<ServiceInfo> order, String... versions) {
        return Arrays.stream(versions).map(ServiceInfoTest::serviceInfoFor).sorted(order).toList();
    }
    private static ServiceInfo serviceInfoFor(String version) {
        final ServiceId serviceId = ServiceId.of("testing-" + version);
        return new ServiceInfo(serviceId, Optional.empty()) {};
    }
}