package net.microstar.spring.settings;

import com.google.common.collect.ImmutableMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.common.util.Utils;
import net.microstar.spring.EncryptionSettings;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.application.RestartableApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"NotNullFieldNotInitialized", "StaticVariableMayNotBeInitialized", "unused", "InstanceVariableMayNotBeInitialized"})
// fields only used for reflection test
@ExtendWith(MockitoExtension.class)
@ExtendWith(OutputCaptureExtension.class)
class DynamicPropertiesManagerTest {
    private static final EncryptionSettings encryption = EncryptionSettings.builder().build();
    private static final PropertySource<?> PROPERTIES_FROM_OTHER_SOURCES = new MapPropertySource("defaults", ImmutableUtil.mapOf(
        "propsA.num", "999",
        "propsA.string", "zzz",
        "propsA.numbers[0]", "1",
        "propsA.numbers[1]", "2",
        "propsA.numbers[2]", "3",
        "propsA.numbers[3]", "4",
        "propsB.a", "9",
        "propsB.b", "999",
        "propsB.c", "1234",
        "propsB.inner.d", "111",
        "maps.mapB.q", "33",
        "maps.mapB.f", "22",
        "maps.mapA.b", "1",
        "maps.mapA.c", "2",
        "maps.mapA.a", "3",
        "list[0]", "a",
        "list[1]", "b",
        "encValues.a", "{cipher}" + encryption.encrypt("value.of.encValue.a"),
        "props.number", "111",
        "props.string", "str",
        "props.list123[0]", "1",
        "props.list123[1]", "2",
        "props.listEmpty[0]", "1",
        "props.listEmpty[1]", "2",
        "props.map.a", "1",
        "props.map.b", "2",
        "props.map123.a", "1",
        "props.map123.b", "2"
    ));
    private static final PropsMap SETTINGS_1 = PropsMap.fromSettingsMap(Map.of(
        "propsA", Map.of("num", 123, "string", "abc", "numbers", List.of(11,22,33,44)),
        "propsB", Map.of("a", 1, "b", "2", "inner.d", 11, "inner.e", 22),
        "encValues.a", "{cipher}" + encryption.encrypt("value.of.encValue.a2"),
        "encValues.b", "{cipher}" + encryption.encrypt("value.of.encValue.b")
    ));
    private static final PropsMap SETTINGS_2 = PropsMap.fromSettingsMap(Map.of(
        "propsA", Map.of("num", 456, "string", "def"),
        "propsB", Map.of("a", 2, "b", "3"),
        "encValues.b", "{cipher}" + encryption.encrypt("value.of.encValue.b2")
    ));
    private static final PropsMap SETTINGS_MAPS = PropsMap.fromSettingsMap(Map.of(
        "maps", ImmutableMap.of(
            "mapA", ImmutableMap.of("b", "1", "c", "2", "a", 3),
            "mapB", ImmutableMap.of("q", "33", "f", "22")
        )
    ));
    private static final PropsMap SETTINGS_MAP_LIST_OVERRIDES = PropsMap.fromSettingsMap(Map.of(
        "list[0]", "aaa",
        "propsB.d", "ddd"
    ));


    @Builder @Jacksonized @ToString
    @DynamicProperties("encValues")
    public static class EncValues {
        public final String a;
        public final String b;
    }
    @Builder @Jacksonized @ToString
    @DynamicProperties("propsA")
    public static class PropsA {
        public final int num;
        public final String string;
        public final int[] numbers;
    }
    @Builder @Jacksonized @ToString
    @DynamicProperties("propsB")
    public static class PropsB {
        public final int a;
        public final int b;
        public final int c;
        public final Inner inner;

        @Builder @Jacksonized @ToString
        public static class Inner {
            public final int d;
            public final int e;
        }
    }
    @Builder @Jacksonized @ToString
    @DynamicProperties("maps")
    public static class MapsProps {
        public final ImmutableMap<String,Integer> mapA;
        public final ImmutableMap<String,Integer> mapB;
    }

    @SuppressWarnings("ConfigurationProperties") // no Spring PropertiesScan but that is not needed for just this test
    @ConfigurationProperties("prefix")
    @AllArgsConstructor @ToString
    public static class ConfigProps {
        public int number;
        public int anotherNumber;
    }


    @Mock private ConfigurableApplicationContext cac;
    @Mock private ConfigurableEnvironment env;
    @Mock private MicroStarApplication app;

    private MutablePropertySources propertySources = SpringProps.propertySources();
    private Map<String,Object> configurationProperties = Map.of();

    private static final    PropsA                  testFieldFinal = PropsA.builder().build();
    private static          PropsA                  testFieldNotVolatile;
    private static volatile PropsA                  testFieldVolatile;
    private static          AtomicReference<PropsA> testFieldRef;
    private static volatile AtomicReference<PropsA> testFieldVolatileRef;
    private static          AtomicReference<PropsA> testFieldNotFinalRef;
    private static final    AtomicReference<PropsA> testFieldFinalRef = new AtomicReference<>();
    private        volatile PropsA                  instanceTestField;


    @BeforeEach void setup() {
        ReflectionTestUtils.setField(RestartableApplication.class, "instanceOpt", Optional.of(app));

        when(cac.getEnvironment()).thenReturn(env);
        when(cac.getBeansWithAnnotation(ConfigurationProperties.class)).thenAnswer(mock -> configurationProperties);
        when(env.getPropertySources()).thenAnswer(mock -> propertySources);

        setExternalSettings(PropsMap.empty());
        DynamicPropertiesManager.setConfigurableApplicationContext(cac);
    }
    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    private void setExternalSettings(PropsMap settings) {
        propertySources = SpringProps.propertySources(
            List.of(new MapPropertySource("settings", settings.asFlatMap().getMap()), PROPERTIES_FROM_OTHER_SOURCES));

        DynamicPropertiesManager.setExternalSettings(settings);
    }

    @Test void shouldBeAbleToStoreSettings() {
        assertThat(DynamicPropertiesManager.getDynamicSettings().isEmpty(), is(true));
        DynamicPropertiesManager.setExternalSettings(SETTINGS_1);
        assertThat(DynamicPropertiesManager.getDynamicSettings(), is(SETTINGS_1));
    }
    @Test void shouldCorrectlyOverrideListAndMap() { // list should be replaced, map updated
        assertThat(DynamicPropertiesManager.getDynamicSettings().isEmpty(), is(true));
        setExternalSettings(SETTINGS_MAP_LIST_OVERRIDES);
        assertThat(DynamicPropertiesManager.getDynamicSettings(), is(SETTINGS_MAP_LIST_OVERRIDES));
        assertThat(DynamicPropertiesManager.getDynamicSettings().asDeepMap().getMap().get("list"), is(List.of("aaa")));
        assertThat(DynamicPropertiesManager.getCombinedSettings().asDeepMap().getMap().get("propsB"), is(Map.of("a","9", "b","999", "c","1234", "d", "ddd", "inner", Map.of("d","111"))));
    }
    @Test void shouldBeAbleToSetIndividualProperties() {
        Mockito.reset(cac, env, app); // mocks not used here
        DynamicPropertiesManager.setConfigurableApplicationContext(null);

        setExternalSettings(SETTINGS_1);
        assertThat(DynamicPropertiesManager.getProperty("propsA.num").orElse(null), is("123"));
        assertThat(DynamicPropertiesManager.getProperty("propsB.c", Integer.class).orElse(null), is(nullValue()));
        DynamicPropertiesManager.setProperty("propsB.c", 3);
        assertThat(DynamicPropertiesManager.getProperty("propsB.c", Integer.class).orElse(null), is(3));
        assertThat(DynamicPropertiesManager.getProperty("propsA.num").orElse(null), is("123")); // to show this is not affected
    }
    @Test void shouldBeAbleToGetIndividualProperties() {
        Mockito.reset(cac, env, app); // mocks not used here

        // When the ConfigurableApplicationContext exists the normal Spring getProperty calls
        // will be made that do not need testing here. Here the fallback only is tested.
        DynamicPropertiesManager.setConfigurableApplicationContext(null);

        setExternalSettings(SETTINGS_1);

        assertThat(DynamicPropertiesManager.getObjectProperty("propsA").orElse(null), instanceOf(Map.class));
        assertThat(DynamicPropertiesManager.getProperty("propsA.num").orElse(null), is("123"));
        assertThat(DynamicPropertiesManager.getProperty("nonexistent").isEmpty(), is(true));
        assertThat(DynamicPropertiesManager.getProperty("nonexistent", "defaultValue"), is("defaultValue"));

        assertThat(DynamicPropertiesManager.getProperty("propsA", PropsA.class).orElse(null), instanceOf(PropsA.class));
        assertThat(DynamicPropertiesManager.getProperty("propsA.num", Integer.class).orElse(null), is(123));
        assertThat(DynamicPropertiesManager.getProperty("nonexistent", PropsA.class).isEmpty(), is(true));
        assertThat(DynamicPropertiesManager.getProperty("nonexistent", PropsA.class, PropsA.builder().build()), instanceOf(PropsA.class));
    }
    @Test void overwritingMapShouldRemoveOldKeys() {
        DynamicPropertiesManager.setProperty("mapping", Map.of("foo", "/foo-path"));
        assertThat(DynamicPropertiesManager.getProperty("mapping.foo", ""), is("/foo-path"));

        DynamicPropertiesManager.setProperty("mapping", Map.of("bar", "/bar-path"));
        assertThat(DynamicPropertiesManager.getProperty("mapping.bar", ""), is("/bar-path"));
        assertThat(DynamicPropertiesManager.getProperty("mapping.foo", ""), is(""));

        DynamicPropertiesManager.setProperty("mapping.zoo", "/zoo");
        assertThat(DynamicPropertiesManager.getProperty("mapping.bar", ""), is("/bar-path"));
        assertThat(DynamicPropertiesManager.getProperty("mapping.zoo", ""), is("/zoo"));
    }

    @Test void shouldBeAbleToCreatePropertiesInstances() {
        setExternalSettings(SETTINGS_1);
        final PropsA propsA = DynamicPropertiesManager.getInstanceOf(PropsA.class);
        final PropsB propsB = DynamicPropertiesManager.getInstanceOf(PropsB.class);

        assertThat(propsA.num, is(123));
        assertThat(propsA.string, is("abc"));
        assertThat(propsA.numbers, is(new int[] { 11, 22, 33, 44}));

        assertThat(propsB.a, is(1));
        assertThat(propsB.b, is(2));
        assertThat(propsB.c, is(1234));
    }
    @Test void shouldBeAbleToCreatePropertiesInstancesOfInnerClasses() {
        setExternalSettings(SETTINGS_1);
        final PropsB.Inner bInner = DynamicPropertiesManager.getInstanceOf(PropsB.Inner.class);

        assertThat(bInner.d, is(11));
        assertThat(bInner.e, is(22));
    }
    @Test void propertiesShouldBeNotifiedOnNewSettings() {
        setExternalSettings(SETTINGS_1);
        final PropsA[] props1 = { null };
        final DynamicPropertyListener<PropsA> propsListener = (newProps, changedKeys) -> props1[0] = newProps;
        props1[0] = DynamicPropertiesManager.getInstanceOf(PropsA.class, propsListener);

        assertThat(props1[0].num, is(123));
        assertThat(props1[0].string, is("abc"));

        Utils.forceGarbageCollection();
        setExternalSettings(SETTINGS_2);

        assertThat(props1[0].num, is(456));
        assertThat(props1[0].string, is("def"));

        final PropsA props2 = DynamicPropertiesManager.getInstanceOf(PropsA.class);
        assertThat(props2.num, is(456));
        assertThat(props2.string, is("def"));
    }
    @Test void thereShouldBeOnlyOneInstancePerClass() {
        setExternalSettings(SETTINGS_1);
        final PropsA propsA1 = DynamicPropertiesManager.getInstanceOf(PropsA.class);
        final PropsA propsA2 = DynamicPropertiesManager.getInstanceOf(PropsA.class);
        final PropsA propsA3 = DynamicPropertiesManager.getInstanceOf(PropsA.class);
        final PropsB propsB1 = DynamicPropertiesManager.getInstanceOf(PropsB.class);
        final PropsB propsB2 = DynamicPropertiesManager.getInstanceOf(PropsB.class);

        assertThat(propsA1 == propsA2, is(true));
        assertThat(propsA2 == propsA3, is(true));
        assertThat(propsB1 == propsB2, is(true));
    }
    @Test void orderShouldRemainInInstance() {
        setExternalSettings(SETTINGS_MAPS);
        final MapsProps maps = DynamicPropertiesManager.getInstanceOf(MapsProps.class);
        assertThat(maps.mapA.keySet().stream().toList(), is(List.of("b","c","a")));
        assertThat(maps.mapB.keySet().stream().toList(), is(List.of("q","f")));
    }

    @Test void changedConfigurationPropertiesShouldLeadToRestart() {
        configurationProperties = Map.of("SpringProps", new ConfigProps(456,789));
        setExternalSettings(PropsMap.fromSettingsMap(Map.of("prefix.number", 3)));

        verify(app).restart();
    }
    @Test void changedDynamicPropertiesShouldNotLeadToRestart() {
        configurationProperties = Map.of("SpringProps", new ConfigProps(456,789));
        setExternalSettings(SETTINGS_1);

        verify(app, never()).restart();
    }

    @Test void shouldSupportEncryptedValues() {
        setExternalSettings(PropsMap.empty());

        assertThat(DynamicPropertiesManager.getProperty("encValues.a").orElse(""), is("value.of.encValue.a"));

        final EncValues encValues0 = DynamicPropertiesManager.getInstanceOf(EncValues.class);
        assertThat(encValues0.a, is("value.of.encValue.a"));

        setExternalSettings(SETTINGS_1);

        final EncValues encValues1 = DynamicPropertiesManager.getInstanceOf(EncValues.class);
        assertThat(encValues1.a, is("value.of.encValue.a2"));
        assertThat(encValues1.b, is("value.of.encValue.b"));

        setExternalSettings(SETTINGS_2);

        final EncValues encValues2 = DynamicPropertiesManager.getInstanceOf(EncValues.class);
        assertThat(encValues2.a, is("value.of.encValue.a"));
        assertThat(encValues2.b, is("value.of.encValue.b2"));
    }
    @Test void invalidEncryptionShouldJustLeadToWarnings(CapturedOutput output) {
        setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "encValues.a", "{cipher}invalidEncryptionString"
        )));
        final EncValues encValues = DynamicPropertiesManager.getInstanceOf(EncValues.class);
        assertThat(encValues.a, is("{cipher}invalidEncryptionString"));

        assertThat(output.getOut(), containsString("DECRYPTION FAILED for encValues.a"));
    }

    @Builder @Jacksonized @ToString
    public static class DiffProps {
        public final int number;
        public final String string;
        @Default public final List<Integer> list123 = Collections.emptyList();
        @Default public final List<Integer> listEmpty = Collections.emptyList();
        @Default public final Map<String,Integer> map = Collections.emptyMap();
        @Default public final Map<String,Integer> map123 = Collections.emptyMap();
    }

    @Test void differencesShouldBeDetectedOnUpdate() {
        final AtomicBoolean changeListenerWasCalled = new AtomicBoolean(false);
        final DynamicPropertyListener<DiffProps> listener = (props, changedKeys) -> { // define here so it won't be garbage collected
            changeListenerWasCalled.set(true);
            assertThat(props.number, is(222));                          assertTrue(changedKeys.contains("number"));
            assertThat(props.string, is("str2"));                       assertTrue(changedKeys.contains("string"));
            assertThat(props.list123, is(List.of(3)));                  assertTrue(changedKeys.contains("list123"));
            assertThat(props.listEmpty, is(Collections.emptyList()));   assertTrue(changedKeys.contains("listEmpty"));
            assertThat(props.map, is(Map.of("a",1, "b",2)));            // props.map wasn't changed
            assertThat(props.map123, is(Map.of("a",1, "b",22, "c",3))); assertTrue(changedKeys.contains("map123"));
        };
        DynamicPropertiesManager.addChangeListenerFor("props", DiffProps.class, listener);

        final PropsMap newSettings = PropsMap.fromDeepMap(Map.of(
            "props", Map.of(
                "number", "222",
                "string", "str2",
                "list123", List.of(3),
                "listEmpty", Collections.emptyList(), // overrides, leading to empty map
                "map", Collections.emptyMap(),        // overrides keys, but there are no keys here, so no change
                "map123", Map.of("b",22, "c",3)
            )
        ));
        setExternalSettings(newSettings);
        assertThat(changeListenerWasCalled.get(), is(true));
    }

    @Test void listenersShouldBeRemovedWhenAsked() {
        final AtomicBoolean changeListenerWasCalled = new AtomicBoolean(false);
        final DynamicPropertyListener<DiffProps> listener = (props, changedKeys) -> changeListenerWasCalled.set(true);

        DynamicPropertiesManager.addChangeListenerFor("props", DiffProps.class, listener);

        setExternalSettings(PropsMap.fromDeepMap(Map.of("props", Map.of("number", "333"))));
        assertThat(changeListenerWasCalled.get(), is(true));
        changeListenerWasCalled.set(false);

        setExternalSettings(PropsMap.fromDeepMap(Map.of("props", Map.of("number", "444"))));
        assertThat(changeListenerWasCalled.get(), is(true));
        changeListenerWasCalled.set(false);

        DynamicPropertiesManager.removeChangeListener(listener);

        setExternalSettings(PropsMap.fromDeepMap(Map.of("props", Map.of("number", "555"))));
        assertThat(changeListenerWasCalled.get(), is(false));
    }
}