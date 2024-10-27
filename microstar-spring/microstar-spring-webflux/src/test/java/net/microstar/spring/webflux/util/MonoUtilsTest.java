package net.microstar.spring.webflux.util;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class MonoUtilsTest {

    @Test void firstPresentShouldTriggerOnFirst() {
        final Mono<String> mono = MonoUtils.firstPresent(
                Mono::empty,
                () -> Mono.just("a"),
                () -> { throw new IllegalStateException("Should not get here"); }
        );
        StepVerifier
                .create(mono)
                .expectNext("a")
                .verifyComplete();
    }
}