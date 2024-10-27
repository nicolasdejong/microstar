package net.microstar.common.datastore;

import com.fasterxml.jackson.core.type.TypeReference;
import net.microstar.common.util.DynamicReference;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

import static net.microstar.common.util.ExceptionUtils.rethrow;

/** The DataStore is fully asynchronous which can be very performant but is somewhat
  * cumbersome to code against. Sometimes blocking the thread is perfectly fine so for
  * those situations a BlockingDataStore can be created, which is a little envelope around
  * a DataStore that blocks for all calls. Since the return types of the methods are
  * different this class does not extend the DataStore.
  */
public class BlockingDataStore {
    private final DynamicReference<DataStore> dataStoreRef;

    private BlockingDataStore(DynamicReference<DataStore> dataStoreRef) {
        this.dataStoreRef = dataStoreRef;
    }

    public String toString() { return "[BlockingDataStore for " + dataStoreRef.get() + "]"; }

    public DataStore getStore() {
        return Optional.ofNullable(dataStoreRef.get()).orElseThrow();
    }

    public static BlockingDataStore forStore(DynamicReference<DataStore> storeRef) {
        return new BlockingDataStore(storeRef);
    }

    public static class BlockedDataStoreException extends RuntimeException {
        public BlockedDataStoreException(Exception other) {
            super(other.getMessage(), other);
        }
    }

    public Runnable                getCloseRunner()                        { return getStore().getCloseRunner(); }
    public List<DataStore.Item>    list()                                  { return rethrow(() -> getStore().list()                .get(), BlockedDataStoreException::new); }
    public List<DataStore.Item>    list(String path)                       { return rethrow(() -> getStore().list(path, false)     .get(), BlockedDataStoreException::new); }
    public List<DataStore.Item>    list(String path, boolean recursive)    { return rethrow(() -> getStore().list(path, recursive) .get(), BlockedDataStoreException::new); }
    public <T> Optional<T>         get(String path, Class<T> type)         { return rethrow(() -> getStore().get(path, type)       .get(), BlockedDataStoreException::new); }
    public <T> Optional<T>         get(String path, TypeReference<T> type) { return rethrow(() -> getStore().get(path, type)       .get(), BlockedDataStoreException::new); }
    public Optional<Instant>       getLastModified(String path)            { return rethrow(() -> getStore().getLastModified(path) .get(), BlockedDataStoreException::new); }
    public boolean                 exists(String path)                     { return rethrow(() -> getStore().exists(path)          .get(), BlockedDataStoreException::new); }
    public <T> boolean             store(String path, T data)              { return rethrow(() -> getStore().store(path, data)     .get(), BlockedDataStoreException::new); }
    public boolean                 remove(String path)                     { return rethrow(() -> getStore().remove(path)          .get(), BlockedDataStoreException::new); }
    public boolean                 move(String fromPath, String toPath)    { return rethrow(() -> getStore().move(fromPath, toPath).get(), BlockedDataStoreException::new); }
    public Optional<InputStream>   readStream(String path)                 { return rethrow(() -> getStore().readStream(path)      .get(), BlockedDataStoreException::new); }
    public Optional<byte[]>        read(String path)                       { return rethrow(() -> getStore().read(path)            .get(), BlockedDataStoreException::new); }
    public Optional<String>        readString(String path)                 { return rethrow(() -> getStore().readString(path)      .get(), BlockedDataStoreException::new); }
    public boolean                 write(String path, byte[] data)         { return rethrow(() -> getStore().write(path, data)     .get(), BlockedDataStoreException::new); }
    public boolean                 write(String path, String data)         { return rethrow(() -> getStore().write(path, data)     .get(), BlockedDataStoreException::new); }
    public boolean                 write(String path, InputStream source)  { return rethrow(() -> getStore().write(path, source)   .get(), BlockedDataStoreException::new); }
    public boolean                 write(String path, InputStream source, LongConsumer progress)
                                                                           { return rethrow(() -> getStore().write(path, source, progress).get(), BlockedDataStoreException::new); }
    public boolean                 touch(String path)                      { return rethrow(() -> getStore().touch(path)           .get(), BlockedDataStoreException::new); }

    public List<String>            listNames(String path)                  { return rethrow(() -> getStore().listNames(path)       .get(), BlockedDataStoreException::new); }
    public List<String>            listNames(String path, boolean recursive){return rethrow(() -> getStore().listNames(path, recursive).get(), BlockedDataStoreException::new); }

    public boolean                 isDir(String path)                      { return getStore().isDir(path); }
    public boolean                 isDir(DataStore.Item item)              { return getStore().isDir(item.path); }
    public String                  getParent(String path)                  { return getStore().getParent(path); }
    public String                  normalizePath(Object... pathParts)      { return getStore().normalizePath(pathParts); }
}
