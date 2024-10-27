package net.microstar.spring.settings;

import net.microstar.common.util.Utils;
import net.microstar.spring.EncryptionSettings;
import org.hamcrest.CoreMatchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.isA;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicPropertyRefTest {
    private static final String NON_EXISTING_PATH = "non.existing.path";
    private static final String TEST_STRING_PATH = "test.string";
    private static final String TEST_INT_PATH = "test.int";
    private static final String DEFAULT_STRING_VALUE = "someDefaultValue";
    private static final String TEST_STRING_VALUE = "someValue";
    private static final Integer DEFAULT_INT_VALUE = 123;
    private static final int TEST_INT_VALUE = 234;

    @BeforeEach void setup() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            TEST_STRING_PATH, TEST_STRING_VALUE,
            TEST_INT_PATH, TEST_INT_VALUE
        )));
    }
    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void ofPath() {
        assertThat(DynamicPropertyRef.of(TEST_STRING_PATH).get(), is(TEST_STRING_VALUE));
        assertThrows(NoSuchElementException.class, () -> DynamicPropertyRef.of(NON_EXISTING_PATH).get()); // NOSONAR -- of() won't throw
    }
    @Test void ofTypeAndPath() {
        assertThat(DynamicPropertyRef.of(TEST_INT_PATH, Integer.class).get(), is(TEST_INT_VALUE));
    }
    @Test void ofComplexType() {
        final Map<String,String> mapInSettings = Map.of("aKey", "aVal", "bKey", "bVal");
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "test.map", mapInSettings,
            TEST_STRING_PATH, TEST_STRING_VALUE
        )));
        final DynamicPropertyRef<Map<String,String>> mapRef = DynamicPropertyRef.of("test.map", new ParameterizedTypeReference<>(){});
        assertThat(mapRef.getMainTypeClass(), is(Map.class));
        assertThat(mapRef.get(), is(mapInSettings));

        final DynamicPropertyRef<String> stringRef = DynamicPropertyRef.of(TEST_STRING_PATH, String.class);
        assertThat(stringRef.getMainTypeClass(), is(String.class));
        assertThat(stringRef.get(), is(TEST_STRING_VALUE));
    }
    @Test void withDefault() {
        assertThat(DynamicPropertyRef.of(NON_EXISTING_PATH).withDefault(DEFAULT_STRING_VALUE).get(), is(DEFAULT_STRING_VALUE));
        assertThat(DynamicPropertyRef.of(NON_EXISTING_PATH, Integer.class).withDefault(DEFAULT_INT_VALUE).get(), is(DEFAULT_INT_VALUE));
    }
    @Test void buildDefaultWithoutDefault() {
        assertThat(DynamicPropertyRef.of("non.existing.path", EncryptionSettings.class).get(), isA(EncryptionSettings.class));
        assertThat(DynamicPropertyRef.of("non.existing.path", new ParameterizedTypeReference<Map<String,String>>(){}).get(), isA(Map.class));
    }
    @Test void aConfiguredGlobalDefaultShouldBeUsed() {
        final EncryptionSettings defaultEnc = EncryptionSettings.builder().build();
        try {
            assertNotSame(DynamicPropertyRef.of("non.existing.path", EncryptionSettings.class).get(), defaultEnc);

            DynamicPropertyRef.setDefaultInstance(EncryptionSettings.class, defaultEnc);
            assertSame(DynamicPropertyRef.of("non.existing.path", EncryptionSettings.class).get(), defaultEnc);
        } finally {
            DynamicPropertyRef.setDefaultInstance(EncryptionSettings.class, null);
        }
        assertNotSame(DynamicPropertyRef.of("non.existing.path", EncryptionSettings.class).get(), defaultEnc);
    }
    @Test void getOptional() {
        assertThat(DynamicPropertyRef.of(TEST_STRING_PATH).getOptional(), is(Optional.of(TEST_STRING_VALUE)));
        assertThat(DynamicPropertyRef.of(NON_EXISTING_PATH).getOptional(), is(Optional.empty()));
    }
    @Test void testToString() {
        assertThat(DynamicPropertyRef.of(TEST_STRING_PATH).toString(), is(TEST_STRING_VALUE));
        assertThat(DynamicPropertyRef.of(NON_EXISTING_PATH).withDefault(DEFAULT_STRING_VALUE).toString(), is(DEFAULT_STRING_VALUE));
        assertThat(DynamicPropertyRef.of(NON_EXISTING_PATH).toString(), is(""));
    }
    @Test void onChange() {
        final String[] lastChange = { null };
        DynamicPropertyRef.of(TEST_STRING_PATH)
            .onChange(change -> lastChange[0] = change);

        assertThat(lastChange[0], is(nullValue()));
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(TEST_STRING_PATH, TEST_STRING_VALUE + "!")));
        assertThat(lastChange[0], is(TEST_STRING_VALUE + "!"));
    }
    @Test void shouldBeGarbageCollected() {
        DynamicPropertyRef<String> ref = DynamicPropertyRef.of(TEST_STRING_PATH);
        final WeakReference<DynamicPropertyRef<String>> weakRef = new WeakReference<>(ref);
        assertThat(weakRef.get(), CoreMatchers.is(ref));
        assertThat(ref.get(), is(TEST_STRING_VALUE));
        //noinspection ConstantConditions,UnusedAssignment -- set to null for garbage collection check
        ref = null;
        Utils.forceGarbageCollection();
        assertThat(weakRef.get(), nullValue());
    }
}