package net.microstar.common.datastore;

public class DataStoreException extends RuntimeException {
    public DataStoreException(String message) {
        super(message);
    }
    public DataStoreException(String message, Exception cause) {
        super(message, cause);
    }
}
