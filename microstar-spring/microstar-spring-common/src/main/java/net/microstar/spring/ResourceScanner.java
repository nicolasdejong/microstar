package net.microstar.spring;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.throwingfunctionals.ThrowingConsumer;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/** Get resources from classpath. Thread safe.
  * Based on: <a href="https://stackoverflow.com/questions/49559041/java-how-to-list-directories-in-resources-in-jar">stack overflow article</a>
  */
@Slf4j
public class ResourceScanner {
    // This resolver is thread safe and can be shared
    // https://github.com/spring-projects/spring-framework/issues/8312
    // (the question of course then is: if it is thread-safe, why can it be constructed as only one instance is required)
    private static final PathMatchingResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();

    public static Resource   getResource(String path) {
        return resourcePatternResolver.getResource(normalize(path));
    }
    public static Resource[] getResources(String path) {
        return getResources(path, false);
    }
    public static Resource[] getResources(String path, boolean recursive) {
        return noThrow(() -> resourcePatternResolver.getResources(IOUtils.concatPath(normalize(path), recursive ? "**" : "*"))).orElse(new Resource[0]);
    }
    public static String[]   getResourceNames(String path) {
        return getResourceNames(path, false);
    }
    public static String[]   getResourceNames(String path, boolean recursive) {
        return Optional.of(getResources(path, recursive))
            .map(recs -> mapToNames(path, recs))
            .orElseThrow();
    }

    public static boolean copyResources(String resourcePath, Path targetPath) { return copyResources(resourcePath, targetPath, false); }
    public static boolean copyResources(String resourcePath, Path targetPath, boolean recursive) { return copyResources(resourcePath, targetPath, recursive, p -> {}); }
    public static boolean copyResources(String resourcePath, Path targetPath, ThrowingConsumer<Path> forEachTarget) { return copyResources(resourcePath, targetPath, false, forEachTarget); }
    public static boolean copyResources(String resourcePath, Path targetPath, boolean recursive, ThrowingConsumer<Path> forEachTarget) {
        try {
            for (Resource resource : getResources(resourcePath, recursive)) {
                final String name = mapToName(resourcePath, resource);
                if(resource.isReadable()) {
                    try(final InputStream resourceIn = resource.getInputStream()) {
                        final Path copyTarget = targetPath.resolve(name);
                        IOUtils.makeSureDirectoryExists(copyTarget.getParent());
                        Files.copy(resourceIn, copyTarget);
                        forEachTarget.accept(copyTarget);
                    }
                } else {
                    IOUtils.makeSureDirectoryExists(targetPath.resolve(name));
                }
            }
            return true;
        } catch(final IOException failed) {
            log.error("Failed to copy resources from {} to {}", resourcePath, targetPath, failed);
            return false;
        }
    }

    private static String normalize(String path) {
        final String result = path.replace("\\", "/").replace("*", "");
        return result.replace("/","").isEmpty() ? "@@@Nothing" : result;
    }
    private static String[] mapToNames(String relativeTo, Resource[] resources) {
        final String[] result = new String[resources.length];
        for(int i=0; i<result.length; i++) result[i] = mapToName(relativeTo, resources[i]);
        return result;
    }
    private static String mapToName(String relativeTo, Resource resource) {
        final Resource root = getResource(relativeTo);
        final @Nullable String rootUri = noThrow(() ->
            URLDecoder.decode(root.getURI().toString().endsWith("/") ? root.getURI().toString() : root.getURI() + "/", StandardCharsets.UTF_8)
        ).orElse(null);
        if(rootUri == null) return "";
        return noThrow(() -> resource.getURI().toString().substring(rootUri.length())).orElse("");
    }
}