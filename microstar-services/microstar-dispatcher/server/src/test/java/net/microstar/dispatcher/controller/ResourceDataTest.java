package net.microstar.dispatcher.controller;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.core.io.Resource;

import java.net.URI;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.when;

class ResourceDataTest {

    private static Resource mockResourceForUri(String uri) {
        final Resource resource = Mockito.mock(Resource.class);
        noThrow(() -> when(resource.getURI()).thenReturn(URI.create(uri)));
        return resource;
    }

    @Test void resourceNameOf() {
        assertThat(ResourceData.resourceNameOf(mockResourceForUri(
                "jar:file:/D/some/file.jar!/BOOT-INF/classes!/public/dashboard/vite.svg")),
            is("/dashboard/vite.svg"));

        assertThat(ResourceData.resourceNameOf(mockResourceForUri(
                "file:/D/some/target/public/dashboard/vite.svg")),
            is("/dashboard/vite.svg"));

        assertThat(ResourceData.resourceNameOf(mockResourceForUri(
                "file:/some/public/target/public/dashboard/vite.svg")),
            is("/dashboard/vite.svg"));
    }
}