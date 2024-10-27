package net.microstar.common.datastore;

import java.time.Duration;

class MemoryDataStoreTest extends AbstractDataStoreTest { // NOSONAR -- there are tests in super

    @Override
    DataStore createStore() {
        return new MemoryDataStore(Duration.ZERO, Duration.ZERO);
    }
}
