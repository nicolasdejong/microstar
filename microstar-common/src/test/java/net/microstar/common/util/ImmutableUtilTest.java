package net.microstar.common.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;

class ImmutableUtilTest {

    @Test void emptyMapShouldReturnTheSameEmptyMap() {
        assertThat(ImmutableUtil.emptyMap().isEmpty(), is(true));
        assertThat(ImmutableUtil.emptyMap() == ImmutableUtil.emptyMap(), is(true));
    }
    @Test void emptySetShouldReturnTheSameEmptySet() {
        assertThat(ImmutableUtil.emptySet().isEmpty(), is(true));
        assertThat(ImmutableUtil.emptySet() == ImmutableUtil.emptySet(), is(true));
    }
    @Test void emptyListShouldReturnTheSameEmptyList() {
        assertThat(ImmutableUtil.emptyList().isEmpty(), is(true));
        assertThat(ImmutableUtil.emptyList() == ImmutableUtil.emptyList(), is(true));
    }

    @Test void copyAndMutateMapShouldReturnAMutatedMap() {
        assertThat(ImmutableUtil.copyAndMutate(ImmutableMap.of("a",1,"b",2), newMap -> newMap.put("b",3)),
            is(ImmutableMap.of("a",1,"b",3)));
    }
    @Test void copyAndMutateSetShouldReturnAMutatedSet() {
        assertThat(ImmutableUtil.copyAndMutate(ImmutableSet.of("a","b"), newSet -> newSet.add("c")),
            is(ImmutableSet.of("a","b","c")));
    }
    @Test void copyAndMutateListShouldReturnAMutatedList() {
        assertThat(ImmutableUtil.copyAndMutate(ImmutableList.of("a","b"), newList -> newList.add("c")),
            is(ImmutableList.of("a","b","c")));
    }

    @Test void copyAndRemoveIfShouldRemoveFromMap() {
        assertThat(ImmutableUtil.copyAndRemoveIf(ImmutableMap.of("a",1,"b",2), (k,v) -> v > 1),
            is(ImmutableMap.of("a",1)));
    }
    @Test void copyAndRemoveIfShouldRemoveFromSet() {
        assertThat(ImmutableUtil.copyAndRemoveIf(ImmutableSet.of(1,2,3,4), v -> v > 2),
            is(ImmutableSet.of(1,2)));
    }
    @Test void copyAndRemoveIfShouldRemoveFromList() {
        assertThat(ImmutableUtil.copyAndRemoveIf(ImmutableList.of(1,2,3,4), v -> v > 2),
            is(ImmutableList.of(1,2)));
    }

    @Test void updateMapRefShouldUpdate() {
        final AtomicReference<ImmutableMap<String,Integer>> mapRef = new AtomicReference<>(ImmutableMap.of("a",1,"b",2));
        ImmutableUtil.updateMapRef(mapRef, map -> map.put("c",3));
        assertThat(mapRef.get(), is(ImmutableMap.of("a",1,"b",2,"c",3)));
    }
    @Test void updateSetRefShouldUpdate() {
        final AtomicReference<ImmutableSet<Integer>> setRef = new AtomicReference<>(ImmutableSet.of(1,2));
        ImmutableUtil.updateSetRef(setRef, set -> set.add(3));
        assertThat(setRef.get(), is(ImmutableSet.of(1,2,3)));
    }
    @Test void updateListRefShouldUpdate() {
        final AtomicReference<ImmutableList<Integer>> listRef = new AtomicReference<>(ImmutableList.of(1,2));
        ImmutableUtil.updateListRef(listRef, list -> list.add(3));
        assertThat(listRef.get(), is(ImmutableList.of(1,2,3)));
    }

    @Test void removeFromMapRefShouldUpdate() {
        final AtomicReference<ImmutableMap<String,Integer>> mapRef = new AtomicReference<>(ImmutableMap.of("a",1,"b",2));
        ImmutableUtil.removeFromMapRef(mapRef, (key,val) -> val > 1);
        assertThat(mapRef.get(), is(ImmutableMap.of("a",1)));
    }
    @Test void RemoveFromSetRefShouldUpdate() {
        final AtomicReference<ImmutableSet<Integer>> setRef = new AtomicReference<>(ImmutableSet.of(1,2,3));
        ImmutableUtil.removeFromSetRef(setRef, val -> val > 2);
        assertThat(setRef.get(), is(ImmutableSet.of(1,2)));
    }
    @Test void removeFromListRefShouldUpdate() {
        final AtomicReference<ImmutableList<Integer>> listRef = new AtomicReference<>(ImmutableList.of(1,2,3));
        ImmutableUtil.removeFromListRef(listRef, val -> val > 2);
        assertThat(listRef.get(), is(ImmutableList.of(1,2)));
    }

    @Test void toImmutableMapShouldCollect() {
        final ImmutableMap<String,Integer> expected = ImmutableMap.of("a", 1, "b", 2);
        assertThat(Stream.of(new Object[] { "a", 1}, new Object[] { "b", 1}, new Object[] { "b", 2})
            .collect(ImmutableUtil.toImmutableMap(a -> a[0], a -> a[1])), is(expected));
        assertThat(Map.of("a", 1, "b", 2).entrySet().stream().collect(ImmutableUtil.toImmutableMap()), is(expected));
        assertThat(Stream.of(Map.entry("a", 1), Map.entry("b", 1), Map.entry("b", 2)).collect(ImmutableUtil.toImmutableMap()), is(expected));
    }
    @Test void toImmutableListShouldCollect() {
        final ImmutableList<String> expected = ImmutableList.of("a", "b", "c");
        assertThat(Stream.of("a", "b", "c").collect(ImmutableUtil.toImmutableList()), is(expected));
    }
    @Test void toImmutableSetShouldCollect() {
        final ImmutableSet<String> expected = ImmutableSet.of("a", "b", "c");
        assertThat(Stream.of("a", "b", "c").collect(ImmutableUtil.toImmutableSet()), is(expected));
    }

    @Test void testMapOfEntries() {
        assertThat(ImmutableUtil.mapOfEntries(Map.entry("a", 1), Map.entry("b", 2)), is(ImmutableMap.of("a", 1, "b", 2)));
    }
    @Test void testMapKeys() {
        assertThat(ImmutableUtil.mapKeys(ImmutableMap.of("a", "A", "b", "B"), s -> "@" + s), is(ImmutableMap.of("@a", "A", "@b", "B")));
    }
    @Test void testMapValues() {
        assertThat(ImmutableUtil.mapValues(ImmutableMap.of("a", "A", "b", "B"), s -> "@" + s), is(ImmutableMap.of("a", "@A", "b", "@B")));
    }

    @Test void testMapOf() {
        assertThat(ImmutableUtil.mapOf("a",1,"b",2), is(ImmutableMap.of("a",1,"b",2)));
        assertThat(ImmutableUtil.mapOf("a",1,"b",2,"c",3), is(ImmutableMap.of("a",1,"b",2,"c",3)));
        assertThat(ImmutableUtil.mapOf("a",1,"b",2,"c",3,"d",4), is(ImmutableMap.of("a",1,"b",2,"c",3,"d",4)));
        assertThat(ImmutableUtil.mapOf("a",1,"b",2,"c",3,"d",4,"e",5),           is(ImmutableMap.of("a",1,"b",2,"c",3,"d",4,"e",5)));
        assertThat(ImmutableUtil.mapOf("a",1,"b",2,"c",3,"d",4,"e",5,"f"      ), is(ImmutableMap.of("a",1,"b",2,"c",3,"d",4,"e",5)));
        assertThat(ImmutableUtil.mapOf("a",1,"b",2,"c",3,"d",4,"e",5,"f",null ), is(ImmutableMap.of("a",1,"b",2,"c",3,"d",4,"e",5)));
        assertThat(ImmutableUtil.mapOf("a",1,"b",2,"c",3,"d",4,"e",5,null,null), is(ImmutableMap.of("a",1,"b",2,"c",3,"d",4,"e",5)));
    }

    @SuppressWarnings("unchecked")
    @Test void testToMutableMapAndSetAndList() {
        final ImmutableMap<String,Object> imap = ImmutableMap.of(
            "string", "abcd",
            "map", ImmutableMap.of(
                "map.string", "klmno",
                "map.map", ImmutableMap.of("a","aa","b","bb"),
                "map.set", ImmutableSet.of("k","l","m"),
                "map.list", ImmutableList.of("x","y","z")
            ),
            "set", ImmutableSet.of(
                "set.string",
                ImmutableMap.of("a","aa","b","bb"),
                ImmutableSet.of("k","l","m"),
                ImmutableList.of("x","y","z")
            ),
            "list", ImmutableList.of(
                "list.string",
                ImmutableMap.of("a","aa","b","bb"),
                ImmutableSet.of("k","l","m"),
                ImmutableList.of("x","y","z")
            )
        );
        final Map<String,Object> map = ImmutableUtil.toMutable(imap);
        assertThat(map, not(instanceOf(ImmutableMap.class)));
        assertThat(map.get("map"), not(instanceOf(ImmutableMap.class)));
        assertThat(map.get("set"), not(instanceOf(ImmutableSet.class)));
        assertThat(map.get("list"), not(instanceOf(ImmutableSet.class)));

        final Map<String,Object> mmap = (Map<String,Object>)((Map<?,?>)map.get("map")).get("map.map");
        final Set<String>        mset = (Set<String>)((Map<?,?>)map.get("map")).get("map.set");
        final List<String>      mlist = (List<String>)((Map<?,?>)map.get("map")).get("map.list");

        // mutate some values, which will throw if immutable
        mmap.put("c", "cc");
        mset.add("o");
        mlist.add("l");
    }
}