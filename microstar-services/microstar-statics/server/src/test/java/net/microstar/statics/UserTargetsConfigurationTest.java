package net.microstar.statics;

import net.microstar.common.datastore.BlockingDataStore;
import net.microstar.spring.DataStores;
import net.microstar.spring.settings.DynamicPropertiesManager;
import net.microstar.spring.settings.PropsMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.microstar.common.util.ImmutableUtil.mapOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

@SuppressWarnings("squid:S5976") // Tests are easier to read without using parameterized input values
class UserTargetsConfigurationTest {

    @AfterEach public void cleanup() {
        DynamicPropertiesManager.clearAllState();
    }

    @Test void updateShouldReplaceExistingUserTargetsDeep() {
        final String initial = """
            app: # first line
              config:
                statics:
                  # Remarks should be ignored
                  # userTargets:
                  userTargets:
                    a: [/a/]
                    b: [/b/]
                etc: 123
            """;
        final String expected = """
            app: # first line
              config:
                statics:
                  # Remarks should be ignored
                  # userTargets:
                  userTargets:
                    b: [ /bChanged/ ]
                    c: [ /cAdded/ ]

                etc: 123
            """;
        final Map<String, List<String>> newUserTargets = mapOf("b", List.of("/bChanged/"), "c", List.of("/cAdded/"));
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldReplaceExistingUserTargetsDeep2() {
        final String initial = """
            app: # first line
              config:
                statics:
                  # Remarks should be ignored
                  # userTargets:
                  userTargets:
                    a: [/a/]
                    b: [/b/]

                etc: 123
            """;
        final String expected = """
            app: # first line
              config:
                statics:
                  # Remarks should be ignored
                  # userTargets:
                  userTargets:
                    b: [ /bChanged/ ]
                    c: [ /cAdded/ ]

                etc: 123
            """;
        final Map<String,List<String>> newUserTargets = mapOf("b", List.of("/bChanged/"), "c", List.of("/cAdded/"));
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldReplaceExistingUserTargetsFlat() {
        final String initial = """
            app.config.statics.userTargets:
              a: [/a/]
              b: [/b/]
            """;
        final String expected = """
            app.config.statics.userTargets:
              b: [ /bChanged/ ]
              c: [ /cAdded/ ]
            """;
        final Map<String,List<String>> newUserTargets = mapOf("b", List.of("/bChanged/"), "c", List.of("/cAdded/"));
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldReplaceExistingUserTargetsFlat2() {
        final String initial = """
            app.config:
              statics.userTargets:
                a: [/a/]
                b: [/b/]
            """;
        final String expected = """
            app.config:
              statics.userTargets:
                b: [ /bChanged/ ]
                c: [ /cAdded/ ]
            """;
        final Map<String,List<String>> newUserTargets = mapOf("b", List.of("/bChanged/"), "c", List.of("/cAdded/"));
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldAddUserTargetsIfNotYetSet() {
        final String initial = """
            app: # first line
              config:
                statics:
                  etc: 123
            """;
        final String expected = """
            app: # first line
              config:
                statics:
                  etc: 123

            app.config.statics.userTargets:
              b: [ /bChanged/ ]
              c: [ /cAdded/ ]
            """;
        final Map<String,List<String>> newUserTargets = mapOf("b", List.of("/bChanged/"), "c", List.of("/cAdded/"));
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldSupportMultipleTargets() {
        final String initial = """
            app.config.statics.userTargets:
              b: [/b/]
              c: [/c/]
            """;
        final String expected = """
            app.config.statics.userTargets:
              b: [ /b1/, /b2/ ]
              c: [ /c1/ ]
            """;
        final Map<String,List<String>> newUserTargets = mapOf("b", List.of("/b1/","/b2/"), "c", List.of("/c1/"));
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldRemoveTargetsWhenEmpty() {
        final String initial = """
            app.config.statics.userTargets:
              b: [/b/]
              c: [/c/]
            """;
        final String expected = """
            app.config.statics.userTargets: {}
            """;
        final Map<String,List<String>> newUserTargets = Collections.emptyMap();
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }
    @Test void updateShouldRemoveTargetsWhenEmpty2() {
        final String initial = """
            app.config.statics:
              foo: bar
              userTargets:
                b: [/b/]
                c: [/c/]
              next: item
            """;
        final String expected = """
            app.config.statics:
              foo: bar
              userTargets: {}

              next: item
            """;
        final Map<String,List<String>> newUserTargets = Collections.emptyMap();
        assertThat(new UserTargetsConfiguration(null).updateSettingsForNewUserTargets(initial, newUserTargets).orElse(""), is(expected));
    }

    @Test void getTargetShouldGetExistingResource() {
        DynamicPropertiesManager.setExternalSettings(PropsMap.fromSettingsMap(Map.of(
            "microstar.dataStores." + DataService.DATASTORE_NAME + ".type", "memory",
            "app.config.statics.userTargets", Map.of("default", List.of("/def1/", "/def2/"))
        )));
        final BlockingDataStore store = BlockingDataStore.forStore(DataStores.get(DataService.DATASTORE_NAME));
        store.write("def1/index.html", "def1");
        store.write("def2/index2.html", "def2");
        assertThat(new UserTargetsConfiguration(null).getTargetForUser("user", "index.html"), is("/def1/"));
        assertThat(new UserTargetsConfiguration(null).getTargetForUser("user", "index2.html"), is("/def2/"));
    }
}