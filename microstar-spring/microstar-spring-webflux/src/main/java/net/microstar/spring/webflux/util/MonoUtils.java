package net.microstar.spring.webflux.util;

import reactor.core.publisher.Mono;

import java.util.function.Supplier;

public final class MonoUtils {
    private MonoUtils() { /*singleton*/ }

    /** Alternative to mono.switchIfEmpty(Mono.defer(supplier1)).switchIfEmpty(Mono.defer(supplier2)).switchIfEmpty(... etc */
    @SafeVarargs
    public static <T> Mono<T> firstPresent(Supplier<Mono<T>>... suppliers) {
        Mono<T> result = Mono.empty();
        for(final Supplier<Mono<T>> supplier : suppliers) {
            result = result.switchIfEmpty(Mono.defer(supplier));
        }
        return result;
    }
}
