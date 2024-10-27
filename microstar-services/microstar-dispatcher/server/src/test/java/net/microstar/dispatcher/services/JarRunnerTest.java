package net.microstar.dispatcher.services;

import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import net.microstar.common.datastore.MemoryDataStore;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.dispatcher.services.ServiceJarsManager.JarInfo;
import net.microstar.spring.webflux.settings.client.SettingsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static net.microstar.common.MicroStarConstants.UUID_ZERO;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.when;

class JarRunnerTest {
    final SettingsService settingsService = Mockito.mock(SettingsService.class);
    final MemoryDataStore store = new MemoryDataStore();

    @Test void shouldGetVmSettingsFromSettingsService() throws ExecutionException, InterruptedException {
        final ServiceId dummyServiceId = ServiceId.of("", "dummy", "1.0");
        final JarInfo dummyJarInfo = JarInfo.builder().name("dummy.jar").store(DynamicReferenceNotNull.of(store)).build();
        store.write("dummy.jar", "dummy").get();
        when(settingsService.getSettings(List.of("default"), UUID_ZERO, dummyServiceId))
            .thenReturn(Mono.just(Map.of("vmArgs", "-some -args", "foo", "bar")));

        callJarRunner(dummyServiceId, dummyJarInfo, Map.of("test", "123"), arguments -> {
            assertThat(arguments, is(List.of(
                "-Dtest=123",
                "-some",
                "-args",
                "-jar"
            )));
        });
    }

    @Test void shouldGetVmSettingsFromJar(@TempDir Path tempDir) throws IOException, ExecutionException, InterruptedException {
        final String jarName = "unit-test-1.0.jar";
        final ServiceId serviceId = ServiceId.of("", "unit-test", "1.0");
        final Path jarPath = tempDir.resolve(jarName);
        final URI jarUri = URI.create(jarPath.toUri().toString().replace("\\","/").replace("file://", "jar:file://"));
        final Map<String, String> env = Map.of("create", "true"); // Create the zip file if it doesn't exist
        final String configPath = "/BOOT-INF/classes/application.yaml";
        final String configText = "vmArgs: -a b=2 -c";

        try (FileSystem zipFs = FileSystems.newFileSystem(jarUri, env)) {
            Files.createDirectories(zipFs.getPath(configPath).getParent());
            Files.writeString(zipFs.getPath(configPath), configText);
        }
        final byte[] zipData = Files.readAllBytes(jarPath);
        store.write(jarName, zipData).get();

        when(settingsService.getSettings(List.of("default"), UUID_ZERO, serviceId))
            .thenReturn(Mono.just(Collections.emptyMap()));

        callJarRunner(serviceId, JarInfo.builder().name(jarName).store(DynamicReferenceNotNull.of(store)).build(), Map.of("test", "123"), arguments -> {
            assertThat(arguments, is(List.of(
                "-Dtest=123",
                "-a",
                "b=2",
                "-c",
                "-jar"
            )));
        });
    }

    private void callJarRunner(ServiceId serviceId, JarInfo jar, Map<String,String> cmdLineVars, Consumer<List<String>> checkArguments) {
        @RequiredArgsConstructor
        class Result {
            final ServiceId serviceId;
            final File jarFile;
            final List<String> arguments;
        }
        final AtomicReference<Result> resultRef = new AtomicReference<>();

        new JarRunner(settingsService) {
            @Override @SneakyThrows
            protected Process run(ProcessBuilder processBuilder, ServiceId serviceId, File jarFile, List<String> arguments) {
                synchronized (resultRef) {
                    resultRef.set(new Result(serviceId, jarFile, arguments));
                    resultRef.notifyAll();
                }
                return new Process() {
                    @Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
                    @Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
                    @Override public int waitFor() { return 0; }
                    @Override public int exitValue() { return 0; }
                    @Override public void destroy() {}
                };
            }
        }.run(serviceId, jar, cmdLineVars);

        // run is performed in a different thread
        synchronized(resultRef) {
            try {
                resultRef.wait(5_000);
            } catch (InterruptedException e) {
                fail("Test failed to complete");
            }
        }
        final Result result = resultRef.get();

        assertThat(result.serviceId, is(serviceId));
        assertThat(result.jarFile.getName(), is(jar.name));
        checkArguments.accept(result.arguments.stream().filter(s -> !s.replace("\\", "/").contains("/")).toList());
    }
}