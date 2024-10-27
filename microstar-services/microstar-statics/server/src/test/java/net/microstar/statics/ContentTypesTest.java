package net.microstar.statics;

import net.microstar.spring.ContentTypes;
import net.microstar.spring.application.AppSettings;
import net.microstar.spring.settings.DynamicPropertiesManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class ContentTypesTest {

    @AfterEach void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void ofExt() {
        assertThat(ContentTypes.typeOfName("html"), is("text/html"));
        assertThat(ContentTypes.typeOfName("filename.html"), is("text/html"));
        assertThat(ContentTypes.typeOfName("some/path/some/file.html"), is("text/html"));
        assertThat(ContentTypes.typeOfName("pdf"), is("application/pdf"));
        assertThat(ContentTypes.typeOfName("unknown"), is("application/octet-stream"));

        assertThat(ContentTypes.mediaTypeOfName("some/path/some/file.html"), is(MediaType.TEXT_HTML));
        assertThat(ContentTypes.mediaTypeOfName("file.yaml").toString(), is("application/yaml"));

        final String appSettingsYaml = "contentTypes:\n  defaultType: foo/bar\n  extToType:\n    html: text/plain";
        AppSettings.handleExternalSettingsText(appSettingsYaml);

        assertThat(ContentTypes.typeOfName("html"), is("text/plain"));
        assertThat(ContentTypes.typeOfName("unknown"), is("foo/bar"));
    }
}
