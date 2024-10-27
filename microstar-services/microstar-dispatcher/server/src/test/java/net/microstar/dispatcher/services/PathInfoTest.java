package net.microstar.dispatcher.services;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class PathInfoTest {

    @Test void shouldSplitPathCorrectly() {
        assertThat(toString(new PathInfo("")),       is(", , , , /"));
        assertThat(toString(new PathInfo("/")),      is(", , , , /"));
        assertThat(toString(new PathInfo("/a")),     is("a, , , , /a"));
        assertThat(toString(new PathInfo("/a/b")),   is("a, b, /b, , /a/b"));
        assertThat(toString(new PathInfo("/a/b/c")), is("a, b, /b/c, /c, /a/b/c"));
        assertThat(toString(new PathInfo("a/b/c")),  is("a, b, /b/c, /c, /a/b/c"));
        assertThat(toString(new PathInfo("//a//b//c")), is("a, b, /b/c, /c, /a/b/c"));
    }

    private static String toString(PathInfo pi) {
        return String.join(", ", pi.first, pi.second, pi.afterFirst, pi.afterSecond, pi.all);
    }
}