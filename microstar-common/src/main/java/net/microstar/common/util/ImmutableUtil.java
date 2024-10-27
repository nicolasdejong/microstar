package net.microstar.common.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collector;

@SuppressWarnings({
    "squid:S3878", // Using new Object[] instead of varargs to prevent ambiguity
    "unchecked"    // Immutable(Map/Set/List) to Map/Set/List cast is ok
})
public final class ImmutableUtil {
    private static final ImmutableMap<?,?> EMPTY_MAP  = ImmutableMap.of(); // NOSONAR -- Map.of() is not possible here
    private static final ImmutableSet<?>   EMPTY_SET  = ImmutableSet.of(); // NOSONAR -- Set.of() is not possible here
    private static final ImmutableList<?>  EMPTY_LIST = ImmutableList.of(); // NOSONAR -- List.of() is not possible here
    private ImmutableUtil() {/*util class*/}

    public static <K, V> ImmutableMap<K,V> emptyMap() {
        //noinspection unchecked
        return (ImmutableMap<K, V>) EMPTY_MAP;
    }
    public static <V>    ImmutableSet<V>   emptySet() {
        //noinspection unchecked
        return (ImmutableSet<V>) EMPTY_SET;
    }
    public static <V>    ImmutableList<V>  emptyList() {
        //noinspection unchecked
        return (ImmutableList<V>) EMPTY_LIST;
    }

    public static <K,V,M extends Map<K,V>> ImmutableMap<K,V> copyAndMutate(M map, Consumer<Map<K,V>> mutator) {
        final Map<K,V> mutableMap = new LinkedHashMap<>(map);
        mutator.accept(mutableMap);
        return ImmutableMap.copyOf(mutableMap);
    }
    public static <V,S extends Set<V>>     ImmutableSet<V>   copyAndMutate(S set, Consumer<Set<V>> mutator) {
        final Set<V> mutableSet = new LinkedHashSet<>(set);
        mutator.accept(mutableSet);
        return ImmutableSet.copyOf(mutableSet);
    }
    public static <V,L extends List<V>>    ImmutableList<V>  copyAndMutate(L set, Consumer<List<V>> mutator) {
        final List<V> mutableList = new ArrayList<>(set);
        mutator.accept(mutableList);
        return ImmutableList.copyOf(mutableList);
    }

    public static <K,V> ImmutableMap<K,V> copyAndRemoveIf(Map<K,V> map, BiPredicate<K,V> shouldRemove) {
        return copyAndMutate(map, m -> new LinkedHashSet<>(m.keySet()).forEach(key -> { if(shouldRemove.test(key, m.get(key))) m.remove(key); }));
    }
    public static <V> ImmutableSet<V>     copyAndRemoveIf(Set<V>   set, Predicate<V> shouldRemove) {
        return copyAndMutate(set, s -> new LinkedHashSet<>(s).forEach(val -> { if(shouldRemove.test(val)) s.remove(val); }));
    }
    public static <V> ImmutableList<V>    copyAndRemoveIf(List<V> list, Predicate<V> shouldRemove) {
        return copyAndMutate(list, l -> new LinkedList<>(l).forEach(val -> { if(shouldRemove.test(val)) l.remove(val); }));
    }

    public static <K,V,T extends Map<K,V>> void updateMapRef (AtomicReference<T> ref, Consumer<Map<K,V>> mutator) {
        ref.set((T)copyAndMutate(ref.get(), mutator));
    }
    public static <V,S extends Set<V>>     void updateSetRef (AtomicReference<S> ref, Consumer<Set<V>> mutator) {
        ref.set((S)copyAndMutate(ref.get(), mutator));
    }
    public static <V,L extends List<V>>    void updateListRef(AtomicReference<L> ref, Consumer<List<V>> mutator) {
        ref.set((L)copyAndMutate(ref.get(), mutator));
    }

    public static <K,V,M extends Map<K,V>> void removeFromMapRef (AtomicReference<M> ref, BiPredicate<K,V> shouldRemove) {
        ref.set((M)copyAndRemoveIf(ref.get(), shouldRemove));
    }
    public static <V,S extends Set<V>>     void removeFromSetRef (AtomicReference<S> ref, Predicate<V> shouldRemove) {
        ref.set((S)copyAndRemoveIf(ref.get(), shouldRemove));
    }
    public static <V,L extends List<V>>    void removeFromListRef(AtomicReference<L> ref, Predicate<V> shouldRemove) {
        ref.set((L)copyAndRemoveIf(ref.get(), shouldRemove));
    }

    /** Collects entries for given key and value mapper and keeps last added in case of collisions */
    public static <T, K, V> Collector<T, ?, ImmutableMap<K,V>> toImmutableMap(
        Function<? super T, ? extends K> keyMapper,
        Function<? super T, ? extends V> valueMapper) {
        return Collector.of(
            ImmutableMap.Builder<K, V>::new,
            (builder, element) -> builder.put(keyMapper.apply(element), valueMapper.apply(element)),
            (builder1, builder2) -> builder1.putAll(builder2.buildKeepingLast()),
            ImmutableMap.Builder::buildKeepingLast);
    }

    public static <K, V> Collector<Map.Entry<K,V>, ?, ImmutableMap<K,V>> toImmutableMap() {
        return toImmutableMap(Map.Entry::getKey, Map.Entry::getValue);
    }
    public static <T> Collector<T, ?, ImmutableSet<T>> toImmutableSet() {
        return Collector.of(
            ImmutableSet.Builder<T>::new,
            ImmutableSet.Builder::add,
            (builder1, builder2) -> builder1.addAll(builder2.build()),
            ImmutableSet.Builder::build);
    }
    public static <T> Collector<T, ?, ImmutableList<T>> toImmutableList() {
        return Collector.of(
            ImmutableList.Builder<T>::new,
            ImmutableList.Builder::add,
            (builder1, builder2) -> builder1.addAll(builder2.build()),
            ImmutableList.Builder::build);
    }

    @SafeVarargs
    public static <K,V> ImmutableMap<K,V> mapOfEntries(Map.Entry<K,V>... entries) {
        final ImmutableMap.Builder<K,V> builder = ImmutableMap.builder();
        for (final Map.Entry<K, V> entry : entries) builder.put(entry.getKey(), entry.getValue());
        return builder.build();
    }
    public static <K,V> ImmutableMap<K,V> mapKeys(Map<K,V> map, UnaryOperator<K> mapper) {
        final ImmutableMap.Builder<K,V> builder = ImmutableMap.builder();
        for (final Map.Entry<K, V> entry : map.entrySet()) builder.put(mapper.apply(entry.getKey()), entry.getValue());
        return builder.build();
    }
    public static <K,V> ImmutableMap<K,V> mapValues(Map<K,V> map, UnaryOperator<V> mapper) {
        final ImmutableMap.Builder<K,V> builder = ImmutableMap.builder();
        for (final Map.Entry<K, V> entry : map.entrySet()) builder.put(entry.getKey(), mapper.apply(entry.getValue()));
        return builder.build();
    }

    /** Convenience function for creating an immutable map with more pairs than the api supports compile-time type-checked.
      * This way creating such a map is still very readable (unlike the Map.Entry variation) and since these maps are
      * typically created once at type construction time a possible runtime exception will be quickly found during
      * development. Unlike Map.of(), ImmutableMaps have an iteration order that is equal to the order entries are added.
      */
    @SuppressWarnings("unchecked")
    public static <K,V> ImmutableMap<K,V> mapOf(K key, V value, Object... tuples) {
        final ImmutableMap.Builder<K,V> builder = ImmutableMap.builder();
        builder.put(key, value);
        for(int i=0; i<tuples.length - 1; i+=2) {
            final K tupleKey = (K)tuples[i];
            final V tupleValue = (V)tuples[i+1];

            //noinspection ConditionCoveredByFurtherCondition -- the tupleKey == null doesn't seem redundant
            if(tupleKey == null || tupleValue == null) continue;

            builder.put(tupleKey, tupleValue);
        }
        return builder.build();
    }
    public static <K,V> ImmutableMap<K,V> mapOf(K k1, V v1, K k2, V v2) { return mapOf(k1, v1, new Object[] { k2, v2 }); }
    public static <K,V> ImmutableMap<K,V> mapOf(K k1, V v1, K k2, V v2, K k3, V v3) { return mapOf(k1, v1, new Object[] { k2, v2, k3, v3 }); }

    public static <K,V> Map<K,V> toMutable(Map<K,V> imap) {
        final Map<K,V> map = new LinkedHashMap<>(); // to keep order, as in ImmutableMap
        imap.forEach((key, v) -> map.put(key, toMutable(v)));
        return map;
    }
    public static <V> Set<V> toMutable(Set<V> iset) {
        final Set<V> set = new LinkedHashSet<>(); // to keep order, as in ImmutableSet
        iset.forEach(v -> set.add(toMutable(v)));
        return set;
    }
    public static <V> List<V> toMutable(List<V> ilist) {
        final List<V> list = new ArrayList<>();
        ilist.forEach(v -> list.add(toMutable(v)));
        return list;
    }

    private static <V> V toMutable(V value) {
        // Type may be Immutable(Map/Set/List) or Unmodifiable(Map/Set/List)
        if (value instanceof Map<?, ?> im) return (V)toMutable(im);
        if (value instanceof Set<?>    is) return (V)toMutable(is);
        if (value instanceof List<?>   il) return (V)toMutable(il);
        return value;
    }
}
