package net.microstar.statics;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.Threads;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.spring.DataStores;
import net.microstar.spring.WildcardParam;
import net.microstar.spring.settings.DynamicPropertiesRef;
import net.microstar.spring.webflux.EventEmitter;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static net.microstar.common.util.ThreadUtils.debounce;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataService {
    public static final String DATASTORE_NAME = "resources";
    private static final Duration EMIT_DEBOUNCE = Duration.ofSeconds(3);
    private final DynamicReferenceNotNull<DataStore> filesRoot = DataStores.get(DATASTORE_NAME);
    private final DynamicPropertiesRef<StaticsProperties> props = DynamicPropertiesRef.of(StaticsProperties.class);
    private final EventEmitter eventEmitter;
    private final DispatcherService dispatcher;

    public void emitChanged() { debounce(EMIT_DEBOUNCE, () -> eventEmitter.next("STATIC-DATA-CHANGED")); }

    public CompletableFuture<List<String>> getDirectoryContents(@WildcardParam String name) {
        return filesRoot.get().listNames(name);
    }

    public CompletableFuture<Void> createDirectory(String name) {
        // Directories cannot be created in DataStores. Only files.
        return filesRoot.get().write(IOUtils.concatPath(name, "placeholder"),
            "Placeholder file because empty directories are not supported in DataStores."
        ).thenRun(this::emitChanged);
    }

    public CompletableFuture<Void> rename(String from, String to) {
        return filesRoot.get().move(from, to).thenRun(this::emitChanged);
    }

    public CompletableFuture<Void> delete(String pathToDelete) {
        return filesRoot.get().remove(pathToDelete).thenRun(this::emitChanged);
    }

    public Mono<Void> handleUpload(String targetDir, Mono<MultiValueMap<String, Part>> partsMap) {
        final Path tempDir = IOUtils.createTempDir();
        return partsMap
            .flatMapIterable(map -> map.toSingleValueMap().values())
            .filter(FilePart.class::isInstance)
            .map(FilePart.class::cast)
            .flatMap(file -> {
                final Path targetFile = tempDir.resolve(file.filename());
                return file.transferTo(targetFile)
                    .then(Mono.just(targetFile));
            })
            .doOnComplete(() -> handleUploadedFiles(tempDir, targetDir))
            .then();
    }

    private void handleUploadedFiles(Path tempDir, String targetDir) {
        Threads.execute(() -> {
            // unzip
            IOUtils.list(tempDir).stream()
                .peek(path -> log.info("Added file: " + tempDir.relativize(path)))
                .filter(path -> path.getFileName().toString().endsWith(".zip"))
                .forEach(zipPath -> {
                    log.info("Unzipping uploaded file '{}'", zipPath.getFileName());
                    unzip(zipPath);
                });

            // copy files to datastore
            log.info("Storing uploaded files in {}", targetDir);
            final CompletableFuture<Boolean> copyToStore = DataStoreUtils.copy(tempDir, filesRoot.get(), targetDir);

            copyToStore
                .thenRun(() -> {
                    syncBetweenStars(tempDir, targetDir);

                    IOUtils.delTree(tempDir);
                    emitChanged();
                });
        });
    }
    private void unzip(Path zipPath) {
        try(final ZipFile zipFile = new ZipFile(zipPath.toFile())) {
            zipFile.extractAll(zipPath.getParent().toAbsolutePath().toString());
            Files.delete(zipPath);
        } catch(final Exception ex) {
            log.warn("Failed to unzip {}", zipPath.getFileName(), ex);
        }
    }
    private void syncBetweenStars(Path tempDir, String targetDir) {
        if(props.get().syncBetweenStars) {
            final List<Path> uploadedFiles = IOUtils.list(tempDir);
            log.info("Tell other stars to download the files: {}", uploadedFiles.stream().map(tempDir::relativize).toList());
            uploadedFiles.forEach(name -> dispatcher.relay(RelayRequest
                .forThisService()
                .servicePath(IOUtils.concatPath("file-change", targetDir, tempDir.relativize(name)))
                .build(), Void.class
            ).then().block());
        }
    }
}
