package net.microstar.settings;

import lombok.Builder;
import lombok.Builder.Default;
import lombok.ToString;
import lombok.extern.jackson.Jacksonized;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.datastore.DataStore;
import net.microstar.common.datastore.DataStoreUtils;
import net.microstar.common.io.IOUtils;
import net.microstar.common.io.StateResource;
import net.microstar.common.util.DynamicReferenceNotNull;
import net.microstar.common.util.StringUtils;
import net.microstar.spring.DataStores;
import net.microstar.spring.exceptions.NotFoundException;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.microstar.common.util.CollectionUtils.last;
import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.settings.FileHistory.ActionType.DELETED;
import static net.microstar.settings.FileHistory.ActionType.UNKNOWN;

/**
 * This class keeps track of all versions of a file.<pre>
 *
 * Current implementation creates a new file for each new version.
 * The latest file is a copy of the current file. Each filename has the following format:
 *
 * {version}-{datetime}-{user}[-{message}]
 *
 * where
 * - version is a number starting from 1 which increases by 1 every new version
 * - datetime is a number in the form yyyMMddhhmmss. The file itself also has this as
 *   metadata but that can easily be lost, for example by copying/restoring from backup
 * - user that made the change ('filesystem' when the change was detected on the file system)
 * - message is an optional 'commit' message
 *
 * An extra file called 'state' is kept which currently just contains the highest version
 * number which prevents the need for scanning the whole directory to find what the latest
 * version number is.
 */
@Slf4j
public class FileHistory {
    private static final String NO_USER = "filesystem";
    public final String directory;
    private final StateResource<State> stateResource;
    private final DynamicReferenceNotNull<DataStore> dataStoreRef;

    public enum ActionType { UNKNOWN, CHANGED, DELETED, RESTORED, RENAMED }

    @Builder(toBuilder = true) @ToString @Jacksonized
    private static class State {
        public final int version;
        public final String latestName; // this is an optimization to prevent needing list() to get the latest name
    }

    @Builder(toBuilder = true) @Jacksonized
    public static class FileVersion {
        private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);
        private static final String FILENAME_PARTS_SEPARATOR = "-";
        public final int version;
        public final Instant modifiedTime; // this can hold ms which is not in the toString()
        @Default public final String user = "unknown";
        @Default public final String userMessage = "";
        @Default public final ActionType actionType = UNKNOWN;

        public String toString() {
            return Stream.of(String.valueOf(version), DATE_TIME_FORMATTER.format(modifiedTime), user, actionType == UNKNOWN ? "" : actionType.toString(), userMessage)
                .filter(StringUtils::isNotEmpty)
                .map(IOUtils::createValidReversibleFilename)
                .collect(Collectors.joining(FILENAME_PARTS_SEPARATOR));
        }
        public static String toString(Instant dt) { return DATE_TIME_FORMATTER.format(dt); }
        public static boolean hasCorrectFormat(String name) {
            return name.matches("^\\d+-\\d+-.*$");
        }
        public static FileVersion fromString(String s) {
            // parts: version-datetime-user-actionType-userMessage
            final String[] parts = IOUtils.reverseReversibleFilename(s).split(FILENAME_PARTS_SEPARATOR, 5);
            return builder()
                .version(parts.length <= 1 ? 0 : Integer.parseInt(parts[0]))
                .modifiedTime(noThrow(() -> DATE_TIME_FORMATTER.parse(parts[1], Instant::from)).orElse(Instant.EPOCH))
                .user(        parts.length <= 2 ? "" : parts[2])
                .actionType(  parts.length <= 3 ? UNKNOWN : noThrow(() -> ActionType.valueOf(parts[3])).orElse(UNKNOWN))
                .userMessage( parts.length <= 4 || parts[4].isBlank() ? "" : parts[4])
                .build();
        }
    }

    @SuppressWarnings("this-escape")
    public FileHistory(String historyDirectory) {
        this.dataStoreRef = DataStores.get("settings");
        this.directory = historyDirectory;
        this.stateResource = new StateResource<>(dataStoreRef, IOUtils.concatPath(directory, "state"), State.class)
            .setDefault(State.builder().build());
        if(stateResource.get().version < 10) updateStateFile(); // no state file or cheap to update
    }

    public FileVersion setLatestVersion(int version, Instant lastModifiedUTC, @Nullable String user, ActionType actionType, @Nullable String message) {
        final FileVersion fileVersion = FileVersion.builder()
            .version(version)
            .modifiedTime(lastModifiedUTC)
            .user(Optional.ofNullable(user).orElse(NO_USER).replace("-","_"))
            .actionType(actionType)
            .userMessage(message)
            .build();
        stateResource.set(stateResource.get().toBuilder().version(fileVersion.version).latestName(fileVersion.toString()).build());
        return fileVersion;
    }
    public void addNewVersion(String currentFile, Instant lastModifiedUTC, @Nullable String user, ActionType actionType, @Nullable String message) {
        final FileVersion fileVersion = setLatestVersion(getNewVersion(), lastModifiedUTC, user, actionType, message);
        final String latestFilePath = IOUtils.concatPath(directory, fileVersion.toString());

        if(actionType == DELETED) {
            getStore().touch(latestFilePath);
        } else {
            DataStoreUtils.copy(getStore(), currentFile, getStore(), latestFilePath);
        }
    }

    public void updateStateFile() {
        list().stream().findFirst().ifPresent(mostRecentVersion ->
           stateResource.set(State.builder()
               .version(mostRecentVersion.version)
               .latestName(mostRecentVersion.toString())
               .build())
        );
    }

    public int getLatestVersionNumber() {
        return stateResource.get().version;
    }
    public FileVersion getLatestVersion() {
        return FileVersion.fromString(stateResource.get().latestName);
    }

    public List<FileVersion> list() {
        final BlockingDataStore store = getStore();
        final Comparator<FileVersion> filenameCmp = Comparator.comparing(fn -> fn.version);
        return store.list(directory).stream()
            .filter(item -> !store.isDir(item))
            .map(item -> last(item.path.split("/")).orElse(""))
            .filter(FileVersion::hasCorrectFormat)
            .map(FileVersion::fromString)
            .sorted(filenameCmp.reversed())
            .toList();
    }

    public FileVersion getFileVersion(int version) {
        if(version == getLatestVersionNumber()) return FileVersion.fromString(getLatestFilename());
        return list().stream().filter(fn -> fn.version == version).findFirst()
            .orElseThrow(() -> new NotFoundException("No version " + version));
    }
    public String getContentOfVersion(int version) {
        return getContentOf(getFileVersion(version));
    }
    public String getContentOf(FileVersion fileVersion) {
        return getStore().readString(IOUtils.concatPath(directory, fileVersion.toString()))
            .orElseThrow(() -> new NotFoundException(getLatestFilename()));
    }

    private BlockingDataStore getStore() { return BlockingDataStore.forStore(dataStoreRef); }
    private int getNewVersion() {
        return Optional.of(stateResource.get())
            .map(state -> stateResource.set(state.toBuilder().version(state.version + 1).build()))
            .map(state -> state.version)
            .orElse(0);
    }
    private String getLatestFilename() {
        return stateResource.get().latestName;
    }
}
