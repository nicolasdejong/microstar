package net.microstar.settings;

import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.common.io.IOUtils;
import net.microstar.settings.FileHistory.FileVersion;
import net.microstar.spring.DataStores;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;

import static net.microstar.settings.FileHistory.ActionType.CHANGED;
import static net.microstar.settings.FileHistory.ActionType.UNKNOWN;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;

class FileHistoryTest {
    private FileHistory fileHistory;

    @BeforeEach void setup() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "microstar.dataStores.settings", Map.of("type", "memory")
        )));
        fileHistory = new FileHistory("history");
    }
    @AfterEach void cleanup() {
        DataStores.closeAll();
        DynamicPropertiesManager.clearAllState();
    }

    @Test void filenameShouldSerializeCorrectly() {
        final FileVersion fn = FileHistory.FileVersion.builder()
            .version(123)
            .modifiedTime(Instant.now())
            .user("someUser")
            .actionType(CHANGED)
            .userMessage("some:Message")
            .build();

        final FileVersion fn2 = FileVersion.fromString(fn.toString());
        assertThat(fn2.toString(), is(fn.toString()));

        final FileVersion fnNoMsg = fn.toBuilder().userMessage(null).build();
        assertThat(FileHistory.FileVersion.fromString(fnNoMsg.toString()).toString(), is(fnNoMsg.toString()));

        final String fn3Input = "1-20220624112910-filesystem";
        final FileVersion fn3 = FileVersion.fromString(fn3Input);
        assertThat(fn3.version, is(1));
        assertThat(fn3.modifiedTime, is(LocalDateTime.of(2022,6,24,11,29,10,0).toInstant(ZoneOffset.UTC)));
        assertThat(fn3.user, is("filesystem"));
        assertThat(fn3.actionType, is(UNKNOWN));
        assertThat(fn3.userMessage, is(""));

        final String fn3String = fn3.toString();
        assertThat(fn3String, is(fn3Input));
    }
    @Test void filenameSerializationShouldLeadToValidPathName() {
        final FileVersion fn = FileHistory.FileVersion.builder()
            .version(123)
            .modifiedTime(Instant.now())
            .user("someUser")
            .userMessage("changed to http://some.domain")
            .build();
        assertThat(fn.toString(), not(containsString(":")));
        assertThat(fn.toString(), not(containsString("/")));
    }

    @Test void newVersionsShouldBeAdded() {
        final BlockingDataStore store = BlockingDataStore.forStore(DataStores.get("settings"));
        final String testFile = "test.yaml";
        final String content = "foo:bar";
        final String content2 = "foo:bar:zoo";
        final Instant now = Instant.now();

        store.write(testFile, content);

        assertThat(fileHistory.list(), is(Collections.emptyList()));
        assertThat(fileHistory.getLatestVersionNumber(), is(0));

        fileHistory.addNewVersion(testFile, now, "someUser", CHANGED, "someMessage");

        assertThat(fileHistory.list().size(), is(1));
        assertThat(fileHistory.getLatestVersionNumber(), is(1));
        FileVersion fileVersion = fileHistory.list().get(0);

        assertThat(fileVersion.version, is(1));
        assertThat(ChronoUnit.SECONDS.between(Instant.now(), fileVersion.modifiedTime), is(lessThan(1L)));
        assertThat(fileVersion.user, is("someUser"));
        assertThat(fileVersion.userMessage, is("someMessage"));
        assertThat(fileVersion.actionType, is(CHANGED));
        assertThat(fileHistory.getContentOf(fileHistory.getLatestVersion()), is(content));

        store.write(testFile, content2);
        fileHistory.addNewVersion(testFile, now, "someUser2", CHANGED, "someMessage2");

        assertThat(fileHistory.list().size(), is(2));
        assertThat(fileHistory.getLatestVersionNumber(), is(2));
        fileVersion = fileHistory.list().get(0);

        assertThat(fileVersion.version, is(2));
        assertThat(ChronoUnit.SECONDS.between(Instant.now(), fileVersion.modifiedTime), is(lessThan(1L)));
        assertThat(fileVersion.user, is("someUser2"));
        assertThat(fileVersion.userMessage, is("someMessage2"));
        assertThat(fileVersion.actionType, is(CHANGED));
        assertThat(fileHistory.getContentOfVersion(fileHistory.getLatestVersionNumber()), is(content2));

        assertThat(store.exists(IOUtils.concatPath(fileHistory.directory, "state")), is(true));

        final FileHistory fileHistory2 = new FileHistory(fileHistory.directory);
        assertThat(fileHistory2.getLatestVersionNumber(), is(2));
        fileVersion = fileHistory.list().get(0);

        assertThat(fileVersion.version, is(2));
        assertThat(ChronoUnit.SECONDS.between(Instant.now(), fileVersion.modifiedTime), is(lessThan(3L)));
        assertThat(fileVersion.user, is("someUser2"));
        assertThat(fileVersion.userMessage, is("someMessage2"));
        assertThat(fileHistory2.getContentOfVersion(fileHistory2.getLatestVersionNumber()), is(content2));
    }
}