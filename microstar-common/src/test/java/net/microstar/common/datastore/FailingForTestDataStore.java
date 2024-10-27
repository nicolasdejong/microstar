package net.microstar.common.datastore;

import lombok.RequiredArgsConstructor;
import lombok.experimental.StandardException;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

/** DataStore that can be set in failing mode (default on) to test how failures are handled */
@RequiredArgsConstructor
public class FailingForTestDataStore extends AbstractDataStore {
    private final DataStore source;
    private boolean shouldFail = true;

    @StandardException
    @SuppressWarnings("this-escape")
    public static class TestFailException extends RuntimeException {}


    public FailingForTestDataStore setFailing(boolean shouldFail) {
        this.shouldFail = shouldFail;
        return this;
    }

    private void checkFail() {  if(shouldFail) throw new TestFailException(); }

    @Override public CompletableFuture<List<Item>> list(String path, boolean recursive) {
        return shouldFail ? CompletableFuture.failedFuture(new TestFailException()) : source.list(path, recursive);
    }

    @Override public CompletableFuture<Optional<Instant>> getLastModified(String path) {
        checkFail(); return source.getLastModified(path);
    }

    @Override public CompletableFuture<Boolean> exists(String path) {
        checkFail(); return source.exists(path);
    }

    @Override public CompletableFuture<Boolean> remove(String path) {
        checkFail(); return source.remove(path);
    }

    @Override public CompletableFuture<Boolean> move(String fromPath, String toPath) {
        checkFail(); return source.move(fromPath, toPath);
    }

    @Override public CompletableFuture<Optional<InputStream>> readStream(String path) {
        checkFail(); return source.readStream(path);
    }

    @Override public CompletableFuture<Optional<byte[]>> read(String path) {
        checkFail(); return source.read(path);
    }

    @Override public CompletableFuture<Boolean> write(String path, byte[] data, Instant time) {
        checkFail(); return source.write(path, data, time);
    }

    @Override public CompletableFuture<Boolean> write(String path, InputStream source, Instant time, LongConsumer progress) {
        checkFail(); return this.source.write(path, source, time, progress);
    }

    @Override public CompletableFuture<Boolean> touch(String path, Instant time) {
        checkFail(); return source.touch(path, time);
    }
}
