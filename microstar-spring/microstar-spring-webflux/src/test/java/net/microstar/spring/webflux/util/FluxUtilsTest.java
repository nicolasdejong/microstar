package net.microstar.spring.webflux.util;

import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.MemoryDataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class FluxUtilsTest {
    private static final String someText = "The quick brown fox jumps over the lazy dog";

    @AfterAll static void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void bufferSizeShouldBeSetFromConfiguration() {
        DynamicPropertiesManager.setProperty("app.config.defaultFluxBufferSize", "25KB");
        assertThat(FluxUtils.bufferSize, is(25 * 1024));
    }

    @Test void testFromToString() {
        final String text = someText.repeat(10  * 1024);
        final Flux<DataBuffer> flux = FluxUtils.fluxFrom(text);
        final Mono<String> result = FluxUtils.toString(flux);

        StepVerifier
                .create(result)
                .expectNext(text)
                .verifyComplete();
    }
    @Test void testFromToBytes() {
        final byte[] data = new byte[201 * 1024];
        for(int i=0; i<data.length; i++) data[i] = (byte)i;
        final Flux<DataBuffer> flux = FluxUtils.fluxFrom(data);
        final Mono<byte[]> result = FluxUtils.toBytes(flux);

        StepVerifier
                .create(result)
                .expectNextMatches(bytes -> equals(bytes, data))
                .verifyComplete();
    }
    @Test void testFromPath() throws IOException {
        final String text = someText.repeat(10 * 1024);
        final Path testFile = Files.createTempFile("FlexUtils", "test");
        IOUtils.writeString(testFile, text);
        final Flux<DataBuffer> flux = FluxUtils.fluxFrom(testFile);
        final Mono<String> result = FluxUtils.toString(flux);

        StepVerifier
                .create(result)
                .expectNext(text)
                .verifyComplete();
    }
    @Test void testFromInputStream() {
        final String text = someText.repeat(10 * 1024);
        final InputStream stream = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        final Flux<DataBuffer> flux = FluxUtils.fluxFrom(stream);
        final Mono<String> result = FluxUtils.toString(flux);

        StepVerifier
            .create(result)
            .expectNext(text)
            .verifyComplete();
    }
    @Test void testFromDataStore() throws ExecutionException, InterruptedException {
        final String text = someText.repeat(10 * 1024);
        final DataStore store = new MemoryDataStore();
        store.write("file.text", text).get();
        final Flux<DataBuffer> flux = FluxUtils.fluxFromStore(store, "file.text").block();
        final Mono<String> result = FluxUtils.toString(flux);

        StepVerifier
            .create(result)
            .expectNext(text)
            .verifyComplete();
    }

    private static boolean equals(byte[] a, byte[] b) {
        if(a.length != b.length) return false;
        for(int i=0; i<a.length; i++) if(a[i] != b[i]) return false;
        return true;
    }
}