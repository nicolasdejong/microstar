package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class VersionComparatorTest {
    private static final List<String> VERSIONS = List.of(
        "1.2",
        "1.3b3",
        "1.2-SNAPSHOT",
        "1.3b2",
        "1.3a",
        "1.3b3-SNAPSHOT"
    );
    private static final List<String> VERSIONS_NEWEST_TO_OLDEST = List.of(
        "1.3b3",
        "1.3b3-SNAPSHOT",
        "1.3b2",
        "1.3a",
        "1.2",
        "1.2-SNAPSHOT"
    );

    @Test void sortOldestToNewestShouldOrderOldestFirst() {
        assertThat(VERSIONS.stream().sorted(VersionComparator.OLDEST_TO_NEWEST).toList(),
            is(CollectionUtils.reverse(VERSIONS_NEWEST_TO_OLDEST.stream()).toList()));
    }
    @Test void sortNewestToOldestShouldOrderNewestFirst() {
        assertThat(VERSIONS.stream().sorted(VersionComparator.NEWEST_TO_OLDEST).toList(),
            is(VERSIONS_NEWEST_TO_OLDEST));
    }
}