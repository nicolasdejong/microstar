package net.microstar.common.datastore;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;

import static net.microstar.testing.TestUtils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("SqlResolve")
class SqlDataStoreTest extends AbstractDataStoreTest { // Most tests are in super
    private static final Logger HIKARI_LOGGER = (Logger) LoggerFactory.getLogger("com.zaxxer.hikari");

    @BeforeAll
    static void hideHikariLogging() {
        HIKARI_LOGGER.setLevel(Level.ERROR);
    }
    @AfterAll
    static void resumeHikariLogging() {
        HIKARI_LOGGER.setLevel(Level.WARN);
    }

    @Override
    DataStore createStore() {
        return new SqlDataStore("jdbc:h2:mem:test;DATABASE_TO_UPPER=false", null, "files", null, null, 10, Duration.ofMillis(1000)); // add schema in url & table-prefix
    }

    @AfterEach
    void cleanup() {
        ((SqlDataStore)store).shutdown();
        store.getCloseRunner().run();
    }

    @Test
    void existsShouldOnlyUseFullName() throws ExecutionException, InterruptedException {
        assertThat(store.exists("root").get(), is(false));
        assertThat(store.exists("root.txt").get(), is(true));
        assertThat(store.exists("/1/2/deep").get(), is(false));
        assertThat(store.exists("/1/2/deeper").get(), is(true));
        assertThat(store.exists("/1/2/deeper/").get(), is(true));
    }

    @Test
    void testExternalChange() throws SQLException {
        final String filename = "/externalFile.txt";
        final SqlDataStore sqlStore = (SqlDataStore) store;
        final List<String> detectedChanges = new CopyOnWriteArrayList<>();

        // Give the db a bit of time to settle down
        sleep(1000);

        // Add a detector for the file we will add
        sqlStore.onChange(detectedChanges::addAll);

        // Here simulate another service that adds a file to the database
        try (final Connection connection = sqlStore.getConnection();
             final PreparedStatement statement = connection.prepareStatement("INSERT INTO files VALUES(?, ?, ?, ?, ?);")) {
            statement.setString(1, "");
            statement.setString(2, filename);
            statement.setTimestamp(3, Timestamp.from(Instant.now()));
            statement.setBlob(4, new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
            statement.setInt(5, 3);
            statement.executeUpdate();
        }

        // There is no event coming from the database, so periodically an 'anything new?' check is performed.
        // Wait for that.
        int tries = 20;
        while(tries-->0 && !detectedChanges.contains(filename)) sleep(250);

        assertTrue(detectedChanges.contains(filename));
    }

    @Test
    void testRemoveBlob() throws SQLException, ExecutionException, InterruptedException {
        final String path = "/testFile";
        final String data = "test data";

        store.write(path, data).get();
        assertTrue(getBlobLength(path) > 0);
        assertThat(store.readString(path).get().orElse(""), is(data));

        store.remove(path).get();
        assertThat(store.readString(path).get().orElse("<none>"), is("<none>"));
        assertTrue(getBlobLength(path) <= 0);
    }

    private long getBlobLength(String path) throws SQLException {
        try (final Connection connection = ((SqlDataStore)store).getConnection();
             final PreparedStatement statement = connection.prepareStatement("SELECT data FROM files WHERE path=?;")) {
            statement.setString(1, path);
            final ResultSet results = statement.executeQuery();
            return results.next() ? results.getBlob(1).length() : -1;
        }
    }
}