package net.microstar.spring.settings;

import org.junit.jupiter.api.Test;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class PropsPathTest {

    @Test void shouldCreatePropsPathFromDottedString() {
        assertThat(PropsPath.of("a.b.c").raw(), is(List.of("a", "b", "c")));
        assertThat(PropsPath.of("a.[b.c].d").raw(), is(List.of("a", "b.c", "d")));

        assertThat(PropsPath.of("a.[2]c").raw(),  is(List.of("a", "2", "c")));
        assertThat(PropsPath.of("a[2].c").raw(),  is(List.of("a", "2", "c")));
        assertThat(PropsPath.of("a.[2].c").raw(), is(List.of("a", "2", "c")));
        assertThat(PropsPath.of("a[2]c").raw(),   is(List.of("a", "2", "c")));

        assertThat(PropsPath.of(".b.c").raw(), is(List.of("", "b", "c")));
        assertThat(PropsPath.of("a..c").raw(), is(List.of("a", "", "c")));
        assertThat(PropsPath.of("a.b.").raw(), is(List.of("a", "b", "")));

        assertThat(PropsPath.of(".").raw(), is(List.of("", "")));
        assertThat(PropsPath.of("").raw(), is(Collections.emptyList())); // this is a bit of a compromise vs empty key
    }
    @Test void emptyPathIsEmpty() {
        assertThat(PropsPath.of("").isEmpty(), is(true));
        assertThat(PropsPath.of("a").isEmpty(), is(false));
        assertThat(PropsPath.of(".").isEmpty(), is(false));
    }
    @Test void headShouldReturnFirstElementOfPath() {
        assertThat(PropsPath.of("a.b.c").head(), is("a"));
    }
    @Test void tailShouldReturnAllButFirstElementOfPath() {
        assertThat(PropsPath.of("a.b.c").tail().raw(), is(List.of("b", "c")));
    }
    @Test void originalShouldReturnOriginalProvidedPathString() {
        final String original = "a.b[2].c";
        assertThat(PropsPath.of(original).original(), is(original));
        assertThat(PropsPath.of(original).raw(), is(List.of("a", "b", "2", "c")));
    }
    @Test void toStringShouldReturnPathString() {
        assertThat(PropsPath.of("a.[b.c]d").toString(), is("a.[b.c].d"));
        assertThat(PropsPath.of("a.[2]d").toString(), is("a[2].d"));
        assertThat(PropsPath.of("a.[b.c][2].d").toString(), is("a.[b.c][2].d"));
    }
    @Test void rawShouldReturnPathAsList() {
        assertThat(PropsPath.of("a.[b.c].d").raw(), is(List.of("a", "b.c", "d")));
    }
    @Test void baseShouldReturnPartBeforeHead() {
        assertThat(PropsPath.of("a.[b.c].d[2].e").tail().tail().tail().base(), is(PropsPath.of("a.[b.c].d")));
    }
    @Test void visitShouldCallForEachPathElement() {
        int[] count = { 0 };
        String[] expected = { "a", "b.c", "d" };
        final @Nullable Object result =
            PropsPath.of("a.[b.c].d").visit("", (obj, propsPath) -> {
                assertThat(obj, is("@".repeat(count[0])));
                assertThat(propsPath.head(), is(expected[count[0]]));

                count[0]++;
                return obj + "@";
            });
        assertThat(count[0], is(3));
        assertThat(result, is("@@@"));
    }
}