package net.microstar.spring.logging;

import net.microstar.common.MicroStarConstants;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class LogHandlerTest {
    private static final DynamicPropertiesRef<LoggingProperties> props = DynamicPropertiesRef.of(LoggingProperties.class);

    private static String san(String in) {
        return in.replace("!!", props.get().sanitizedReplacement);
    }

    @Test void loggingShouldBeSanitized() {
        // This only sets the value for the clusterSecret when the MicroStarConstants class hasn't been
        // loaded yet. When running all tests this has already happened so the cluster secret will be "0"
        // which is ignored by the sanitizer. When running just this test, the secret will be set.
        System.setProperty("clusterSecret", "TEST-CLUSTER-SECRET");
        if(MicroStarConstants.CLUSTER_SECRET.length() > 1) assertThat(LogHandler.sanitize("@" + MicroStarConstants.CLUSTER_SECRET + "@"), is(san("@!!@")));

        assertThat(LogHandler.sanitize("foo bar password: my_pw and secret=my_secret"), is(san("foo bar password: !! and secret=!!")));
        assertThat(LogHandler.sanitize("name: admin\nadminPassword: admin\netc"), is(san("name: admin\nadminPassword: !!\netc")));
    }
}