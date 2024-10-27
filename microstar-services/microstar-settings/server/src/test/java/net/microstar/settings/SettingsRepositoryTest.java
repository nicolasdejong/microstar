package net.microstar.settings;

import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.util.ImmutableUtil;
import net.microstar.dispatcher.model.RelayRequest;
import net.microstar.dispatcher.model.RelayResponse;
import net.microstar.spring.DataStores;
import net.microstar.spring.application.MicroStarApplication;
import net.microstar.spring.exceptions.FatalException;
import net.microstar.spring.exceptions.NotFoundException;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import net.microstar.spring.webflux.MiniBus;
import net.microstar.spring.webflux.dispatcher.client.DispatcherService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.ParameterizedTypeReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

import static net.microstar.common.io.IOUtils.concatPath;
import static net.microstar.settings.FileHistory.ActionType.DELETED;
import static net.microstar.settings.FileHistory.ActionType.RESTORED;
import static net.microstar.settings.SettingsRepository.CURRENT_DIR_NAME;
import static net.microstar.settings.SettingsRepository.HISTORY_DIR_NAME;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Slf4j
class SettingsRepositoryTest {
    SettingsRepository repo;
    private static final Instant NOW_TIME = Instant.now();
    private static final String NOW_TEXT = FileHistory.FileVersion.toString(NOW_TIME);
    private DispatcherService dispatcher;
    private MicroStarApplication application;
    private MiniBus miniBus;

    @BeforeEach void setup() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(
            Map.of("microstar.dataStores.settings", Map.of("type", "memory"))
        ));
        dispatcher = Mockito.mock(DispatcherService.class);
        application = Mockito.mock(MicroStarApplication.class);
        miniBus = Mockito.mock(MiniBus.class);
        when(dispatcher.getServiceInstanceIds()).thenReturn(ImmutableUtil.emptyMap());
        when(dispatcher.getLocalStarName()).thenReturn(Mono.just("local"));
        when(dispatcher.relay(Mockito.any(RelayRequest.class),Mockito.<Class<?>>any())).thenReturn(Flux.empty());
        repo = new SettingsRepository(dispatcher, application, miniBus) {
            @Override public Instant now() { return NOW_TIME; }
            @Override Flux<RelayResponse<Set<String>>> tellAllOtherSettingsServicesToSync() {
                return Flux.empty();
            }
        };
    }
    @AfterEach void cleanup() {
        repo.close();
        DataStores.closeAll();
        DynamicPropertiesManager.clearAllState();
    }

    @Test void aListingShouldBeAvailable() {
        final String fileContents = "foobar";
        final List<String> filenames = Stream.of(
            "fileA", "fileB", "fileD", "fileC",
            "dirA/fileAA", "dirA/fileAB", "dirA/fileAC",
            "dirB/fileBA", "dirB/dirBA/fileBAA", "dirB/dirBA/fileBAB", "dirB/dirBA/fileBAC",
            "deletedFileA", "dirB/deletedFileB"
        ).sorted().toList();

        // Store all files
        filenames.forEach(filename -> repo.store(filename, fileContents, "username", ""));

        // Delete files that have 'delete' in the name
        filenames.stream()
            .filter(filename -> filename.contains("deleted"))
            .forEach(filename -> repo.delete(filename, "username"));

        // Returned filenames should contain all names, including the deleted files
        assertThat(
            repo.getAllFilenames().stream().map(fn -> fn.name + (fn.isDeleted?":deleted":"")).toList(),
            is(filenames.stream().map(fn -> fn + (fn.contains("deleted") ? ":deleted" : "")).toList())
        );
    }

    @Test void itShouldBePossibleToGetContents() throws ExecutionException, InterruptedException {
        write("test.txt", "testing");
        assertThat(repo.getContentsOf("/test.txt"), is("testing"));

        write("/a/b/test.txt", "testing deeper");
        assertThat(repo.getContentsOf("/a/b/test.txt"), is("testing deeper"));
    }
    @Test void itShouldBePossibleToGetHistoryContents() throws ExecutionException, InterruptedException {
        final DataStore store = DataStores.get("settings").get();
        final String topFile = "test.txt";
        final String deeperFile = "a/b/test.txt";
        final String topContents = "testing";
        final String deeperContents = "testing deeper";

        store.write(concatPath(CURRENT_DIR_NAME, topFile), topContents).get();
        store.write(concatPath(CURRENT_DIR_NAME, deeperFile), deeperContents).get();

        final List<String> historyItems = repo.getAllHistoryNames();
        for (final String historyName : historyItems) {
            String contents = repo.getContentsOf(historyName);

            if (historyName.startsWith(topFile + "/")) assertThat(contents, is(topContents));
            else
            if (historyName.startsWith(deeperFile + "/")) assertThat(contents, is(deeperContents));
            else
                fail("Unexpected history name: " + historyName);
        }

        assertThat(repo.getContentsOf(topFile), is(topContents));
        assertThat(repo.getContentsOf(deeperFile), is(deeperContents));
    }
    @Test void itShouldNotBePossibleToGetContentsAboveCurrentDir() {
        final DataStore store = DataStores.get("settings").get();
        store.write("test.txt", "testing");
        assertThrows(NotFoundException.class, () -> repo.getContentsOf("../test.txt"));
    }

    @Test void filesystemChangesShouldUpdateHistory() throws ExecutionException, InterruptedException {
        final DataStore store = DataStores.get("settings").get();
        assertThat(store.list(CURRENT_DIR_NAME).get().isEmpty(), is(true));
        assertThat(store.list(HISTORY_DIR_NAME).get().isEmpty(), is(true));

        final String filename = "test.txt";
        final String fileContents1 = "foo bar test text";
        store.write(concatPath(CURRENT_DIR_NAME, filename), fileContents1).get();

        repo.handleDataStoreChange(filename);

        final List<String> historyDirs = store.listNames(HISTORY_DIR_NAME).get();
        assertThat(historyDirs.size(), is(1));
        assertThat(historyDirs.get(0), is(filename + "/")); // added slash because history is dir
        final List<String> historyFiles = store.listNames(concatPath(HISTORY_DIR_NAME, filename)).get();
        assertThat(historyFiles.size(), is(2)); // state + latest version
        assertThat(store.isDir(historyFiles.get(0)), is(false));
        assertThat(store.isDir(historyFiles.get(1)), is(false));
        final String historyFile = historyFiles.stream()
            .filter(file -> !file.endsWith("state"))
            .findFirst()
            .orElseThrow();
        assertThat(store.readString(concatPath(HISTORY_DIR_NAME, filename, historyFile)).get().orElse(""), is(fileContents1));
    }

    @Test void storeShouldUpdateContents() {
        final String filename = "test.txt";
        final String fileContents1 = "foo bar test text";
        final String fileContents2 = "second contents";
        repo.store(filename, fileContents1, "username", "message abc");

        assertThat(repo.getContentsOf(filename), is(fileContents1));
        assertThrows(NotFoundException.class, () -> repo.getContentsOf(filename + "-non-existing"));

        repo.store(filename, fileContents2, "username", "message abc");

        assertThat(repo.getContentsOf(filename), is(fileContents2));
    }
    @Test void storeShouldUpdateHistory() {
        final String filename = "test.txt";
        final String fileContents1 = "foo bar test text";
        final String fileContents2 = "second contents";

        repo.store(filename, fileContents1, "username", "message abc");

        assertThat(repo.getVersions(filename).size(), is(1));
        assertThat(repo.getContentsOf(filename, 1), is(fileContents1));
        assertThrows(NotFoundException.class, () -> repo.getContentsOf(filename, 2));

        repo.store(filename, fileContents2, "username", "message abc");
        assertThat(repo.getVersions(filename).size(), is(2));
        assertThat(repo.getContentsOf(filename, 1), is(fileContents1));
        assertThat(repo.getContentsOf(filename, 2), is(fileContents2));
        assertThrows(NotFoundException.class, () -> repo.getContentsOf(filename, 3));
    }
    @Test void deleteShouldRemoveFile() {
        final String filename = "test.txt";
        final String fileContents1 = "foo bar test text";
        final String fileContents2 = "second contents";

        repo.store(filename, fileContents1, "username", "message abc");
        repo.store(filename, fileContents2, "username", "message 2");

        assertThat(repo.getVersions(filename).size(), is(2));
        assertThat(repo.getContentsOf(filename, 2), is(fileContents2));

        repo.delete(filename, "username");

        assertThat(repo.getVersions(filename).size(), is(3));
        assertThrows(NotFoundException.class, () -> repo.getContentsOf(filename));
        assertThat(repo.getContentsOf(filename, 2), is(fileContents2));
        assertThat(repo.getLatestVersion(filename).actionType, is(DELETED));
    }
    @Test void restoreShouldUndeleteDeletedFile() {
        final String username = "username";
        final String filename = "test.txt";
        final String fileContents1 = "foo bar test text";
        final String fileContents2 = "second contents";

        repo.store(filename, fileContents1, username, "message abc");       // version 1: text
        repo.store(filename, fileContents2, username, "message 2");         // version 2: text

        assertThat(repo.getVersions(filename).size(), is(2));
        assertThat(repo.getContentsOf(filename, 2), is(fileContents2));

        repo.delete(filename, username);                                    // version 3: deleted
        assertThat(repo.getVersions(filename).size(), is(3));
        assertThat(repo.getLatestVersion(filename).actionType, is(DELETED));

        repo.restore(filename, username, Optional.empty());
        assertThat(repo.getVersions(filename).size(), is(4));                 // version 4: restored
        assertThat(repo.getLatestVersion(filename).actionType, is(RESTORED));
        assertThat(repo.getContentsOf(filename), is(fileContents2));

        final Optional<Integer> v3 = Optional.of(3); // Sonar doesn't want this in the next line
        assertThrows(FatalException.class, () -> repo.restore(filename, username, v3)); // cannot restore to deleted version

        repo.restore(filename, username, Optional.of(1));
        assertThat(repo.getVersions(filename).size(), is(5));
        assertThat(repo.getLatestVersion(filename).actionType, is(RESTORED));
        assertThat(repo.getContentsOf(filename), is(fileContents1));
    }

    @Test void getHistoryNamesShouldProvideAllHistory() {
        final List<String> filenames = List.of("fileA", "fileB", "fileC", "fileD");

        filenames.forEach(filename -> repo.store(filename, "1", "username", ""));
        repo.store("fileA", "2", "username", "");

        assertThat(repo.getAllHistoryNames(), is(replaceVars(List.of(
            "fileA/1-${changed}",
            "fileA/2-${changed}",
            "fileB/1-${changed}",
            "fileC/1-${changed}",
            "fileD/1-${changed}"
        ))));
    }
    @Test void syncSettingsShouldUpdateFiles() {
        final List<String> historyStarLocal  = createHistoryList("fileA/1", "fileA/2",            "fileB/1",                   "fileC/1", "deeper/fileD/1");
        final List<String> historyStarFirst  = createHistoryList("fileA/1", "fileA/2", "fileA/3", "fileB/1",                                              "fileE/1", "fileE/2");
        final List<String> historyStarSecond = createHistoryList("fileA/1", "fileA/2", "fileA/3", "fileB/1", "fileB/2", "deeper/fileD/1", "deeper/fileD/2");

        // Contents of each test-file is name/version -- NOTE: no version is given in store() so the test names should be ordered from 1 and up
        historyStarLocal.forEach(name -> repo.store(name.split("/\\d")[0], name.split("-",2)[0], "username", ""));
        assertThat(sorted(repo.getAllHistoryNames()), is(sorted(historyStarLocal)));

        // Prepare mocks
        when(dispatcher.getLocalStarName()).thenReturn(Mono.just("local"));
        when(dispatcher.relay(any(RelayRequest.class)/* /allHistory */,Mockito.<ParameterizedTypeReference<List<String>>>any()))
            // This is the /allHistory request
            .thenReturn(Flux.fromStream(Stream.of(
                RelayResponse.<List<String>>builder().starName("first").starUrl("").content(Optional.of(historyStarFirst)).build(),
                RelayResponse.<List<String>>builder().starName("local").starUrl("").content(Optional.of(historyStarLocal)).build(),
                RelayResponse.<List<String>>builder().starName("second").starUrl("").content(Optional.of(historyStarSecond)).build()
            )));
        when(dispatcher.relaySingle(any(RelayRequest.class)/* /file/** */,Mockito.<Class<String>>any()))
            // This is the download request
            .thenAnswer(inv -> {
                final RelayRequest req = inv.getArgument(0);
                assert req.servicePath != null;
                assert req.star != null;
                assertThat(req.star, not(is("local")));

                final String reqName = req.servicePath.split("-", 2)[0];
                final String starName = req.star;
                final String result = switch(reqName) {
                    case "file/fileA/3",
                         "file/fileB/2",
                         "file/deeper/fileD/2",
                         "file/fileE/1",
                         "file/fileE/2" -> reqName.replaceFirst("^file/","");
                    default -> throw new IllegalStateException("Unexpected reqName: " + reqName);
                };
                return Mono.just(RelayResponse.builder().content(Optional.of(result)).starName(starName).starUrl("").build());
            });

        // Do the sync
        final Set<String> changes = Objects.requireNonNull(repo.syncWithSettingsFromOtherStars().block());
        assertThat(changes, is(Set.of("fileA", "fileB", "fileE", "deeper/fileD")));

        // Assert that the right versions have been updated
        assertThat(sorted(repo.getAllHistoryNames().stream().map(n->n.split("-",2)[0]).toList()),
            is(sorted(List.of(
                "fileA/1", "fileA/2", "fileA/3",
                "fileB/1", "fileB/2",
                "fileC/1",
                "deeper/fileD/1", "deeper/fileD/2",
                "fileE/1", "fileE/2"))));

        // Assert that the updated versions are reflected in the latest version
        final List<String> expectedNames = sorted(List.of("fileA", "fileB", "fileC", "deeper/fileD", "fileE"));
        final Map<String,Integer> expectedLatestVersions = Map.of("fileA",3, "fileB",2, "fileC",1, "deeper/fileD",2, "fileE",2);

        assertThat(repo.getAllFilenames().stream().map(sf -> sf.name).toList(), is(expectedNames));
        expectedNames.forEach(filename -> {
            assertThat(filename, repo.getLatestVersion(filename).version, is(expectedLatestVersions.get(filename)));
            // assertThat(repo.getContentsOf(filename), is(filename + "/" + repo.getLatestVersion(filename).version))
        });
    }

    private void write(String path, String contents) throws ExecutionException, InterruptedException {
        final DataStore store = DataStores.get("settings").get();
        store.write(concatPath(CURRENT_DIR_NAME, path), contents).get();
    }

    private static List<String> createHistoryList(String... nameAndVersion) {
        return Arrays.stream(nameAndVersion)
            .map(nv -> nv + "-" + NOW_TEXT + "-username-CHANGED")
            .toList();
    }
    private static List<String> replaceVars(List<String> in) {
        return in.stream()
            .map(s -> s.replace("${changed}", NOW_TEXT + "-username-CHANGED"))
            .toList();
    }

    private static <T> List<T> sorted(Collection<T> collection) { return collection.stream().sorted().toList(); }
}