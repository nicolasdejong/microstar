package net.microstar.spring.settings;

import lombok.Builder;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import net.microstar.common.util.Utils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class DynamicPropertiesRefTest {
    private static final String STRING_VALUE = "abc";
    private static final int NUMBER_VALUE = 123;
    private static final String VALUE_PATH = "value";

    @DynamicProperties(VALUE_PATH)
    @Builder @Jacksonized @ToString
    static class TestClass {
        @Nullable public final String string;
        public final int number;
    }

    @Builder @Jacksonized @ToString
    static class TestClassWithoutPrefix {
        @Nullable public final String string;
        public final int number;
    }

    @AfterEach void cleanup() { DynamicPropertiesManager.clearAllState(); }

    @Test void getExisting() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            VALUE_PATH, Map.of("string", STRING_VALUE, "number", NUMBER_VALUE)
        )));

        final DynamicPropertiesRef<TestClass> ref = DynamicPropertiesRef.of(TestClass.class);
        assertThat(ref.get().string, is(STRING_VALUE));
        assertThat(ref.get().number, is(NUMBER_VALUE));
    }
    @Test void getNonExisting() {
        final DynamicPropertiesRef<TestClass> ref = DynamicPropertiesRef.of(TestClass.class);
        assertThat(ref.get().string, is(nullValue()));
        assertThat(ref.get().number, is(0));
    }
    @Test void getClassWithoutPrefix() {
        final DynamicPropertiesRef<TestClassWithoutPrefix> ref = DynamicPropertiesRef.of(TestClassWithoutPrefix.class);
        assertThat(ref.get().string, is(nullValue()));
        assertThat(ref.get().number, is(0));

        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "string", STRING_VALUE, "number", NUMBER_VALUE
        )));
        assertThat(ref.get().string, is(STRING_VALUE));
        assertThat(ref.get().number, is(NUMBER_VALUE));
    }

    @Test void onChange() {
        final boolean[] called = { false };
        DynamicPropertiesRef.of(TestClass.class)
            .onChange((instance, changes) -> {
                assertThat(instance.getClass(), is(TestClass.class));
                assertThat(changes, is(Set.of("string", "number")));
                assertThat(instance.string, is(STRING_VALUE));
                assertThat(instance.number, is(NUMBER_VALUE));
                called[0] = true;
            });
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            VALUE_PATH, Map.of("string", STRING_VALUE, "number", NUMBER_VALUE)
        )));
        assertThat(called[0], is(true));
    }
    @Test void removeListenersShouldPreventOnChange() {
        final boolean[] called = { false };
        DynamicPropertiesRef.of(TestClass.class)
            .onChange((instance, changes) -> called[0] = true)
                .removeChangeListeners();
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            VALUE_PATH, Map.of("string", STRING_VALUE, "number", NUMBER_VALUE)
        )));
        assertThat(called[0], is(false));
    }
    @Test void shouldBeGarbageCollected() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            VALUE_PATH, Map.of("string", STRING_VALUE, "number", NUMBER_VALUE)
        )));
        DynamicPropertiesRef<TestClass> ref = DynamicPropertiesRef.of(TestClass.class);
        assertThat(ref.get().string, is(STRING_VALUE));

        // Now that we still have a reference, a garbage collect should not interfere
        Utils.forceGarbageCollection();
        assertThat(ref.get().string, is(STRING_VALUE));
        DynamicPropertiesManager.setProperty(VALUE_PATH + ".string", "def"); // this won't be picked up if garbage collected
        assertThat(ref.get().string, is("def"));

        // Clear the reference and see that the ref is garbage collected
        final WeakReference<DynamicPropertiesRef<TestClass>> weakRef = new WeakReference<>(ref);
        //noinspection ConstantConditions,UnusedAssignment -- set to null, so it will be garbage collected
        ref = null;
        Utils.forceGarbageCollection();
        assertThat(weakRef.get(), is(nullValue()));
    }
}