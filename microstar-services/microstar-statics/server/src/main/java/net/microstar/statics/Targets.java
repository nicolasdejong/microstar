package net.microstar.statics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.util.ResourceUtils.isUrl;

@Slf4j
@Component
@RequiredArgsConstructor
public class Targets {
    private final WebClient.Builder webClientBuilder;
    private final AtomicReference<List<Target>> cachedTargets = new AtomicReference<>();
    private final DynamicPropertiesRef<StaticsProperties> props = DynamicPropertiesRef.of(StaticsProperties.class).onChange(() -> cachedTargets.set(null));

    public final class Target {
        public final String from;
        public final String to;
        public final Optional<WebClient> webClient; // when target is url

        Target(StaticsProperties.Target target) {
            this(target.from, target.to);
        }
        Target(String givenFrom, String givenTo) {
            from      = "/" + givenFrom.replaceFirst("^/+", "").replaceFirst("/+$", "");
            to        = givenTo;
            webClient = isUrl(to) ? Optional.of(buildClient()) : Optional.empty();
        }

        public  String      toString()                                  { return "(" + from + "->" + to  + ")"; }
        public  String      toPathFor(String requestPath)               { return IOUtils.concatPath(to, requestPath.substring(from.length())); }
        private WebClient   buildClient()                               {
            final DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory(to);
            factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
            return webClientBuilder
                .uriBuilderFactory(factory)
                .baseUrl(to)
                .build();
        }
    }

    public Target createTarget(String from, String to) {
        return new Target(from, to);
    }

    public Optional<Target> getTarget(String name) {
        return getTargets().stream()
            .filter(target -> nameStartsWithPath(name, target.from))
            .findFirst();
    }

    private List<Target> getTargets() {
        return Optional.ofNullable(cachedTargets.get())
            .orElseGet(() -> {
                cachedTargets.set(
                    props.get().targets.stream()
                        .map(Target::new)
                        .sorted(Comparator.comparing(t -> -t.from.split("/").length)) // most segments first
                        .toList()
                );
                return cachedTargets.get();
            });
    }

    private static boolean nameStartsWithPath(String nameIn, String pathIn) {
        final String name = nameIn.replaceFirst("^/+", "");
        final String path = pathIn.replaceFirst("^/+", "");
        return path.isEmpty() ||
            (name.startsWith(path) && (name.length() == path.length() || name.startsWith("/", path.length())));
    }
}
