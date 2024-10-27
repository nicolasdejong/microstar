package net.microstar.common.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collector;
import java.util.stream.Stream;

@SuppressWarnings("varargs")
public final class CollectionUtils {
    private CollectionUtils() {}

    /** Returns the elements from a that are also in b */
    public static <T> Set<T> intersectionOf(Collection<T> a, Collection<T> b) {
        final Set<T> set = new LinkedHashSet<>(a);
        set.retainAll(b);
        return set;
    }

    /** Returns the elements from a that are not in b */
    public static <T> Set<T> disjunctionLeft(Collection<T> a, Collection<T> b) {
        final Set<T> set = new LinkedHashSet<>(a);
        set.removeAll(b);
        return set;

    }

    /** Returns the elements from b that are not in a. Alias of disjunctionLeft(b,a). */
    public static <T> Set<T> disjunctionRight(Collection<T> a, Collection<T> b) {
        return disjunctionLeft(b, a);
    }

    @SafeVarargs
    public static <T> List<T> concat(Collection<T>... lists) {
        return Arrays.stream(lists).flatMap(Collection::stream).toList();
    }

    @SafeVarargs
    public static <T> Stream<T> concat(Stream<T> ... streams) {
        return Arrays.stream(streams).flatMap(stream -> stream);
    }

    public static <T> List<T>    removeIf(List<T> list, Predicate<T> whenToRemove) {
        final List<T> toRemove = list.stream().filter(whenToRemove).toList();
        toRemove.forEach(list::remove);
        return list;
    }
    public static <T> Set<T>     removeIf(Set<T>   set, Predicate<T> whenToRemove) {
        final List<T> toRemove = set.stream().filter(whenToRemove).toList();
        toRemove.forEach(set::remove);
        return set;
    }
    public static <K,T> Map<K,T> removeIf(Map<K,T> map, Predicate<T> whenToRemove) {
        final List<K> toRemove = map.entrySet().stream().filter(e -> whenToRemove.test(e.getValue())).map(Map.Entry::getKey).toList();
        toRemove.forEach(map::remove);
        return map;
    }

    public static <T> Stream<T>  reverse(Stream<T> stream) {
        //noinspection unchecked
        return (Stream<T>) stream.collect(Collector.of(
            LinkedList::new,
            LinkedList::addFirst,
            (d1,d2) -> { d2.addAll(d1); return d2; }
        )).stream();
    }
    public static <K,V> Map<K,V> reverse(Map<K,V> mapToReverse) {
        return reverse(mapToReverse.entrySet().stream()).collect(ImmutableUtil.toImmutableMap());
    }

    public static <T> Optional<T> first(T[] array)        { return array.length == 0 ? Optional.empty() : Optional.ofNullable(array[0]); }
    public static <T> Optional<T> first(List<T> list)     { return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(0)); }
    public static <T> Optional<T> first(Stream<T> stream) { return stream.findFirst(); }
    public static <T> Optional<T> last(T[] array)         { return array.length == 0 ? Optional.empty() : Optional.ofNullable(array[array.length-1]); }
    public static <T> Optional<T> last(List<T> list)      { return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(list.size()-1)); }
    public static <T> Optional<T> last(Stream<T> stream)  { return stream.reduce((t, e)->e); }

    public static <T> List<T> shuffledCopy(List<T> source) { return shuffledCopy(source, new Random()); }
    public static <T> List<T> shuffledCopy(List<T> source, Random random) {
        final ArrayList<T> copy = new ArrayList<>(source);
        Collections.shuffle(copy, random);
        return copy;
    }
}
