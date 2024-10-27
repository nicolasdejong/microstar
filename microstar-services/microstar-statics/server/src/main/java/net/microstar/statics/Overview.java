package net.microstar.statics;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.spring.DataStores;
import net.microstar.statics.model.OverviewItem;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.zip.CRC32;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Utils.peek;
import static net.microstar.statics.DataService.DATASTORE_NAME;

@Slf4j
public final class Overview {
    private Overview() {}
    private static final DynamicReferenceNotNull<DataStore> filesRoot = DataStores.get(DATASTORE_NAME);
    private static final Map<String,Long> crcCache = new ConcurrentHashMap<>();

    public static List<OverviewItem> list(boolean includeCrc) {
        return peek(noThrow(() -> filesRoot.get()
            .list("", true).get().stream()
            .map(item -> {
                final long lastModified = item.time.getEpochSecond();
                final String key = lastModified + ":" + item.path;
                return OverviewItem.builder()
                        .path(item.path)
                        .length(item.size)
                        .lastModified(lastModified)
                        .crc(includeCrc ? crcCache.computeIfAbsent(key, s -> crcOf(item.path)) : 0)
                        .build();
                }
            ).toList()).orElseGet(Collections::emptyList), result -> log.info("Returning overview list of size {}", result.size()));
    }

    private static long crcOf(String path) {
        final Path tempFile = IOUtils.createAndDeleteTempFile();
        try {
            DataStoreUtils.copy(filesRoot.get(), path, tempFile).get();
            return crcOf(tempFile);
        } catch (ExecutionException e) {
            return -1;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return -1;
        } finally {
            IOUtils.del(tempFile);
        }
    }
    private static long crcOf(Path path) {
        final CRC32 crc = new CRC32();
        final ByteBuffer buffer = ByteBuffer.allocate(1024);

        try(SeekableByteChannel input = Files.newByteChannel(path, StandardOpenOption.READ)) {
            int len;
            while((len = input.read(buffer)) > 0) {
                buffer.flip();
                crc.update(buffer.array(), 0, len);
            }
        } catch(final IOException e) {
            return -1;
        }

        // While returning a 64-bits value, CRC32 is only 32 bits long.
        // Might as well add the file length in the upper empty 32 bits.
        return crc.getValue() | (path.toFile().length() << 32);
    }
}
