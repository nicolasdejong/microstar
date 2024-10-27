package net.microstar.spring.settings;

import net.microstar.common.util.ImmutableUtil;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class PropsMapTest {
    private static final Map<String,Object> TEST_DEEP_MAP = sort(ImmutableUtil.mapOf(
        "number", "123",
        "dotted.key", "dotted.value",
        "some", Map.of("deeper", Map.of("map", Map.of(
            "list", List.of("1","2","3",Map.of("num", "4", "dotted.list", List.of("5","6"))),
            "value", "abc",
            "value2", "abc2",
            "even", Map.of("deeper", Map.of(
                "value", "12345"
            )),
            "deep.dotted.key", "ddk"
        ))),
        "not", Map.of("a", Map.of("dotted", Map.of("value", "2"))),
        "logging", Map.of("level", Map.of(
            "some", Map.of("package", Map.of("name", Map.of("here", Map.of(
                "deeper", Map.of("ClassName", "INFO"),
                "another", Map.of("Type", "DEBUG")
            )))),
            "other", Map.of("pak", Map.of(
                "TypeName", "TRACE"
            ))
        ))
    ));
    private static final Map<String,Object> TEST_SETTINGS_MAP = sort(ImmutableUtil.mapOf(
        "number", "123",
        "[dotted.key]", "dotted.value",
        "some.deeper.map", Map.of(
            "list", List.of("1","2","3",Map.of("num", "4", "[dotted.list]", List.of("5","6"))),
            "value", "abc",
            "value2", "abc2",
            "even.deeper", Map.of(
                "value", "12345"
            ),
            "[deep.dotted.key]", "ddk"
        ),
        "not.a.dotted.value", "2",
        "logging.level", Map.of(
            "some.package.name.here", Map.of(
                "deeper.ClassName", "INFO",
                "another.Type", "DEBUG"
            ),
            "other.pak", Map.of(
                "TypeName", "TRACE"
            )
        )
    ));
    private static final Map<String,Object> TEST_FLAT_MAP = sort(ImmutableUtil.mapOf(
        "number", "123",
        "[dotted.key]", "dotted.value",
        "some.deeper.map.list[0]", "1",
        "some.deeper.map.list[1]", "2",
        "some.deeper.map.list[2]", "3",
        "some.deeper.map.list[3].num", "4",
        "some.deeper.map.list[3].[dotted.list][0]", "5",
        "some.deeper.map.list[3].[dotted.list][1]", "6",
        "some.deeper.map.value", "abc",
        "some.deeper.map.value2", "abc2",
        "some.deeper.map.even.deeper.value", "12345",
        "some.deeper.map.[deep.dotted.key]", "ddk",
        "not.a.dotted.value", "2",
        "logging.level.some.package.name.here.deeper.ClassName", "INFO",
        "logging.level.some.package.name.here.another.Type", "DEBUG",
        "logging.level.other.pak.TypeName", "TRACE"
    ));
    private static final PropsMap TEST_DEEP_PROPS_MAP = PropsMap.fromDeepMap(TEST_DEEP_MAP);
    private static final PropsMap TEST_SETTINGS_PROPS_MAP = PropsMap.fromSettingsMap(TEST_SETTINGS_MAP);
    private static final PropsMap TEST_FLAT_PROPS_MAP = PropsMap.fromFlatMap(TEST_FLAT_MAP);

    @Test void mapsShouldKeepTheirOrder() {
        final List<PropsMap> maps = PropsMap.fromYamlMultiple(String.join("\n",
            "someMap:",
            "  b: 1,",
            "  c: 2,",
            "  d: 3,",
            "  a: 4",
            "otherMap: { \"B\": 1, \"C\": 2, \"A\": 3 }",
            ""
        ));
        assertThat( maps.get(0).getMap("someMap").orElseThrow().keySet().stream().toList(), is(List.of("b","c","d","a")));
        assertThat( maps.get(0).getMap("otherMap").orElseThrow().keySet().stream().toList(), is(List.of("B","C","A")));
    }
    @Test void settingsMapKeysShouldBeSplitOnDot() {
        assertThat(TEST_SETTINGS_PROPS_MAP.asDeepMap().getMap().keySet(), is(Set.of("number", "dotted.key", "some", "not", "logging")));
        assertThat(sort(TEST_SETTINGS_PROPS_MAP.asFlatMap().getMap()), is(TEST_FLAT_MAP));

        for(final PropsMap map : List.of(TEST_SETTINGS_PROPS_MAP, TEST_DEEP_PROPS_MAP, TEST_FLAT_PROPS_MAP)) {
            Map.of(
                "[dotted.key]", "dotted.value",
                "some.deeper.map.[deep.dotted.key]", "ddk",
                "logging.level.some.package.name.here.deeper.ClassName", "INFO"
            ).forEach((key, val) -> {
                assertThat(map.toString(), map.get(key).orElse(""), is(val));
                assertThat(map.toString(), map.asFlatMap().get(key).orElse(""), is(val));
                assertThat(map.toString(), map.asDeepMap().get(key).orElse(""), is(val));
                if (!key.contains("[")) assertThat(map.toString(), map.asFlatMap().getMap().get(key), is(val));
            });
        }
    }
    @Test void flattenShouldProvideNonRecursiveMap() {
        for(final PropsMap map : List.of(TEST_SETTINGS_PROPS_MAP, TEST_DEEP_PROPS_MAP, TEST_FLAT_PROPS_MAP)) {
            for (final Object value : map.asFlatMap().getMap().values()) {
                assertThat(value instanceof String, is(true));
            }
        }
    }
    @Test void flatGetShouldReturnFlatValue() {
        assertThat(TEST_DEEP_PROPS_MAP.asFlatMap().get("some.deeper.map").orElse(null), is(nullValue()));
        assertThat(TEST_DEEP_PROPS_MAP.asFlatMap().get("some.deeper.map.value").orElse(null), is("abc"));
    }
    @Test void combineShouldBraceDottedKeys() {
        assertThat(PropsMap.fromDeepMap(Map.of(
            "dotted.value", "abc"
        )).getSettingsMap(), is(Map.of("[dotted.value]", "abc")));
    }
    @Test void mapShouldSurviveFlattening() {
        for(final PropsMap map : List.of(TEST_SETTINGS_PROPS_MAP, TEST_DEEP_PROPS_MAP, TEST_FLAT_PROPS_MAP)) {
            assertThat(sort(map.asFlatMap().getMap()), is(sort(TEST_FLAT_MAP)));
            assertThat(sort(map.asFlatMap().asDeepMap().asFlatMap().getMap()), is(sort(TEST_FLAT_MAP)));
            assertThat(sort(map.asFlatMap().asDeepMap().getMap()), is(sort(TEST_DEEP_MAP)));
            assertThat(sort(PropsMap.fromSettingsMap(map.getSettingsMap()).asDeepMap().getMap()), is(sort(TEST_DEEP_MAP)));
        }
    }
    @Test void copyAMapAndOverrideBMapShouldMergeMapsCorrectly() {
        final PropsMap overrides = PropsMap.fromSettingsMap(Map.of(
            "number", "234",
            "[dotted.key]", "new.dotted.value",
            "some.deeper.map.even.deeper", Map.of("a", 1, "b", 2),
            "some.a", "aaa"
        ));
        for(final PropsMap map : List.of(TEST_SETTINGS_PROPS_MAP, TEST_DEEP_PROPS_MAP, TEST_FLAT_PROPS_MAP)) {
            final PropsMap overridden = map.getWithOverrides(overrides);
            assertThat(overridden.getString("number").orElseThrow(), is("234"));
            assertThat(overridden.getString("[dotted.key]").orElseThrow(), is("new.dotted.value"));
            assertThat(sort(overridden.getMap("some.deeper.map.even.deeper").orElseThrow()), is(sort(Map.of("a",1, "b", 2, "value", "12345"))));
            assertThat(overridden.getString("not.a.dotted.value").orElseThrow(), is("2"));
            assertThat(overridden.getString("some.a").orElseThrow(), is("aaa"));
        }

        final Map<String,Object> aMap = Map.of("list123", List.of(1,2,3),   "string","str",  "listEmpty",List.of(1,2), "number",111, "map12",Map.of("a",1,"b",2), "map",Map.of("a",1, "b",2, "c",3));
        final Map<String,Object> bMap = Map.of("list123", List.of(1,2,3,4), "string","str3", "listEmpty",emptyList(),  "number",222, "map12",emptyMap()                                            );
        final Map<String,Object> exp  = Map.of("list123", List.of(1,2,3,4), "string","str3", "listEmpty",emptyList(),  "number",222, "map12",Map.of("a",1,"b",2), "map",Map.of("a",1, "b",2, "c",3));
        assertThat(PropsMap.fromDeepMap(aMap).getWithOverrides(PropsMap.fromDeepMap(bMap)).getMap(), is(exp));
    }
    @Test void testEqualsAndHashCode() {
        assertThat(TEST_DEEP_PROPS_MAP.equals(TEST_SETTINGS_PROPS_MAP), is(true));
        assertThat(TEST_SETTINGS_PROPS_MAP.equals(TEST_DEEP_PROPS_MAP), is(true));
        assertThat(TEST_SETTINGS_PROPS_MAP.equals(TEST_FLAT_PROPS_MAP), is(true));
        assertThat(TEST_SETTINGS_PROPS_MAP.set("foo","bar").equals(TEST_FLAT_PROPS_MAP), is(false));

        assertThat(TEST_DEEP_PROPS_MAP.hashCode(), is(TEST_SETTINGS_PROPS_MAP.hashCode()));
        assertThat(TEST_SETTINGS_PROPS_MAP.hashCode(), is(TEST_FLAT_PROPS_MAP.hashCode()));
        assertThat(TEST_SETTINGS_PROPS_MAP.set("foo","bar").hashCode(), not(is(TEST_FLAT_PROPS_MAP.hashCode())));
    }
    @Test void gettersShouldReturnValues() {
        assertThat(TEST_SETTINGS_PROPS_MAP.get("some.deeper.map.list[2]").orElseThrow(), is("3"));
        assertThat(TEST_FLAT_PROPS_MAP.get("not.a.dotted.value").orElseThrow(), is("2"));
        assertThat(TEST_DEEP_PROPS_MAP.getInteger("number").orElseThrow(), is(123));
        assertThat(TEST_SETTINGS_PROPS_MAP.getList("some.deeper.map.list").orElseThrow(), is(List.of("1","2","3",sort(Map.of("num", "4", "dotted.list", List.of("5","6"))))));

        assertThat(TEST_SETTINGS_PROPS_MAP.get("some.deeper.map.list[3][dotted.list][1]").orElseThrow(), is("6"));
        assertThat(TEST_DEEP_PROPS_MAP    .get("some.deeper.map.list[3][dotted.list][1]").orElseThrow(), is("6"));
        assertThat(TEST_FLAT_PROPS_MAP    .get("some.deeper.map.list[3][dotted.list][1]").orElseThrow(), is("6"));
    }
    @Test void setShouldResultInAlteredMap() {
        assertThat(TEST_SETTINGS_PROPS_MAP.set("number", "345").get("number").orElseThrow(), is("345"));
        assertThat(TEST_SETTINGS_PROPS_MAP.set("some.deeper.map.list[3][dotted.list][1]", "678").get("some.deeper.map.list[3][dotted.list]").orElseThrow(), is(List.of("5","678")));
    }

    @Test void flattenShouldKeepNamesCorrect() {
        assertThat(sort(PropsMap.fromSettingsMap(Map.of(
            "props.number", 1,
            "props.emptyMap", new HashMap<>(),
            "props.emptyList", new ArrayList<>(),
            "props.map", Map.of("number", 1, "string", "str"),
            "props.list", List.of("a", "b", "c")
        )).asFlatMap().getMap()), is(sort(Map.of(
            "props.number", 1,
            "props.emptyList", Collections.emptyList(),
            "props.map.number", 1,
            "props.map.string", "str",
            "props.list[0]", "a",
            "props.list[1]", "b",
            "props.list[2]", "c"
        ))));
        assertThat(sort(PropsMap.fromDeepMap(Map.of(
            "number.name", 1,
            "emptyMap.name", new HashMap<>(),
            "emptyList.name", new ArrayList<>(),
            "list.name", List.of("a", "b"),
            "map.name", Map.of("number.name", 1, "string.name", "str")
        )).asFlatMap().getMap()), is(sort(Map.of(
            "[number.name]", 1,
            "[emptyList.name]", Collections.emptyList(),
            "[list.name][0]", "a",
            "[list.name][1]", "b",
            "[map.name].[number.name]", 1,
            "[map.name].[string.name]", "str"
        ))));
    }

    @Test void deepenShouldSurviveFlatten() {
        final PropsMap p1 = PropsMap.fromSettingsMap(Map.of(
            "props.a", 1,
            "props.map", Map.of("a",1, "b",2),
            "props.list[0]", 1,
            "props.list[1]", 2,
            "props.list[2]", 3
        )).asFlatMap().asDeepMap();
        final Map<String,Object> expMap = Map.of("props", Map.of("a",1,"map", Map.of("a",1, "b",2), "list",List.of(1,2,3) ));

        assertThat(sort(p1.getMap()), is(sort(expMap)));
    }
    @Test void emptyCollectionShouldSurviveFlatten() {
        final Map<String,Object> dataMap = ImmutableUtil.mapOf(
            "list", List.of("a","b"),
            "map", Map.of("a",1, "b",2),
            "emptyList", Collections.emptyList(),
            "emptyMap", Collections.emptyMap()
        );
        final Map<String,Object> expectedMap = ImmutableUtil.mapOf(
            "list", List.of("a","b"),
            "map", Map.of("a",1, "b",2),
            "emptyList", Collections.emptyList()
        );
        assertThat(PropsMap.fromDeepMap(dataMap).asFlatMap().asDeepMap().getMap(), is(expectedMap));
    }

    @Test void unflattenShouldHandleOverlappingKeys() {
        testUnflattenFlattenConsistency(Map.of(
                "java.runtime.name", "openJDK",
                "java.runtime.version", "17",
                "java.vendor", "SomeVendor",
                "java.vendor.url", "someUrl"
            ), Map.of(
                "java", Map.of(
                    "runtime", Map.of(
                        "name", "openJDK",
                        "version", "17"
                    ),
                    "vendor", Map.of(
                        "", "SomeVendor",
                        "url", "someUrl"
                    )
            )
        ));
        // ImmutableMap keeps order intact
        testUnflattenFlattenConsistency(ImmutableUtil.mapOf(
            "a.b", "1",
            "a.b.c", "2",
            "a.b.c.d", "3"
        ));
        testUnflattenFlattenConsistency(ImmutableUtil.mapOf(
            "a.b.c.d", "3",
            "a.b.c", "2",
            "a.b", "1"
        ));
        testUnflattenFlattenConsistency(ImmutableUtil.mapOf(
            "a.b", "1",
            "a.b.c.d", "3",
            "a.b.c", "2"
        ));
    }
    private static void testUnflattenFlattenConsistency(Map<String,Object> flatMap) { testUnflattenFlattenConsistency(flatMap, null); }
    private static void testUnflattenFlattenConsistency(Map<String,Object> flatMap, @Nullable Map<String,Object> expectedDeepMap) {
        final PropsMap flatPropsMap = PropsMap.fromFlatMap(flatMap);
        final PropsMap deepPropsMap = flatPropsMap.asDeepMap();

        if(expectedDeepMap != null) {
            assertThat(sort(deepPropsMap.getMap()), is(sort(expectedDeepMap)));
        }
        final Map<String,Object> resultMap = deepPropsMap.asFlatMap().getMap();
        assertThat(sort(resultMap), is(sort(flatMap)));
    }

    @Test void yamlShouldResultInCorrectMap() {
        final String yaml =
            """
            authentication.userRoles:
              admin: ADMIN
              foo: BAR
              "[user1@domain.org]": ADMIN
              user2: FOO
            """;

        final PropsMap map = PropsMap.fromYaml(yaml);
        assertThat(map.getString("authentication.userRoles.[user1@domain.org]").orElse(""), is("ADMIN"));
        assertThat(map.getString("authentication.userRoles.user2").orElse(""), is("FOO"));
    }


    private static <K,V> Map<K,V> sort(Map<K,V> map) {
        final TreeMap<K,V> copy = new TreeMap<>(map);
        for(final K key : new HashSet<>(copy.keySet())) {
            if(copy.get(key) instanceof Map) {
                //noinspection unchecked
                copy.put(key, (V)sort((Map<K, V>) copy.get(key)));
            }
            if(copy.get(key) instanceof List) {
                //noinspection unchecked,rawtypes
                copy.put(key, (V)((Collection) copy.get(key)).stream().map(obj -> (obj instanceof Map ? sort((Map)obj) : obj)).toList());
            }
        }
        return copy;
    }
}