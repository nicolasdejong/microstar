package net.microstar.spring;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import java.util.Map;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class HttpHeadersFacadeTest {
    @SuppressWarnings("unused") // accessed via reflection
    static class WithGetHeader {
        public String getHeader(String name) { return Map.of("a", "A", "b", "B").get(name); }
    }
    @SuppressWarnings("unused") // accessed via reflection
    static class WithGetHeaders {
        public HttpHeaders getHeaders() { return new HttpHeaders() {{ add("a","A"); add("b","B"); }}; }
    }

    @Test void testForMap() {
        test(new HttpHeadersFacade(Map.of("a","A", "b","B")));
    }

    @Test void testForHttpHeaders() {
        test(new HttpHeadersFacade(new HttpHeaders() {{ set("a","A"); set("b","B"); }}));
    }

    @Test void testForClassWithGetHeader() {
        test(new HttpHeadersFacade(new WithGetHeader()));
    }

    @Test void testForClassWithGetHeaders() {
        test(new HttpHeadersFacade(new WithGetHeaders()));
    }

    private void test(HttpHeadersFacade headers) {
        assertThat(headers.getFirst("a").orElse(""), is("A"));
        assertThat(headers.getFirst("b").orElse(""), is("B"));
        assertThat(headers.getFirst("c").orElse(""), is(""));
    }
}