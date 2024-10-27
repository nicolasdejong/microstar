package net.microstar.tools.watchdog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import static net.microstar.common.util.Utils.sleep;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class MiniServerTest {
    MiniServer server;

    @BeforeEach void init() {
        server = new MiniServer(0).start();
        sleep(100); // server starts in thread
    }
    @AfterEach void cleanup() {
        server.stop();
    }

    @Test void shouldRespond() throws IOException {
        final URL url = URI.create("http://localhost:" + server.port() + "/foo/bar").toURL();
        final HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("GET");

        assertThat(con.getResponseCode(), is(200));
    }
}