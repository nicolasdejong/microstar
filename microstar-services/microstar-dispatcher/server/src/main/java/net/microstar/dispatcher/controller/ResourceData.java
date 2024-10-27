package net.microstar.dispatcher.controller;

import com.google.common.collect.ImmutableMap;
import net.microstar.common.util.ImmutableUtil;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.ExceptionUtils.noThrow;

@Component
public class ResourceData {
    private static final ImmutableMap<String, Resource> knownResources = getClasspathResources();
    private static final String PUBLIC_ROOT = "/public";

    public Optional<Resource> getResource(String name) {
        // using map which is fast: all requests touch this
        @Nullable Resource resource = knownResources.get(name);
        if(resource != null) return Optional.of(resource);

        resource = knownResources.get(name + "/");
        return resource == null
            ? Optional.empty()
            : Optional.of(resource);
    }

    private static ImmutableMap<String,Resource> getClasspathResources() {
        final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(ProxyController.class.getClassLoader());
        final Map<String,Optional<Resource>> resourceMap = new LinkedHashMap<>();
        Arrays.stream(noThrow(() -> resolver.getResources(concatPath(PUBLIC_ROOT, "/**"))).orElse(new Resource[0]))
            .forEach(resource -> {
                final String name = resourceNameOf(resource);
                final Optional<Resource> perhapsResource = Optional.of(resource).filter(r -> !name.endsWith("/"));
                resourceMap.put(name, perhapsResource);
            });
        // Resources that represent directories should not be returned unless the directory contains a /index.html
        Set.copyOf(resourceMap.keySet()).forEach(key -> {
           if(resourceMap.get(key).isEmpty()) {
               final String indexKey = concatPath(key, "index.html");
               if(resourceMap.getOrDefault(indexKey, Optional.empty()).isPresent()) {
                   resourceMap.put(key, resourceMap.get(indexKey));
               } else {
                   resourceMap.remove(key);
               }
           }
        });

        return resourceMap.entrySet().stream()
            .filter(entry -> entry.getValue().isPresent())
            .collect(ImmutableUtil.toImmutableMap(Map.Entry::getKey, e -> e.getValue().orElseThrow()));
    }
    static String resourceNameOf(Resource resource) {
        return noThrow(resource::getURI)
            .map(Object::toString)
            .map(s -> s.replace("\\","/").replaceFirst("^.*" + PUBLIC_ROOT + "/", "/")) // don't create /public/ under /public/
            .orElse("");
    }
}