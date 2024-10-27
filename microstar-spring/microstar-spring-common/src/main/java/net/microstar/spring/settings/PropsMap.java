package net.microstar.spring.settings;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.ImmutableUtil;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;

import static net.microstar.common.util.ExceptionUtils.noThrow;

@SuppressWarnings({"rawtypes", "unchecked"})
@Slf4j
@RequiredArgsConstructor
public class PropsMap {
    private static final PropsMap EMPTY = new PropsMap(Collections.emptyMap(), DepthType.DEEP);
    private final Map<String,Object> map;
    private final DepthType depth;

    private enum DepthType {
        COMBO, // Map is both DEEP and FLAT; dotted keys should be split
        DEEP,  // Map is DEEP, meaning values can be recursive (maps have map values, etc)
        FLAT   // Map is FLAT, meaning values can NOT be recursive (values are numbers or strings)
    }

    public static PropsMap       fromSettingsMap(Map<String,Object> map) {
        return new PropsMap(ImmutableMap.copyOf(map), DepthType.COMBO).deepen();
    }
    public static PropsMap       fromDeepMap(Map<String,Object> map) {
        return new PropsMap(toRecursiveImmutableMap(map), DepthType.DEEP);
    }
    public static PropsMap       fromFlatMap(Map<String,Object> map) {
        return new PropsMap(ImmutableMap.copyOf(map), DepthType.FLAT);
    }
    public static PropsMap       fromYaml(String yamlText) {
        return fromYamlMultiple(yamlText).stream().findFirst().orElseGet(PropsMap::empty);
    }
    public static List<PropsMap> fromYamlMultiple(String yamlText) {
        try {
            return fromYamlMultipleOrThrow(yamlText);
        } catch(final Exception yamlException) {
            log.error("Illegal yaml encountered -- document handled as if empty!: " + yamlException.getMessage());
            return Collections.emptyList();
        }
    }
    public static List<PropsMap> fromYamlMultiple(String yamlText, String profilesToSelect) {
        return filterMapsForProfiles(profilesToSelect, PropsMap.fromYamlMultiple(yamlText));
    }
    public static List<PropsMap> fromYamlMultipleOrThrow(String yamlText) {
        final Yaml yaml = new Yaml();
        final Iterable<Object> documents = yaml.loadAll(yamlText);
        return decrypt(StreamSupport
            .stream(documents.spliterator(), false)
            .map(obj -> obj instanceof Map ? (Map<String, Object>) obj : Map.of("singleton", obj))
            .map(PropsMap::fromSettingsMap)
            .toList(), "document");
    }
    public static PropsMap       empty() {
        return EMPTY;
    }
    public static List<PropsMap> filterMapsForProfiles(String profiles, List<PropsMap> maps) {
        return maps.stream()
            .filter(map -> hasProfile(profiles, map.get("spring.profiles").orElse("").toString()))
            .toList();
    }

    public PropsMap asDeepMap()             { return deepen(); }
    public PropsMap asFlatMap()             { return flatten(); }

    public String   toString()              { return depth + ":" + map; }
    public String   toPrettyString()        { return depth + " PropsMap {\n" + new Yaml().dump(map) + "}"; }
    public boolean  equals(Object otherObj) { return otherObj instanceof PropsMap other && flatten().getMap().equals(other.flatten().getMap()); }
    public int      hashCode()              { return flatten().getMap().hashCode(); }
    public boolean  isEmpty()               { return map.isEmpty(); }

    public Optional<Object>                      get(String key) { return getMapValue(map, key); }
    public Optional<String>                      getString(String key) { return getMapString(deepen().map, key); }
    public Optional<Integer>                     getInteger(String key) { return getMapInteger(deepen().map, key); }
    public Optional<ImmutableMap<String,Object>> getMap(String key) { return getMap(deepen().map, key).map(ImmutableMap::copyOf); }
    public ImmutableMap<String,Object>           getMap() { return ImmutableMap.copyOf(map); }
    public Optional<ImmutableList<Object>>       getList(String key) { return getMapList(map, key).map(ImmutableList::copyOf); }
    public ImmutableMap<String,Object>           getSettingsMap() { return braceDots(deepen().map); }

    public PropsMap set(String key, Object value) {
        final Map<String,Object> deepMap = unflatten(new LinkedHashMap<>(flatten().map));
        setInMap(deepMap, key, value);
        return fromDeepMap(deepMap);
    }

    public PropsMap getWithOverrides(PropsMap overrides) {
        return PropsMap.fromDeepMap(copyAMapAndOverrideWithBMap(deepen().map, overrides.deepen().map));
    }
    public static PropsMap getWithOverrides(PropsMap initial, PropsMap overrides) {
        return initial.getWithOverrides(overrides);
    }

    private PropsMap flatten() {
        return switch(depth) {
            case COMBO, DEEP -> PropsMap.fromFlatMap(flatten(map));
            case FLAT -> this;
        };
    }
    private PropsMap deepen() {
        return switch(depth) {
            case COMBO, FLAT -> PropsMap.fromDeepMap(unflatten(map));
            case DEEP -> this;
        };
    }


    private static boolean hasProfile(String profilesInConfiguration, String profilesToSelect) {
        final Set<String> cfgSet    = Set.of(profilesInConfiguration.split("\\s*,\\s*"));
        final Set<String> selectSet = Set.of(profilesToSelect.split("\\s*,\\s*"));

        return cfgSet.isEmpty() || !intersectionOf(cfgSet, selectSet).isEmpty();
    }
    private static <T> Set<T> intersectionOf(Set<T> setA, Set<T> setB) {
        final Set<T> set = new LinkedHashSet<>(setA);
        set.retainAll(setB);
        return set;
    }
    private static Map<String,Object> copyAMapAndOverrideWithBMap(Map<String, Object> aMap, Map<String, Object> bMap) { return copyAMapAndOverrideWithBMap(aMap, bMap, 0); }
    private static Map<String,Object> copyAMapAndOverrideWithBMap(Map<String, Object> aMap, Map<String, Object> bMap, int callDepth) {
        final Map<String,Object> result = new LinkedHashMap<>();
        aMap.forEach((key, o) ->
            result.put(key, o instanceof Map aChild && bMap.get(key) instanceof Map bChild // NOSONAR - map generic type
                    ? copyAMapAndOverrideWithBMap(aChild, bChild, callDepth+1)
                    : bMap.getOrDefault(key, o)
                )
        );
        bMap.keySet().stream()
            .filter(key -> !result.containsKey(key))
            .forEach(key -> result.put(key, bMap.get(key)));
        return result;
    }
    private static Map<String,Object> flatten(Map<String, Object> map) { // NOSONAR sonar complexity doesn't see inner methods
        final class Inner {
            private Inner() {/*Singleton*/}
            private static void doFlatten(String blockedParentKey, @Nullable Object parentValue, Map<String, Object> targetMap) {
                if (parentValue instanceof Map map) doFlattenMap(targetMap, blockedParentKey, map);
                else
                if (parentValue instanceof List<?> list) doFlattenList(targetMap, blockedParentKey, list);
                else
                  targetMap.put(blockedParentKey, parentValue);
            }
            private static void doFlattenMap(Map<String, Object> targetMap, String blockedParentKey, Map map) {
                map.forEach((key, value) ->
                    doFlatten(joinKeys(blockedParentKey, key).replaceAll("\\.$",""), value, targetMap)
                );
            }
            private static void doFlattenList(Map<String, Object> targetMap, String blockedParentKey, List list) {
                if(list.isEmpty()) targetMap.put(blockedParentKey, new ArrayList<>()); // flat lists should not contain complex objects but no other way to set 'empty list'
                IntStream.range(0, list.size())
                    .forEach(index -> {
                        final String key = "%s[%d]".formatted(blockedParentKey, index);
                        final Object listItem = list.get(index);
                        final Map<String,Object> deeper = new LinkedHashMap<>();
                        doFlatten(key, listItem, deeper);
                        targetMap.putAll(deeper);
                    });
            }
            private static String block(String in) { return in.contains(".") && !in.startsWith("[") ? ("[" + in + "]") : in; }
            private static String joinKeys(String blockedParentKey, @Nullable Object b) {
                return blockedParentKey + "." + (b == null ? "null" : block(b.toString()));
            }
        }
        final Map<String, Object> targetMap = new LinkedHashMap<>();
        map.forEach((key, value) -> Inner.doFlatten(Inner.block(key), value, targetMap));
        return targetMap;
    }
    private static Map<String,Object> unflatten(Map<String,Object> flatMap) {
        final Map<String, Object> targetMap = new LinkedHashMap<>();
        flatMap.forEach((key, value) -> setInMap(targetMap, key, value));
        return targetMap;
    }
    private static <T> T unflatten(T toUnflatten) {
        if(toUnflatten instanceof Map map) return (T)unflatten(map);
        if(toUnflatten instanceof List list) return (T)new ArrayList<>(list.stream().map(PropsMap::unflatten).toList());
        return toUnflatten;
    }
    private static ImmutableMap<String,Object> braceDots(Map<String,Object> map) {
        final Map<String,Object> bracedMap = new LinkedHashMap<>();
        map.forEach((key,val) -> bracedMap.put(key.contains(".") ? "[" + key + "]" : key, braceDots(val)));
        return ImmutableMap.copyOf(bracedMap);
    }
    private static List<Object> braceDots(List<Object> list) {
        return list.stream().map(PropsMap::braceDots).toList();
    }
    private static Object braceDots(Object object) {
        if(object instanceof Map map) return braceDots(map);
        if(object instanceof List list) return braceDots(list);
        return object;
    }

    private static ImmutableMap<String,Object> toRecursiveImmutableMap(Map<String,Object> map) {
        return map.entrySet().stream()
            .map(entry -> entry.getValue() instanceof List childList ? Map.entry(entry.getKey(), toRecursiveImmutableList(childList)) :
                entry.getValue() instanceof Map  childMap  ? Map.entry(entry.getKey(), toRecursiveImmutableMap(childMap)) :
                    entry)
            .collect(ImmutableUtil.toImmutableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    private static ImmutableList<Object> toRecursiveImmutableList(List<Object> list) {
        return ImmutableList.copyOf(list.stream()
            .map(value -> value instanceof List childList ? toRecursiveImmutableList(childList) :
                value instanceof Map  childMap  ? toRecursiveImmutableMap(childMap) :
                    value)
            .toList()
        );

    }

    private static void setInMap(Map<String,Object> targetMap, String targetPath, Object valueToSet) { // NOSONAR -- it will probably be harder to read if this method is split up
        if(targetPath.isEmpty()) {
            targetMap.put(targetPath, unflatten(valueToSet));
            return;
        }
        PropsPath.of(targetPath).visit(targetMap, (target, path) -> {
            final boolean isLast = path.tail().isEmpty();
            final String key = path.head();
            final boolean nextIsList = !isLast && path.tail().head().matches("^\\d+$");

            if (target instanceof Map map) { // NOSONAR map generics
                if(isLast) {
                    // In flat maps there are sometimes overlaps. See below.
                    if(!(valueToSet instanceof Map) && map.get(key) instanceof Map deepMap) {
                        deepMap.put("", unflatten(valueToSet));
                    } else {
                        map.put(key, unflatten(valueToSet));
                    }
                    return null; // stop visiting
                } else {
                    final Object newTarget = map.computeIfAbsent(key, unused -> nextIsList ? new ArrayList<>() : new LinkedHashMap<>());
                    if ( nextIsList && !(newTarget instanceof List)) throw new IllegalStateException("not a list at " + key + " in " + targetPath + ": " + newTarget);
                    if (!nextIsList &&   newTarget instanceof List)  throw new IllegalStateException("expected list at " + key + " in " + targetPath + ": " + newTarget);
                    return newTarget;
                }
            } else
            if (target instanceof List list) { // NOSONAR list generics
                final int index = noThrow(() -> Integer.parseInt(key)).orElseThrow(() -> new IllegalStateException("Unexpected key index " + key + " in " + key));
                if (isLast) return setListItem(list, index, unflatten(valueToSet));
                else        return setListItemIfNotExists(list, index, LinkedHashMap::new);
            }

            // Sometimes there are lines like:
            //  some.path=value
            //  some.path.deeper=anotherValue
            // In this case the 'deeper' cannot be added to 'path' because 'path' is not a map.
            // When that happens the 'some.path' will become a map with
            // - an empty key containing the original value ('value' here).
            // - 'deeper' containing 'anothervalue'

            final String pathToNewMap = path.base().original();
            final Optional<Object> replacedValue = getMapValue(targetMap, pathToNewMap);

            final Map<String,Object> newMap = new LinkedHashMap<>();
            replacedValue.ifPresent(rv -> newMap.put("", rv));
            setInMap(newMap, path.toString(), valueToSet);
            setInMap(targetMap, pathToNewMap, newMap);

            return null; // stop visiting
        });
    }
    private static <T> T setListItem(List<T> list, int index, T value) {
        while(list.size() <= index) list.add(null);
        list.set(index, value);
        return value;
    }
    private static <T> T setListItemIfNotExists(List<T> list, int index, Supplier<T> valueToSet) {
        while(list.size() <= index) list.add(null);
        T value = list.get(index);
        if(value == null) { value = valueToSet.get(); list.set(index, value); }
        return value;
    }


    /** Returns the value of a nested map from a dotted key like "some.deeper.map.value" or "some.list[3].value" */
    private static Optional<String>             getMapString(Map<String,Object> map, String key) {
        return getMapValue(map, key)
            .map(Object::toString)
            .map(s -> decrypt(s, key));
    }
    private static Optional<Integer>            getMapInteger(Map<String,Object> map, String key) {
        return getMapValue(map, key)
            .map(obj -> obj instanceof String s ? noThrow(() -> (Object)Integer.parseInt(s)).orElse(s) : obj) // NOSONAR false positive on cast
            .filter(Integer.class::isInstance)
            .map(Integer.class::cast);
    }
    private static Optional<Map<String,Object>> getMap(Map<String,Object> map, String key) {
        return getMapValue(map, key)
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(val -> decrypt(val, key));
    }
    private static Optional<List<Object>>       getMapList(Map<String,Object> map, String key) {
        return getMapValue(map, key)
            .filter(List.class::isInstance)
            .map(List.class::cast)
            .map(list -> decrypt(list, key));
    }

    private static Optional<Object> getMapValue(Map<String,?> map, String path) {
        return getMapValue(map, PropsPath.of(path));
    }
    private static Optional<Object> getMapValue(Object source, PropsPath path) {
        if(path.isEmpty()) return Optional.of(decrypt(source, ""));
        if(source instanceof Map map) return getMapValue(map, path);
        if(source instanceof List list) return getListValue(list, path);
        return Optional.empty();
    }
    private static Optional<Object> getMapValue(Map<String,?> map, PropsPath path) {
        if (path.isEmpty()) return Optional.of(map);

        final Object flatValue = map.get(path.toString());
        if(flatValue != null) return Optional.of(decrypt(flatValue, path.toString()));

        final String key = path.head();
        if (map.containsKey("[" + key + "]")) return Optional.ofNullable(map.get("[" + key + "]")).map(val -> decrypt(val, key));

        final @Nullable Object value = getFirst(map, key, "[" + key + "]");

        if(value == null) return Optional.empty();
        if(value instanceof Map deeperMap)   return getMapValue(deeperMap, path.tail());
        if(value instanceof List deeperList) return getMapValue(deeperList, path.tail());
        if(!path.tail().isEmpty()) return Optional.empty(); // deeper data is requested but his code doesn't know how to get there
        return Optional.of(decrypt(value, path.original()));
    }
    private static Optional<Object> getListValue(List<?> list, PropsPath path) {
        Optional<Object> result = Optional.empty();

        final String key = path.head();
        if(key.matches("^\\d+$")) {
            final int index = Integer.parseInt(key);
            if(index < list.size()) result = getMapValue(list.get(index), path.tail());
        }
        return result;
    }

    private static <T> T decrypt(T in, String errorHint) {
        return SpringProps.decrypt(in, errorHint);
    }
    private static @Nullable Object getFirst(Map<?,?> map, String... keys) {
        for(final String key : keys) {
            final Object value = map.get(key);
            if(value != null) return value;
        }
        return null;
    }
}
