package net.microstar.spring.webflux.util;

import net.microstar.common.datastore.DataStore;
import net.microstar.common.util.ByteSize;
import net.microstar.spring.settings.DynamicPropertyRef;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

public final class FluxUtils {
    private FluxUtils() {}
            static       int bufferSize = 100 * 1024;
    private static final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();
    @SuppressWarnings("unused")
    private static final DynamicPropertyRef<ByteSize> bufferSizeRef = DynamicPropertyRef.of("app.config.defaultFluxBufferSize", ByteSize.class)
        .withDefault(ByteSize.ofKilobytes(100))
        .onChange(size -> bufferSize = size.getBytesInt())
        .callOnChangeHandlers();

    public static Flux<DataBuffer> fluxFrom(String s) {
        return DataBufferUtils.readInputStream(() -> new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), bufferFactory, bufferSize);
    }
    public static Flux<DataBuffer> fluxFrom(byte[] data) {
        return DataBufferUtils.readInputStream(() -> new ByteArrayInputStream(data), bufferFactory, bufferSize);
    }
    public static Flux<DataBuffer> fluxFrom(Path path) {
        return DataBufferUtils.read(path, bufferFactory, bufferSize);
    }
    public static Flux<DataBuffer> fluxFrom(InputStream in) {
        return DataBufferUtils.readInputStream(() -> in, bufferFactory, bufferSize);
    }

    public static Mono<Flux<DataBuffer>> fluxFromStore(DataStore store, String path) {
        return Mono.fromFuture(store.readStream(path))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .map(FluxUtils::fluxFrom);
    }

    public static Mono<byte[]> toBytes(@Nullable Flux<DataBuffer> flux) {
        return flux == null ? Mono.empty() : DataBufferUtils.join(flux)
            .map(dataBuffer -> {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                return bytes;
            });
    }
    public static Mono<String> toString(Flux<DataBuffer> flux) {
        return toBytes(flux).map(bytes -> new String(bytes, StandardCharsets.UTF_8));
    }
}
