package net.microstar.spring;

import lombok.RequiredArgsConstructor;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.settings.SpringProps;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.PropertySources;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.Nullable;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class SpringPropsTest {

    @Test void shouldGetActiveProfileNames() {
        assertThat(SpringProps.getActiveProfileNames(), is(List.of("default")));
        assertThat(SpringProps.getActiveProfileNames("--spring.profiles.active=foo,bar"), is(List.of("foo", "bar")));
        System.setProperty("spring.profiles.active","profileA,profileB");
        assertThat(SpringProps.getActiveProfileNames(), is(List.of("profileA","profileB")));
        System.clearProperty("spring.profiles.active");
    }
    @Test void documentsFromYamlTextShouldCreateCorrectMaps() {
        final List<PropsMap> maps = PropsMap.fromYamlMultiple(getYamlText("testInput"));

        // some quick checks to see if the yaml is read successfully
        assertThat(maps.size(), is(4));
        assertThat(maps.get(0).get("servers").orElseThrow(), is(List.of("server-a", "server-b", "server-c")));
        assertThat(maps.get(1).get("profile").orElse(""), is("dev"));
        assertThat(maps.get(2).getMap().size(), is(2));
        assertThat(maps.get(3), is(PropsMap.fromSettingsMap(Map.of("singleton", "justSomeString"))));

        assertThat(PropsMap.fromYamlMultiple("} invalid input {"), is(Collections.emptyList()));
    }
    @Test void documentsFromYamlShouldHandleMultipleDocsPerYaml() {
        final String yamlText = getYamlText("withProfiles");
        final Function<String,String> getMapNamesForProfiles = profiles ->
            PropsMap.fromYamlMultiple(yamlText, profiles).stream()
                .map(m -> (String)m.get("name").orElse(""))
                .sorted()
                .collect(Collectors.joining(","));

        Map.of(
            "a", "a,ad",
            "b", "b",
            "c", "cd",
            "d", "ad,cd",
            "a,b", "a,ad,b",
            "a,c", "a,ad,cd",
            "a,d", "a,ad,cd",
            "b,d,e", "ad,b,cd"
        ).forEach((select,expected) -> assertThat(select + " -> " + expected, getMapNamesForProfiles.apply(select), is(expected)));
    }
    @Test void mergePropertySourcesShouldCorrectlyOverride() {
        final PropertySources sources = SpringProps.propertySources(
            Map.entry("loaded third",      ImmutableUtil.mapOf(
                "num", 1,
                "text", "zoo",
                "level3", 4444,
                "numbers[0]", 0,
                "numbers[1]", 11,
                "numbers[2]", 22
            )),
            Map.entry("loaded second",     Map.of(
                "num", 2,
                "level2", 333,
                "level3", 444,
                "text", "foo",
                "map.b", 222,
                "map.deeper.b", 444
            )),
            Map.entry("loaded first", ImmutableUtil.mapOf(
                "num", 3,
                "level1", 22,
                "level2", 33,
                "level3", 44,
                "numbers[0]", 1,
                "numbers[1]", 2,
                "numbers[2]", 3,
                "numbers[3]", 6,
                "deeper[0].a", 55,
                "deeper[1].a", 66,
                "some.deeper.also", 345,
                "map.c", 22,
                "map.deeper.c", 44
            )),
            Map.entry("defaults",              ImmutableUtil.mapOf(
                "num", 4,
                "level0", 1,
                "level1", 2,
                "level2", 3,
                "level3", 4,
                "text", "bar",
                "deeper[0].a", 55,
                "deeper[1].a", 66,
                "deeper[1].b", 666,
                "some.deeper.num", 123,
                "map.a", 1,
                "map.b", 2,
                "map.deeper.a", 3,
                "map.deeper.b", 4,
                "map.deeper.more.a", 5
            ))
        );
        final PropsMap result = SpringProps.mergePropertySources(sources);
        final Yaml yaml = new Yaml();
        assertThat(yaml.dump(sort(result.getMap())), is(yaml.dump(sort(Map.of(
            "num", 1,
            "level0", 1,
            "level1", 22,
            "level2", 333,
            "level3", 4444,
            "text", "zoo",
            "numbers", List.of(0, 11, 22),
            "deeper", List.of(Map.of("a",55), Map.of("a",66)),
            "some", Map.of("deeper", Map.of("num", 123, "also", 345)),
            "map", Map.of("a",1, "b",222, "c", 22, "deeper", Map.of("a",3, "b",444, "c",44, "more", Map.of("a", 5)))
        )))));
    }
    @Test void shouldGetKeysFromCommandLineInput() {
        assertThat(SpringProps.fromCommandLine("bar", "foo", "--foo=123", "--bar=234"), is(Optional.of("234")));
    }
    @Test void fromResourceShouldGiveValueForKey() {
        assertThat(SpringProps.fromResource("deepList[2].b.foo", "/SpringProps/testInput"), is(Optional.of("222")));
    }

    @SuppressWarnings("ALL")
    @ConfigurationProperties(value = "test-prefix")
    public static class TestProps {
        private int a;
        private boolean b;
        public  int c;
        private Inner inner = new Inner();
        private int d;

        public int getA() { return a; }
        public boolean isB() { return b; }
        public Inner getInner() { return inner; }

        public static class Inner {
            public int ia;
            public int ib;
        }
    }
    @SuppressWarnings("ALL")
    @ConfigurationProperties(prefix = "foo")
    @RequiredArgsConstructor
    public static class TestProps2 {
        public final int a;
        public final int b;
    }
    @SuppressWarnings("ALL")
    @ConfigurationProperties
    @RequiredArgsConstructor
    public static class TestProps3 {
        public int a;
        public int b;
    }

    @Test void getConfigurationPropertiesNamesOfShouldReturnAllProperties() {
        assertThat(SpringProps.getConfigurationPropertiesNamesOf(new TestProps()), is(List.of(
            "test-prefix.a",
            "test-prefix.b",
            "test-prefix.c",
            "test-prefix.inner.ia",
            "test-prefix.inner.ib"
        )));
        assertThat(SpringProps.getConfigurationPropertiesNamesOf(new TestProps2(1,2)), is(List.of(
            "foo.a",
            "foo.b"
        )));
        assertThat(SpringProps.getConfigurationPropertiesNamesOf(new TestProps3()), is(List.of(
            "a",
            "b"
        )));
    }

    @SuppressWarnings("unchecked")
    @Test void normalizeDeepMapShouldRemoveAllUnsupportedTypes() {
        final Map<String,Object> template = Map.of(
            "string", "stringValue",
            "int", 123,
            "double", 1.23d,
            "simpleList", List.of("a","b","c"),
            "simpleMap", Map.of("a",1, "b",2),
            "deepList", List.of(Map.of("a1",1,"a2",2), Map.of("b1",1,"b2",2)),
            "deepMap", Map.of("a", Map.of("a1",1,"a2",2), "b", Map.of("b1",1,"b2",2))
        );
        class Other {
            private final @Nullable String inner;
            public Other(@Nullable String inner) { this.inner = inner; }
            public String toString() { if(inner == null) throw new IllegalStateException("no value"); return inner; }
        }
        final Map<String,Object> mapIn = ImmutableUtil.toMutable(template);
        mapIn.put("other", new Other("otherText"));
        ((List<Map<String,Object>>)mapIn.get("deepList")).get(0).put("a3", new Other("list-other-a3"));
        ((Map<String,Map<String,Object>>)mapIn.get("deepMap")).get("a").put("a3", new Other("map-other-a3"));
        mapIn.put("otherNull", new Other(null));

        final Map<String,Object> mapExpected = ImmutableUtil.toMutable(template);
        mapExpected.put("other", "otherText");
        ((List<Map<String,Object>>)mapExpected.get("deepList")).get(0).put("a3", "list-other-a3");
        ((Map<String,Map<String,Object>>)mapExpected.get("deepMap")).get("a").put("a3", "map-other-a3");
        mapExpected.put("otherNull", "Other(no value)");

        assertThat(SpringProps.normalizeDeepMap(mapIn), is(mapExpected));
    }

    private static String getYamlText(String name) {
        return getResourceText("/SpringProps/%s.yml".formatted(name));
    }
    private static String getResourceText(String name) {
        try (
            final StringWriter writer = new StringWriter();
            @Nullable
            final InputStream inputStream = SpringPropsTest.class.getResourceAsStream(name);
            final InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(inputStream), StandardCharsets.UTF_8)
        ) {
            reader.transferTo(writer);
            return writer.toString();
        } catch(final Exception e) {
            if(!name.startsWith("/")) return getResourceText("/" + name);
            throw new IllegalStateException("Resource not found: " + name);
        }
    }

    @SuppressWarnings("varargs")
    @SafeVarargs
    private static <K,V> Map<K,V> join(Map<K,V>... maps) {
        return Stream.of(maps).collect(Collector.of(
            LinkedHashMap::new,
            LinkedHashMap::putAll,
            (d1, d2) -> { d1.putAll(d2); return d1; }
        ));
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