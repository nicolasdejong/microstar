package net.microstar.common.datastore;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.io.IOUtils;
import net.microstar.common.util.ThreadUtils;
import net.microstar.common.util.Threads;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

import static net.microstar.common.util.ExceptionUtils.noThrow;

/**
 * DataStore implementation for an SQL connection.
 * This class uses the following table and tries to create it when not available:<pre>
 *
 * table name 'files' or as configured in datastore configuration (key 'table')
 * column: section VARCHAR(32)   -- used when configuration contains a section name (key 'section')
 *                                  (this can be convenient if the table is shared between multiple data stores)
 * column:    path VARCHAR(256)  -- for the full path names (no empty directories supported)
 * column:    time TIMESTAMP(3)  -- last modified time
 * column:    data BLOB          -- file contents
 *
 * primary key: path  -- used to get specific files
 * indexed key: time  -- used to regularly check for updates by querying for 'time > lastKnownTime'
 * </pre>
 *
 * For the creation SQL, see makeSureTableExists()
 */
@Slf4j
public class SqlDataStore extends AbstractDataStore {
    private final String jdbcUrl;
    private final String table;
    private final String section;
    private final HikariDataSource connectionPool;
    private final AtomicReference<Instant> lastKnownTime = new AtomicReference<>(Instant.EPOCH);
    private final Future<Void> periodicUpdateRunner;


    @SuppressWarnings("this-escape")
    public SqlDataStore(String jdbcUrl, @Nullable String section, @Nullable String table, @Nullable String user, @Nullable String password, int poolSize, Duration pollingTime) {
        this.jdbcUrl = jdbcUrl;
        final HikariConfig hikariConfig = getHikariConfig(jdbcUrl, user, password, poolSize);

        connectionPool = new HikariDataSource( hikariConfig );

        this.table = table == null ? "files" : table;
        this.section = section == null ? "" : section;
        makeSureTableExists();

        Threads.execute(Duration.ofMillis(Math.min(pollingTime.toMillis(), 1000)/2), () -> { if(!connectionPool.isClosed()) lastKnownTime.set(getLastKnownTime()); });
        periodicUpdateRunner = Threads.executePeriodically(pollingTime, /*runFirst=*/false, this::pollForChanges);
    }

    private static HikariConfig getHikariConfig(String jdbcUrl, @Nullable String user, @Nullable String password, int poolSize) {
        final HikariConfig config = new HikariConfig();
        config.setPoolName("SqlDataStore");
        config.addDataSourceProperty("URL", jdbcUrl); // h2 doesn't always work with setJdbcUrl()
        config.setJdbcUrl(jdbcUrl);

        if(jdbcUrl.contains(":h2:")) config.setDataSourceClassName("org.h2.jdbcx.JdbcDataSource"); // Hikari needs this for H2 specifically

        if(user     != null && !user.isEmpty()    ) config.addDataSourceProperty("user", user);
        if(password != null && !password.isEmpty()) config.addDataSourceProperty("password", password);

        // For handling many REST / resource calls in a short time, really
        // cache is necessary as otherwise too many connections are needed
        // to the database. Increase the pool size a bit for those first
        // request-groups.
        config.setMaximumPoolSize(poolSize <= 0 ? 10 : poolSize);
        config.setConnectionTimeout(10_000);
        config.setIdleTimeout(600_000);
        config.setMaxLifetime(1_800_000);

        return config;
    }

    Connection getConnection() throws SQLException {
        return connectionPool.getConnection();
    }

    /** Stop the database altogether */
    void shutdown() {
        try (final Connection connection = getConnection();
             final Statement statement = connection.createStatement()) {
            //noinspection SqlDialectInspection,SqlNoDataSourceInspection
            statement.execute("shutdown");
        } catch (final SQLException e) {
            // already shutdown
        }
    }

    @Override
    public Runnable getCloseRunner() {
        // Separate Runnable from this store (so the SqlDataStore instance can be garbage collected
        return new Runnable() {
            private final HikariDataSource pool = connectionPool;
            private final Future<Void> pur = periodicUpdateRunner;
            public void run() {
                pur.cancel(false);
                pool.close();
                closed();
            }
        };
    }

    @Override
    public CompletableFuture<List<Item>> list(String path, boolean recursive) {
        return supplyAsync(() -> {
            final String normalizedPath = addSlash(normalizePath(path)); // NOSONAR -- always ends with one slash
            final String sqlRecursive =
                """
                SELECT SUBSTRING(path, ${normalizedPathLength}+1, LENGTH(path)-${normalizedPathLength}) AS relPath, time, 1, size as len
                FROM ${table}
                WHERE path LIKE ?
                """;
            final Map<String,Object> vars = Map.of(
                "sqlRecursive", sqlRecursive,
                "normalizedPathLength", normalizedPath.length()
            );
            final String sql = prepareQuery(recursive
                    ? "${sqlRecursive}"
                    : "SELECT DISTINCT COALESCE(NULLIF(LEFT(relPath, POSITION('${delim}' IN relPath)), ''), relPath) as path, MAX(time), COUNT(*), SUM(len) FROM (${sqlRecursive}) AS subq GROUP BY path"
                , vars);
            try (final Connection connection = getConnection();
                 final PreparedStatement statement = connection.prepareStatement(prepareQuery(sql, vars))) {
                statement.setString(1, normalizedPath + "%");
                try(final ResultSet results = statement.executeQuery()) {
                    final List<Item> items = new ArrayList<>();
                    while(results.next()) items.add(new Item(results.getString(1),
                                                             results.getTimestamp(2).toInstant(),
                                                             results.getInt(3),
                                                             results.getLong(4)));
                    return items.stream().sorted(ITEM_COMPARATOR).toList();
                }
            } catch (final SQLException e) {
                log.error("Unable to read data from path \"{}\"", path, e);
                return Collections.emptyList();
            }
        });
    }

    @Override
    public CompletableFuture<Optional<Instant>> getLastModified(String path) {
        return supplyAsync(() -> {
            final String normalizedPath = normalizePath(path);
            try (final Connection connection = getConnection();
                 final PreparedStatement statement = connection.prepareStatement(prepareQuery("SELECT time FROM ${table} WHERE path = ?"))) {
                statement.setString(1, normalizedPath);
                try(final ResultSet results = statement.executeQuery()) {
                    if(!results.next()) return Optional.empty();
                    final Timestamp timestamp = results.getTimestamp(1);
                    return Optional.of(timestamp.toInstant());
                }
            } catch (final SQLException e) {
                log.error("Unable to read last modified from path \"{}\"", path, e);
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> exists(String path) {
        // Path may be a file (for which there may be a key) or a directory
        // for which there may be files that have the directory as prefix.
        return supplyAsync(() -> {
            final String normalizedPath = normalizePath(path);
            try (final Connection connection = getConnection();
                 final PreparedStatement statement = connection.prepareStatement(prepareQuery(
                     "SELECT 1 FROM ${table} WHERE path=? OR path LIKE ? ESCAPE '!'"))) {
                statement.setString(1, normalizedPath);
                statement.setString(2, emEscapeLike(normalizedPath) + (normalizedPath.endsWith("/") ? "" : "/") + "%");
                try(final ResultSet results = statement.executeQuery()) {
                    return results.isBeforeFirst();
                }
            } catch (final SQLException e) {
                log.error("db.exists({}) failed", path, e);
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> remove(String path) {
        return list(path, true).thenApply(list -> {
            final String normalizedPath = normalizePath(path);
            final boolean pathIsDir = !list.isEmpty() || isDir(normalizedPath);
            final String fromWhere = prepareQuery(" FROM ${table} WHERE path LIKE ? ESCAPE '!'");

            try(final Connection connection = getConnection();
                final PreparedStatement delRowStatement = connection.prepareStatement("DELETE" + fromWhere)) {
                final String emPath = emEscapeLike(normalizedPath);
                final String likePath = (pathIsDir ? addSlash(emPath) : emPath) + "%";

                // Delete the blob itself (which is not stored inside the row(s) that will be deleted)
                deleteBlob(connection, fromWhere, likePath);

                // Remove the file row(s) for the given path
                delRowStatement.setString(1, likePath);
                delRowStatement.executeUpdate();

                if(pathIsDir) changed(list.stream().map(item -> IOUtils.concatPath(normalizedPath, item.path)).toList());
                else          changed(normalizedPath);
                return true;
            } catch (final SQLException e) {
                log.error("Unable to remove {}", path, e);
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> move(String fromPath0, String toPath0) {
        return remove(toPath0).thenCompose(b -> list(fromPath0, true)).thenApply(fromList -> {
            final String fromPath   = normalizePath(fromPath0);
            final boolean isFromDir = !fromList.isEmpty() || isDir(fromPath);
            final String toPath     = normalizePath(isFromDir ? addSlash(toPath0) : toPath0);
            final String query = isFromDir
                ? "UPDATE ${table} SET path = REPLACE(path, ?, ?) WHERE path LIKE ? ESCAPE '!'"
                : "UPDATE ${table} SET path = ? WHERE path = ?";
            try (final Connection connection = getConnection();
                 final PreparedStatement statement = connection.prepareStatement(prepareQuery(query))) {
                if(isFromDir) {
                    statement.setString(1, fromPath);
                    statement.setString(2, toPath);
                    statement.setString(3, emEscapeLike(fromPath) + "%");
                    changed(fromList.stream().filter(f -> !isDir(f.path)).map(f -> IOUtils.concatPath(fromPath, f.path)).toList(),
                            fromList.stream().filter(f -> !isDir(f.path)).map(f -> IOUtils.concatPath(toPath, f.path)).toList());
                } else {
                    statement.setString(1, toPath);
                    statement.setString(2, fromPath);
                    changed(fromPath, toPath);
                }
                statement.executeUpdate();
                return true;
            } catch (final SQLException e) {
                log.error("Unable to move from {} to {}", fromPath0, toPath0, e);
                return false;
            }
        });
    }

    @Override
    public CompletableFuture<Optional<byte[]>> read(String path) {
        return readStream(path)
            .thenApply(optStream -> optStream.flatMap(stream -> {
                try(final InputStream in = stream) {
                    return Optional.of(in.readAllBytes());
                } catch(final IOException e) {
                    log.error("Unable to read data from path \"{}\"", path, e);
                    return Optional.empty();
                }
            }));
    }

    @Override
    public CompletableFuture<Optional<InputStream>> readStream(String path) {
        return supplyAsync(() -> {
            final String normalizedPath = normalizePath(path);
            try {
                final Connection connection = getConnection();
                connection.setAutoCommit(false); // autoCommit is not allowed with large objects

                final PreparedStatement statement = connection.prepareStatement(prepareQuery("SELECT data FROM ${table} WHERE path = ?"), ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                statement.setString(1, normalizedPath);
                final ResultSet results = statement.executeQuery();

                final Runnable closeConnection = () -> {
                    noThrow(results::close, ex -> log.error("Unable to close the database results set: {}", ex.getMessage()));
                    noThrow(statement::close, ex -> log.error("Unable to close the database statement: {}", ex.getMessage()));
                    noThrow(connection::close, ex -> log.error("Unable to close the database connection: {}", ex.getMessage()));
                };

                if(!results.next()) {
                    closeConnection.run();
                    return Optional.empty();
                }

                return Optional.of(new FilterInputStream(results.getBlob(1).getBinaryStream()) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        closeConnection.run();
                    }
                });
            } catch (final SQLException e) {
                log.error("Unable to read data from path \"{}\": {}", path, e.getMessage());
                return Optional.empty();
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> write(String path, byte[] data, Instant time) {
        return write(path, new ByteArrayInputStream(data), time, sizeDone -> {});
    }

    @Override
    public CompletableFuture<Boolean> write(String path, InputStream source, Instant time, LongConsumer progress) {
        final Consumer<Long> throttledProgress = ThreadUtils.throttleLC(progress, Duration.ofSeconds(1)); // NOSONAR -- LongConsumer is not possible here
        final AtomicLong transferredSize = new AtomicLong(0);
        final InputStream progressSource = new FilterInputStream(source) {
            private int transferred(int size) {
                if(size > 0) transferredSize.getAndAdd(size);
                throttledProgress.accept(transferredSize.get());
                return size;
            }
            @Override public int read() throws IOException { return transferred(in.read()); }
            @Override public int read(byte[] b) throws IOException { return transferred(in.read(b, 0, b.length)); }
            @Override public int read(byte[] b, int off, int len) throws IOException { return transferred(in.read(b, off, len)); }
        };
        return supplyAsync(() -> {
            // NOTE: Will overwrite if a file at given path already exists!
            final String normalizedPath = normalizePath(path);
            try (final Connection connection = getConnection();
                 final PreparedStatement statement = connection.prepareStatement(prepareQuery(
                     "INSERT INTO ${table} VALUES(?, ?, ?, ?, ?) " +
                     (jdbcUrl.contains("postgres") ?
                         "ON CONFLICT(path) DO UPDATE " +
                         "SET section=excluded.section, path=excluded.path, time=excluded.time, data=excluded.data, size=excluded.size;"
                         : ""
                     )
                 ))) {
                connection.setAutoCommit(false); // autocommit is not allowed with blobs
                statement.setString(1, section);
                statement.setString(2, normalizedPath);
                statement.setTimestamp(3, Timestamp.from(time));
                statement.setBlob(4, progressSource);
                statement.setInt(5, 0); // unknown at this time
                statement.executeUpdate();
                connection.commit();
                noThrow(source::close);

                // After exhausting the source input stream we now know what the data size is
                try (final PreparedStatement statement2 = connection.prepareStatement(prepareQuery("UPDATE ${table} SET size=? WHERE section=? AND path=?"))) {
                    statement2.setInt(1, (int) transferredSize.get());
                    statement2.setString(2, section);
                    statement2.setString(3, normalizedPath);
                    statement2.executeUpdate();
                    connection.commit();
                }
            } catch (final SQLException e) {
                log.error("Unable to write data to path \"{}\": {}", path, e.getMessage());
                return false;
            }
            changed(normalizedPath);
            return true;
        });
    }


    @Override
    public CompletableFuture<Boolean> touch(String path, Instant time) {
        final String targetPath = normalizePath(path);
        return exists(targetPath).thenComposeAsync(pathExists -> {
            if (pathExists) { // NOSONAR -- boolean
                boolean success = false;
                try (final Connection connection = getConnection();
                     final PreparedStatement statement = connection.prepareStatement(prepareQuery("UPDATE ${table} SET time=? WHERE path=?"))) {
                    statement.setTimestamp(1, Timestamp.from(time));
                    statement.setString(2, targetPath);
                    statement.executeUpdate();
                    success = true;
                } catch (final SQLException e) {
                    log.error("Unable to write data to path \"{}\"", path, e);
                }
                return CompletableFuture.completedFuture(success);
            } else {
                return write(targetPath, "", time);
            }
        }).thenCompose(success -> {
            if(success) changed(targetPath); // NOSONAR -- boolean
            return CompletableFuture.completedFuture(success);
        });
    }


    private void deleteBlob(Connection connection, String fromWhere, String likePath) throws SQLException {
        // Delete the BLOB using proprietary PostgresSQL command
        try(final PreparedStatement delBlobStatement = connection.prepareStatement(prepareQuery("SELECT lo_unlink(data)" + fromWhere))) {
            delBlobStatement.setString(1, likePath);
            delBlobStatement.execute();
        } catch(final Exception loUnlinkFailed) {
            // If that failed, set a new, empty blob so the blob won't use any space
            // (setting data to null will not remove the existing blob)
            try (final PreparedStatement emptyBlobStatement = connection.prepareStatement(prepareQuery("UPDATE ${table} SET data=? WHERE" + fromWhere.split("WHERE", 2)[1]))) {
                final Blob emptyBlob = connection.createBlob();
                emptyBlob.setBytes(1, new byte[0]);
                emptyBlobStatement.setBlob(1, emptyBlob);
                emptyBlobStatement.setString(2, likePath);
                emptyBlobStatement.executeUpdate();
            }
        }
    }

    private void pollForChanges() {
        try {
            getChangedPathsAndUpdateLastKnownTime().thenAccept(this::changed).get();
        } catch (final ExecutionException | InterruptedException e) { // NOSONAR -- this is called by a periodic runner -- rethrowing is useless
            log.error("Error running updateChangedPaths()", e);
        }
    }
    private Instant getLastKnownTime() {
        if(connectionPool.isClosed()) return Instant.EPOCH;
        try (final Connection connection = getConnection();
             final PreparedStatement statement = connection.prepareStatement(prepareQuery("SELECT MAX(time) FROM ${table}"))) {
            try(final ResultSet results = statement.executeQuery()) {
                if(!results.next()) return Instant.now();
                final @Nullable Timestamp timestamp = results.getTimestamp(1);
                return timestamp == null ? Instant.now() : timestamp.toInstant();
            }
        } catch (final SQLException e) {
            log.error("Unable to read last time", e);
            return Instant.EPOCH;
        }
    }
    private CompletableFuture<List<String>> getChangedPathsAndUpdateLastKnownTime() {
        return supplyAsync(() -> {
            try (final Connection connection = getConnection();
                 final PreparedStatement statement = connection.prepareStatement(prepareQuery("SELECT time, path FROM ${table} WHERE time > ?"))) {
                statement.setTimestamp(1, Timestamp.from(lastKnownTime.get()));
                try(final ResultSet results = statement.executeQuery()) {
                    final List<String> list = new ArrayList<>();
                    while(results.next()) {
                        final Timestamp timestamp = results.getTimestamp(1);
                        final Instant instant = timestamp.toInstant();
                        if(instant.isAfter(lastKnownTime.get())) lastKnownTime.set(instant);
                        list.add(results.getString(2));
                    }
                    return list;
                }
            } catch (final SQLException e) {
                log.error("Unable to get latest changes paths", e);
                return Collections.emptyList();
            }
        });
    }


    @SuppressWarnings("this-escape")
    private boolean containsTable(String tableName) {
        final String[] tnParts = tableName.split("\\.");
        final String schema = tnParts.length > 1 ? tnParts[0] : "";
        final String tn     = tnParts.length > 1 ? tnParts[1] : tableName;
        try(final Connection connection = getConnection();
            final ResultSet resultSet = connection.getMetaData().getTables(null, schema, tn, new String[]{"TABLE"})){
            return resultSet.next();
        } catch(final SQLException ignored) {
            return false;
        }
    }
    @SuppressWarnings("this-escape")
    private void makeSureTableExists() {
        if(!containsTable(table)) {
            try (final Connection connection = getConnection();
                 final Statement statement = connection.createStatement()) {
                statement.execute(prepareQuery("""
                    CREATE TABLE IF NOT EXISTS ${table}(
                        section VARCHAR(32),
                        path VARCHAR(256),
                        time TIMESTAMP(3),
                        data BLOB,
                        size INTEGER,
                        PRIMARY KEY (path)
                    )
                """));
                connection.commit();
            } catch (final SQLException e) {
                throw new DataStoreException("Unable to create table " + table, e);
            }
        }
    }
    private String prepareQuery(String sql) { return prepareQuery(sql, Collections.emptyMap()); }
    private String prepareQuery(String sql, Map<String,Object> vars) {
        final AtomicReference<String> result = new AtomicReference<>(sql);
        vars.forEach((key,val) -> result.set(result.get().replace("${" + key + "}", "" + val)));
        return result.get()
            .replace("${table}", table)
            .replace("${delim}", "/")
            .replace("\n", " ")
            .replace(" WHERE ", section.isEmpty() ? " WHERE " : " WHERE section='" + section + "' AND ")
            ;
    }
    private String emEscapeLike(String textToLike) {
        return textToLike.replaceAll("([!%\\[])", "!$1");
    }
    private String addSlash(String path) {
        return path.replaceAll("/+$", "") + "/";
    }
}
