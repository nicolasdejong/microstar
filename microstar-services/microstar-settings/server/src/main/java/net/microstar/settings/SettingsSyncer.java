package net.microstar.settings;

import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.spring.DataStores;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.common.util.ExceptionUtils.noThrow;

@Slf4j
@RequiredArgsConstructor
public class SettingsSyncer {
    private final SettingsRepository repo;
    private final DispatcherService dispatcher;

    @ToString @EqualsAndHashCode(exclude = "starName")
    private static class FileAndVersion implements Comparable<FileAndVersion> {
        public final String filename;
        public final String version;
        public final int versionNumber;
        public final String starName;

        public FileAndVersion(String filenameAndVersion, String starName) {
            final String[] favParts = filenameAndVersion.split("/(?=[^/]+$)", 2);
            filename = favParts[0];
            version = favParts[1];
            versionNumber = noThrow(() -> Integer.parseInt(version.split("-",2)[0])).orElse(1);
            this.starName = starName;
        }

        public String toFullName() {
            return filename + "/" + version;
        }
        public RelayRequest toDownloadRequest() {
            return RelayRequest.forGet("microstar-settings")
                .servicePath("file/" + toFullName())
                .star(starName)
                .build();
        }

        @Override
        public int compareTo(FileAndVersion other) {
            // starName should not be in the comparison
            final int nameDelta = filename.compareTo(other.filename);
            if(nameDelta != 0) return nameDelta;
            return versionNumber - other.versionNumber;
        }
    }

    public Mono<Set<String>> syncWithSettingsFromOtherStars() {
        log.info("SYNC with other stars");
        return getNewerHistoryNamesFromOtherStars(dispatcher)
            .flatMap(version -> downloadNewVersion(dispatcher, version, repo.getStore()))
            .collectList()
            .map(list -> (Set<String>)new HashSet<>(list))
            .doOnNext(this::updateFilesForNewVersions);
    }

    private static Flux<FileAndVersion> getNewerHistoryNamesFromOtherStars(DispatcherService dispatcher) {
        final RelayRequest request = RelayRequest.forGet("microstar-settings").servicePath("all-history-names").build();
        final ParameterizedTypeReference<List<String>> resultType = new ParameterizedTypeReference<>() {};

        return dispatcher.getLocalStarName()
            .flatMapMany(localStarName -> dispatcher.relay(request, resultType)
                // Create map holding all versions from all stars, local star preferred
                .reduce(new HashMap<String, SortedSet<FileAndVersion>>(), (all, resp) -> {
                    final boolean isFromLocalStar = resp.starName.equals(localStarName);
                    final List<String> starHistory = resp.content.orElse(Collections.emptyList());

                    if(resp.status.isError()) log.warn("Failed to call settings service on {} for allHistoryNames: {}", resp.starName, resp.status);

                    starHistory.forEach(nameAndVersion -> {
                        final FileAndVersion version = new FileAndVersion(nameAndVersion, resp.starName);
                        if(!all.containsKey(version.filename)) all.put(version.filename, new TreeSet<>());
                        final SortedSet<FileAndVersion> versions = all.get(version.filename);

                        if(!versions.contains(version) || isFromLocalStar) {
                            versions.remove(version); // remove + add == overwrite (add only adds if not yet contained)
                            versions.add(version); // overwrite in case of isFromLocalStar
                        }
                    });
                    return all;
                })
                // Remove all local versions to keep the versions that need to be downloaded
                .flatMapMany(allVersions -> Flux.fromStream(allVersions.entrySet().stream()
                    .map(entry -> Map.entry(entry.getKey(), entry.getValue().stream().filter(fav -> !fav.starName.equals(localStarName)).toList()))
                    .filter(entry -> !entry.getValue().isEmpty())
                    .flatMap(entry -> entry.getValue().stream()
                )))
            );
    }
    private static Mono<String> downloadNewVersion(DispatcherService dispatcher, FileAndVersion fileAndVersion, BlockingDataStore store) {
        final FileHistory currentHistory = new FileHistory(concatPath(SettingsRepository.HISTORY_DIR_NAME, fileAndVersion.filename));
        return dispatcher.relaySingle(fileAndVersion.toDownloadRequest(), String.class)
            .doOnError(ex -> log.warn("SYNC: History file request failed: {}", ex.getMessage()))
            .mapNotNull(resp -> {
                if(!resp.status.is2xxSuccessful()) {
                    log.warn("SYNC: download failed of {}: {}", fileAndVersion.filename, resp.status);
                    return null;
                }
                return resp.content.map(fileData -> {
                    final String historyPath = concatPath(SettingsRepository.HISTORY_DIR_NAME, fileAndVersion.filename, fileAndVersion.version);
                    try {
                        store.write(historyPath, fileData);
                        if(currentHistory.getLatestVersionNumber() < fileAndVersion.versionNumber) {
                            final String currentPath = concatPath(SettingsRepository.CURRENT_DIR_NAME, fileAndVersion.filename);
                            DataStoreUtils.copy(store, historyPath, store, currentPath);
                        }
                    } catch(final RuntimeException ex) {
                        log.warn("SYNC: Unable to write file: {} -- {}: {}", fileAndVersion.filename, ex.getClass().getSimpleName(), ex.getMessage());
                    }
                    return fileAndVersion.filename;
                }).orElse(null);
            });
    }
    private void updateFilesForNewVersions(Set<String> updatedNames) {
        if(updatedNames.isEmpty()) {
            log.info("SYNC: No changes");
        } else {
            log.info("SYNC: {} settings files were updated: {}", updatedNames.size(), updatedNames.stream().sorted().toList());
            updatedNames.forEach(repo::handleDataStoreChange);
            repo.updateAllServicesForChangedContent();
        }
    }
}
