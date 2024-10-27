package net.microstar.common.util;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Slf4j
class UtilsTest {
    private static final Class<?> thisClass = Utils.staticThis();

    @Test void testFirstNotNull() {
        assertThat(Utils.firstNotNull(null, 1, null, 2), is(1));
        assertThrows(NullPointerException.class, ()  -> Utils.firstNotNull(null, null));
        assertThrows(NullPointerException.class, ()  -> Utils.firstNotNull((Object) null));
        assertThrows(NullPointerException.class, Utils::firstNotNull);
    }
    @Test void testFirstNotNullOrEmpty() {
        assertThat(Utils.firstNotNullOrElseEmpty(null, 1, null, 2), is(Optional.of(1)));
        assertThat(Utils.firstNotNullOrElseEmpty(), is(Optional.empty()));
        assertThat(Utils.firstNotNullOrElseEmpty((Object)null), is(Optional.empty()));
        assertThat(Utils.firstNotNullOrElseEmpty(null, null), is(Optional.empty()));
    }
    @Test void testFirstSupplyNotNullOrEmpty() {
        assertThat(Utils.firstSupplyNotNullOrElseEmpty(null, () -> 1, null, () -> 2), is(Optional.of(1)));
        assertThat(Utils.firstSupplyNotNullOrElseEmpty(), is(Optional.empty()));
        assertThat(Utils.firstSupplyNotNullOrElseEmpty((Supplier<?>)null), is(Optional.empty()));
        assertThat(Utils.firstSupplyNotNullOrElseEmpty(null, null), is(Optional.empty()));
    }
    @Test void testFirstPresent() {
        assertThat(Utils.firstPresent(Optional.empty()), is(Optional.empty()));
        assertThat(Utils.firstPresent(Optional.empty(), Optional.empty()), is(Optional.empty()));
        assertThat(Utils.firstPresent(Optional.of("a")), is(Optional.of("a")));
        assertThat(Utils.firstPresent(Optional.empty(), Optional.of("a"), Optional.empty()), is(Optional.of("a")));

        assertThat(Utils.firstPresent(Optional::empty), is(Optional.empty()));
        assertThat(Utils.firstPresent(Optional::empty, Optional::empty), is(Optional.empty()));
        assertThat(Utils.firstPresent(() -> Optional.of("a")), is(Optional.of("a")));
        assertThat(Utils.firstPresent(Optional::empty, () -> Optional.of("a"), Optional::empty), is(Optional.of("a")));
    }
    @Test void testPipe() {
        final UnaryOperator<Integer> inc = n -> n + 1;
        assertThat(Utils.pipe(5), is(5));
        assertThat(Utils.pipe(5, inc), is(6));
        assertThat(Utils.pipe(5, inc, inc), is(7));
        assertThat(Utils.pipe(5, inc, inc, inc), is(8));
    }
    @Test void staticThisShouldReturnThisClass() {
        assertThat(Utils.staticThis(), is(UtilsTest.class));
        assertThat(thisClass, is(UtilsTest.class));
    }
    @Test void testRequiredCondition() {
        final Exception e = assertThrows(Utils.FailedConditionException.class, () -> Utils.requireCondition("message for value {}", 123, val -> val < 100));
        assertThat(e.getMessage(), is("message for value 123"));
        assertDoesNotThrow(() -> Utils.requireCondition(12, val -> val < 100));
    }
    @Test void testPeek() {
        final AtomicReference<String> ref = new AtomicReference<>();
        Utils.peek("foo", ref::set);
        assertThat(ref.get(), is("foo"));
    }
    @Test void testCompareUtil() {
        assertThat(Utils.is(5).smallerThan(7), is(true));
        assertThat(Utils.is(7).smallerThan(5), is(false));

        assertThat(Utils.is(5).smallerThanOrEqualTo(7), is(true));
        assertThat(Utils.is(7).smallerThanOrEqualTo(5), is(false));
        assertThat(Utils.is(7).smallerThanOrEqualTo(7), is(true));

        assertThat(Utils.is(5).equalTo(5), is(true));
        assertThat(Utils.is(7).equalTo(5), is(false));

        assertThat(Utils.is(5).greaterThan(7), is(false));
        assertThat(Utils.is(7).greaterThan(5), is(true));

        assertThat(Utils.is(5).greaterThanOrEqualTo(7), is(false));
        assertThat(Utils.is(7).greaterThanOrEqualTo(5), is(true));
        assertThat(Utils.is(7).greaterThanOrEqualTo(7), is(true));

        assertThat(Utils.is(5).not().smallerThan(7), is(false));

        assertThat(Utils.compareUsing(SemanticStringComparator.IGNORING_CASE).is("abc").smallerThan("def"), is(true));
    }
}
