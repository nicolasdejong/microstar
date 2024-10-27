package net.microstar.common.datastore;

import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class DataStoreTest {
    @Test void testGetParentDefault() {
        assertThat(DataStore.getParentDefault("a/b/c"), is("/a/b/"));
        assertThat(DataStore.getParentDefault("a/b/c/"), is("/a/b/"));
        assertThat(DataStore.getParentDefault("a/"), is("/"));
        assertThat(DataStore.getParentDefault("a"), is("/"));
        assertThat(DataStore.getParentDefault(""), is("/"));
    }
    @Test void testNormalizePathDefault() {
        assertThat(DataStore.normalizePathDefault(""), is("/"));
        assertThat(DataStore.normalizePathDefault("a/b"), is("/a/b"));
        assertThat(DataStore.normalizePathDefault("a/b/../d"), is("/a/d"));
        assertThat(DataStore.normalizePathDefault("a/b/c/d/../../../b2/"), is("/a/b2/"));
        assertThat(DataStore.normalizePathDefault("a/./b/./c/../d/e"), is("/a/b/d/e"));
        assertThat(DataStore.normalizePathDefault("a/."), is("/a/"));
        assertThat(DataStore.normalizePathDefault("a/b/.."), is("/a/"));
        assertThat(DataStore.normalizePathDefault("a/b/../"), is("/a/"));
        assertThat(DataStore.normalizePathDefault("a", ".", "b", ".", "c", "..", "d", "e"), is("/a/b/d/e"));
    }
}