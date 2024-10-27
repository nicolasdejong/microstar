package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class DynamicReferenceTest {
    private static final String TEXT = "abc";
    private static final String TEXT2 = "abc222";
    private static final String TEXT3 = "abc333";

    @Test void setAndGetValueShouldBeEqual() {
        final DynamicReference<String> vv = new DynamicReference<>();
        assertThat(vv.get(), is(nullValue()));
        vv.set(TEXT);
        assertThat(vv.get(), is(TEXT));
    }
    @Test void nullValueShouldLeadToDefaultValue() {
        final DynamicReference<String> vv = new DynamicReference<String>(null)
            .setDefault(TEXT);
        assertThat(vv.get(), is(TEXT));
        assertThat(vv.set("foo").get(), is("foo"));
        assertThat(vv.set(null).get(), is(TEXT));
    }
    @Test void nullValueShouldLeadToDefaultSuppliedValue() {
        final DynamicReference<String> vv = new DynamicReference<String>(null)
            .setDefault(() -> TEXT);
        assertThat(vv.get(), is(TEXT));
        assertThat(vv.set("foo").get(), is("foo"));
        assertThat(vv.set(null).get(), is(TEXT));
    }
    @Test void defaultSuppliedValueShouldOverrideDefaultValue() {
        final DynamicReference<String> vv = new DynamicReference<String>(null)
            .setDefault(TEXT)
            .setDefault(() -> TEXT2);
        assertThat(vv.get(), is(TEXT2));
    }
    @Test void settingANewValueShouldCallChangeHandler() {
        final AtomicInteger count1 = new AtomicInteger(0);
        final AtomicInteger count2 = new AtomicInteger(0);
        final AtomicReference<String> expectedVal = new AtomicReference<>();
        final AtomicReference<String> expectedOld = new AtomicReference<>();
        final DynamicReference<String> vv = new DynamicReference<String>(null)
            .setDefault(TEXT)
            .whenChanged(newVal -> {
                assertThat(newVal, is(expectedVal.get()));
                count1.incrementAndGet();
            })
            .whenChanged((oldVal, newVal) -> {
                assertThat(oldVal, is(expectedOld.get()));
                assertThat(newVal, is(expectedVal.get()));
                count2.incrementAndGet();
            });

        assertThat(count1.get(), is(0));
        assertThat(count2.get(), is(0));
        assertThat(vv.get(), is(TEXT));

        expectedOld.set(null); // whenChanged returns actual old value instead of default
        expectedVal.set(TEXT2);
        vv.set(TEXT2);

        assertThat(count1.get(), is(1));
        assertThat(count2.get(), is(1));

        expectedOld.set(vv.get());
        expectedVal.set(TEXT3);
        vv.set(TEXT3);

        assertThat(count1.get(), is(2));
        assertThat(count2.get(), is(2));

        vv.set(TEXT3); // no change

        assertThat(count1.get(), is(2));
        assertThat(count2.get(), is(2));
    }
    @Test void settingDefaultValueWhenCallingGetShouldCallChangeHandler() {
        final AtomicBoolean called = new AtomicBoolean(false);
        final DynamicReference<String> ref = new DynamicReference<String>(null)
            .setDefault(TEXT)
            .setDefaultIfNoValue(true)
            .whenChanged(val -> called.set(true));

        assertThat(ref.get(), is(TEXT));
        assertThat("Callback when setting in get() call", called.get(), is(true));
    }
}