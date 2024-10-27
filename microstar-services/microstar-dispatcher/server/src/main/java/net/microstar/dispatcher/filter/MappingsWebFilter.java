package net.microstar.dispatcher.filter;

import com.google.common.collect.ImmutableList;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.util.GeneratedReference;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.dispatcher.model.DispatcherProperties;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

@Slf4j
@Component
@Order(3)
public class MappingsWebFilter implements WebFilter {
    public static final String REMAP_PROXY_KEY = "X-PROXY-TO";
    public static final String PATH_MAPPED_KEY = "X-PATH-MAPPED-FROM";

    @SuppressWarnings("Convert2MethodRef")
    private final DynamicPropertiesRef<DispatcherProperties> propsRef = DynamicPropertiesRef
        .of(DispatcherProperties.class)
        .onChange(() -> this.mappingsRef.reset()); // NOSONAR cannot change lambda to method ref
    private final GeneratedReference<ImmutableList<Mapping>> mappingsRef = new GeneratedReference<>(() ->
        propsRef.get().mappings.entrySet().stream()
            .map(Mapping::of)
            .collect(ImmutableUtil.toImmutableList())
    );

    @Builder @ToString @EqualsAndHashCode
    private static class Mapping {
        public final String rawPattern;
        public final Pattern pattern;
        public final Pattern toReplace;
        public final String replacement;

        public static Mapping of(Map.Entry<String,String> entry) {
            return Mapping.builder()
                .rawPattern(entry.getKey().replaceFirst("^(>|)?/?", "$1/"))
                .pattern(Pattern.compile(entry.getKey()
                    .replaceFirst("^/?", "/")         // add a slash in front of a pattern
                    .replaceFirst("/*$", "(/.*)?\\$") // pattern should not match until slash or end
                    ))
                .toReplace(Pattern.compile(entry.getKey()))
                .replacement(entry.getValue())
                .build();
        }
    }

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain nextInChain) {
        final List<Mapping> mappings = mappingsRef.get();
        return mappings.isEmpty()
            ? nextInChain.filter(exchange)
            : filter(mappings, exchange, nextInChain);
    }

    private static Mono<Void> filter(List<Mapping> mappings, ServerWebExchange exchange, WebFilterChain nextInChain) {
        final ServerHttpRequest req = exchange.getRequest();
        final String oldPath = req.getPath().value();
        final Supplier<String> pathAndQuerySupplier = new CachingSupplier<>(() -> oldPath + Optional.ofNullable(req.getURI().getRawQuery()).map(rq -> "?" + rq).orElse(""));
        String newPath = oldPath;
        @Nullable Set<Mapping> matched = null;
        @Nullable Mapping matchedMapping;
        @Nullable String replacedPath = null;

        // Find a mapping for this path.
        // If found, try again to find a mappings for the new path, to support recursive mappings.
        // Limit the recursion depth to mapping count (which is less expensive than keeping a set
        // of matched paths as the 99% use case is no recursive mappings).
        do {
            matchedMapping = null;
            for (final Mapping mapping : mappings) {
                if (matched != null && matched.contains(mapping)) continue;
                if (mapping.rawPattern.startsWith(">") && mapping.rawPattern.substring(1).equals(pathAndQuerySupplier.get())) {
                    matchedMapping = mapping;
                    replacedPath = mapping.replacement;
                }
                if (mapping.pattern.matcher(newPath).matches()) {
                    matchedMapping = mapping;
                    replacedPath = mapping
                        .toReplace.matcher(newPath)
                        .replaceFirst(mapping.replacement)
                        .replaceFirst("^/+", "/")
                        .replaceFirst(mapping.replacement.startsWith("http") ? "^/" : "^", "")
                        ;
                }
            }
            if(matchedMapping != null) {
                newPath = replacedPath;
                if(matched == null) matched = new HashSet<>();
                matched.add(matchedMapping);
            }
        } while(matchedMapping != null);


        final boolean isAbs = newPath.startsWith("http");
        final String newPathFull = isAbs ? Optional.of(newPath.replaceFirst("^/+","")).map(p->p.startsWith("http") ? p : ("http://" + p)).orElse("") : newPath;
        final boolean hasProxyMappingToRemove = req.getHeaders().containsKey(REMAP_PROXY_KEY); // prevent outside setting of redirect

        //noinspection StringEquality
        return hasProxyMappingToRemove || newPath != oldPath // NOSONAR -- no equals needed here
            ? nextInChain.filter(exchange.mutate().request(exchange.getRequest().mutate()
                .path(isAbs ? oldPath : newPath)
                .headers(headers -> {
                    headers.add(PATH_MAPPED_KEY, oldPath);
                    if(isAbs) headers.add(REMAP_PROXY_KEY, newPathFull);
                    else      headers.remove(REMAP_PROXY_KEY); // remove, ignoring bad intend so no attention is given to this check
                })
                .build()).build())
            : nextInChain.filter(exchange);
    }

    private static class CachingSupplier<T> implements Supplier<T> {
        private final Supplier<T> supplier;
        private final AtomicReference<T> value = new AtomicReference<>();

        public CachingSupplier(Supplier<T> supplier) { this.supplier = supplier; }

        @Override
        public T get() {
            T result = value.get();
            if(result == null) synchronized(value) {
                if((result = value.get()) == null) value.set(result = supplier.get());
            }
            return result;
        }
    }
}
