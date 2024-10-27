package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static net.microstar.common.util.CollectionUtils.first;
import static net.microstar.common.util.CollectionUtils.last;
import static org.junit.jupiter.api.Assertions.*;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;

class CollectionUtilsTest {

    @Test void testIntersectionOf() {
        assertThat(CollectionUtils.intersectionOf(List.of("a","b","c"), List.of("b","c","d")), is(Set.of("b","c")));
    }
    @Test void testDisjunctionLeft() {
        assertThat(CollectionUtils.disjunctionLeft(List.of("a", "b", "c"), List.of("b", "c", "d")), is(Set.of("a")));
    }
    @Test void testDisjunctionRight() {
        assertThat(CollectionUtils.disjunctionRight(List.of("a", "b", "c"), List.of("b", "c", "d")), is(Set.of("d")));
    }
    @Test void testConcatLists() {
        assertThat(CollectionUtils.concat(List.of("a", "b", "c"), List.of("b", "c", "d")), is(List.of("a", "b", "c", "b", "c", "d")));
    }
    @Test void testConcatStreams() {
        assertThat(CollectionUtils.concat(Stream.of("a","b"), Stream.of("c","d"), Stream.of("e","f")).toList(), is(List.of("a", "b", "c", "d", "e", "f")));
    }
    @Test void testListRemoveIf() {
        final List<String> list = new ArrayList<>(List.of("a","b","c"));
        CollectionUtils.removeIf(list, item -> item.contains("b"));
        assertThat(list, is(List.of("a","c")));
    }
    @Test void testSetRemoveIf() {
        final Set<String> set = new HashSet<>(List.of("a","b","c"));
        CollectionUtils.removeIf(set, value -> value.contains("b"));
        assertThat(set, is(Set.of("a","c")));
    }
    @Test void testMapRemoveIf() {
        final Map<String,Integer> map = new LinkedHashMap<>(Map.of("a",1, "b",2, "c",3));
        CollectionUtils.removeIf(map, value -> value > 2);
        assertThat(map, is(Map.of("a",1, "b",2)));
    }
    @Test void testReverseStream() {
        assertThat(CollectionUtils.reverse(Stream.of()).toList(), is(List.of()));
        assertThat(CollectionUtils.reverse(Stream.of(1)).toList(), is(List.of(1)));
        assertThat(CollectionUtils.reverse(Stream.of(1,2)).toList(), is(List.of(2,1)));
        assertThat(CollectionUtils.reverse(Stream.of(1,2,3,4,5)).toList(), is(List.of(5,4,3,2,1)));
    }
    @Test void testFirst() {
        assertThat(first(new String[0]), is(Optional.empty()));
        assertThat(first(new String[] {"a","b'"}), is(Optional.of("a")));
        assertThat(first((Collections.emptyList())), is(Optional.empty()));
        assertThat(first(List.of("a","b")), is(Optional.of("a")));
        assertThat(first(Stream.empty()), is(Optional.empty()));
        assertThat(first(Stream.of("a","b")), is(Optional.of("a")));
    }
    @Test void testLast() {
        assertThat(last(new String[0]), is(Optional.empty()));
        assertThat(last(new String[] {"a","b"}), is(Optional.of("b")));
        assertThat(last((Collections.emptyList())), is(Optional.empty()));
        assertThat(last(List.of("a","b")), is(Optional.of("b")));
        assertThat(last(Stream.empty()), is(Optional.empty()));
        assertThat(last(Stream.of("a","b")), is(Optional.of("b")));
    }
}