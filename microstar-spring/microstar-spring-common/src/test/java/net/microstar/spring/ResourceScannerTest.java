package net.microstar.spring;

import net.microstar.common.io.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;

import java.net.URI;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class ResourceScannerTest {

    @Test void getResourceShouldReturnExistingResource() {
        assertThat(ResourceScanner.getResource("ResourceScanner/a.txt").exists(), is(true));
        assertThat(ResourceScanner.getResource("ResourceScanner/deeperDir/deeper.txt").exists(), is(true));
        assertThat(ResourceScanner.getResource("ResourceScanner/notExisting.yml").exists(), is(false));
    }
    @Test void getResourcesShouldGetChildResources() {
        assertThat(Arrays.stream(ResourceScanner.getResources("ResourceScanner")).map(this::toString).sorted().toList(),
            is(List.of("a.txt", "b.txt", "deeperDir/")));
        assertThat(Arrays.stream(ResourceScanner.getResources("ResourceScanner/deeperDir")).map(this::toString).sorted().toList(),
            is(List.of("deeperDir/deeper.txt")));
        assertThat(Arrays.stream(ResourceScanner.getResources("")).map(this::toString).sorted().toList(),
            is(Collections.emptyList()));
    }
    @Test void getResourcesRecursivelyShouldGetAllDeeperResources() {
        assertThat(Arrays.stream(ResourceScanner.getResources("ResourceScanner/", true)).map(this::toString).sorted().toList(),
            is(List.of("a.txt", "b.txt", "deeperDir/", "deeperDir/deeper.txt")));
        assertThat(Arrays.stream(ResourceScanner.getResources("ResourceScanner/deeperDir", true)).map(this::toString).sorted().toList(),
            is(List.of("deeperDir/deeper.txt")));
        assertThat(Arrays.stream(ResourceScanner.getResources("", true)).map(this::toString).sorted().toList(),
            is(Collections.emptyList()));
    }
    @Test void getResourceNamesShouldGetChildNames() {
        assertThat(Arrays.stream(ResourceScanner.getResourceNames("ResourceScanner")).sorted().toList(),
            is(List.of("a.txt", "b.txt", "deeperDir/")));
        assertThat(Arrays.stream(ResourceScanner.getResourceNames("ResourceScanner/deeperDir")).sorted().toList(),
            is(List.of("deeper.txt")));
        assertThat(Arrays.stream(ResourceScanner.getResourceNames("")).toList(),
            is(Collections.emptyList()));
    }
    @Test void getResourceNamesRecursivelyShouldGetAllDeeperNames() {
        assertThat(Arrays.stream(ResourceScanner.getResourceNames("ResourceScanner/", true)).sorted().toList(),
            is(List.of("a.txt", "b.txt", "deeperDir/", "deeperDir/deeper.txt")));
        assertThat(Arrays.stream(ResourceScanner.getResourceNames("ResourceScanner/deeperDir", true)).sorted().toList(),
            is(List.of("deeper.txt")));
        assertThat(Arrays.stream(ResourceScanner.getResourceNames("", true)).sorted().toList(),
            is(Collections.emptyList()));
    }
    @Test void copyResourcesShouldCopy(@TempDir Path tempDir) {
        assertThat(ResourceScanner.copyResources("ResourceScanner", tempDir), is(true));
        assertThat(IOUtils.listDeep(tempDir).stream().map(path -> tempDir.relativize(path).toString()).sorted().toList(),
            is(List.of("a.txt", "b.txt", "deeperDir")));
    }
    @Test void copyResourcesShouldCopyRecursively(@TempDir Path tempDir) {
        assertThat(ResourceScanner.copyResources("ResourceScanner", tempDir, true), is(true));
        assertThat(IOUtils.listDeep(tempDir).stream().map(path -> tempDir.relativize(path).toString()).map(s->s.replace("\\","/")).sorted().toList(),
            is(List.of("a.txt", "b.txt", "deeperDir", "deeperDir/deeper.txt")));
    }

    private String toString(Resource rec) {
        return noThrow(rec::getURI)
            .map(URI::toString)
            .map(txt -> txt.replaceAll("^.*ResourceScanner/", ""))
            .orElse("");

    }
}