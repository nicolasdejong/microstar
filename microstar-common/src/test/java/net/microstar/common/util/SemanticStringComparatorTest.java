package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class SemanticStringComparatorTest {

    @Test void orderShouldBeLexicographical() {
        assertThat(sort(List.of("a2", "a10", "a3")),
                     is(List.of("a2", "a3", "a10")));
        assertThat(sort(List.of("some magic B longer", "foo bar", "foo bar 2", "foo bar 12", "some magic A")),
                     is(List.of("foo bar", "foo bar 2", "foo bar 12", "some magic A", "some magic B longer")));
    }
    @Test void versionsShouldBeComparedCorrectly() {
        assertThat(sort(List.of("0.9", "1.0c", "1.2", "1.3", "0.6", "1.1", "0.7",  "0.3",  "1.0b", "1.0", "0.8")),
                     is(List.of("0.3", "0.6",  "0.7", "0.8", "0.9", "1.0", "1.0b", "1.0c", "1.1",  "1.2", "1.3")));
        assertThat(sort(List.of("1.5.2", "1.10")),
                     is(List.of("1.5.2", "1.10")));
        assertThat(sort(List.of("1.0", "1.10", "1.5.2", "1.5.12", "1.5.2.960", "1.5.2.97")),
                     is(List.of("1.0", "1.5.2", "1.5.2.97", "1.5.2.960", "1.5.12", "1.10")));
        assertThat(sort(List.of("name-1.2.3.20220525123457", "name-1.2.3.20220525123456", "name-1.2.4.20220525123458")),
                     is(List.of("name-1.2.3.20220525123456", "name-1.2.3.20220525123457", "name-1.2.4.20220525123458")));
    }
    @Test void orderShouldCheckCaseWhenAsked() {
        assertThat(sort(List.of("a", "B", "c")),
                     is(List.of("B", "a", "c")));
    }
    @Test void orderShouldIgnoreCaseWhenAsked() {
        assertThat(sortIgnoreCase(List.of("a", "B", "c")),
                               is(List.of("a", "B", "c")));
    }

    private static List<String> sortIgnoreCase(List<String> list) { return sort(true, list); }
    private static List<String> sort(List<String> list) { return sort(false, list); }
    private static List<String> sort(boolean ignoreCase, List<String> list) {
        final List<String> modifiableList = new ArrayList<>(list);
        modifiableList.sort(ignoreCase ? SemanticStringComparator.IGNORING_CASE : SemanticStringComparator.DEFAULT);
        return modifiableList;
    }
}