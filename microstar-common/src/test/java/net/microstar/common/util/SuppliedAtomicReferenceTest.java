package net.microstar.common.util;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class SuppliedAtomicReferenceTest {

    @Test void shouldSetSuppliedValue() {
        final int n = 123;
        final SuppliedAtomicReference<Integer> ref = new SuppliedAtomicReference<>(() -> n);
        assertThat(ref.get(), is(n));
        assertThat(ref.get(), is(n));
    }
    @Test void shouldResupplyAfterReset() {
        final int[] n = { 1 };
        final SuppliedAtomicReference<Integer> ref = new SuppliedAtomicReference<>(() -> n[0]++);
        assertThat(ref.get(), is(1));
        assertThat(ref.get(), is(1));
        ref.reset();
        assertThat(ref.get(), is(2));
        assertThat(ref.get(), is(2));
    }
}