package net.microstar.common.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

import static net.microstar.common.model.ServiceId.DEFAULT_GROUP_NAME;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServiceIdTest {
    @Test void initShouldCorrectlySplitParts() {
        assertSplit("service", DEFAULT_GROUP_NAME, "service", "0");
        assertSplit("__service__", DEFAULT_GROUP_NAME, "service", "0");
        assertSplit("group_service-1", "group", "service", "1");
        assertSplit("group/service/1", "group", "service", "1");
        assertSplit("group_service_1", "group", "service", "1");
        assertSplit("group-name_service-name_1", "group-name", "service-name", "1");
        assertSplit("group-name_service-name-1", "group-name", "service-name", "1");
        assertSplit("service-name-1", DEFAULT_GROUP_NAME, "service-name", "1");
        assertSplit("service-name-1.0.1234", DEFAULT_GROUP_NAME, "service-name", "1.0.1234");

        assertSplit("/some/dir/serviceName.jar!etc!foo","main", "serviceName", "0");
        assertSplit("/some/dir/group_serviceName-123456.jar!etc","group", "serviceName", "123456");
        assertSplit("D:/some/windows/path/serviceName-1.0-SNAPSHOT.jar!/BOOT-INF/classes!/", "main", "serviceName", "1.0-SNAPSHOT");
        assertSplit("C:\\some\\windows\\path\\group_serviceName-123456.jar","group", "serviceName", "123456");
        assertSplit("serviceName","main", "serviceName", "0");
        assertSplit("serviceName.jar","main", "serviceName", "0");
        assertSplit(("group_serviceName-20220505112233"),"group", "serviceName", "20220505112233");
        assertSplit("group_serviceName_1.2.3.20220505112233","group", "serviceName", "1.2.3.20220505112233");
        assertSplit("some/deeper/path/foo.jar","main", "foo", "0");
        assertSplit(ServiceId.of("some/dir/group_serviceName-123456.jar!etc").combined, "group", "serviceName", "123456");
        assertSplit("serviceName-123","main", "serviceName", "123");
        assertSplit("serviceName-123.jar","main", "serviceName", "123");
        assertSplit("serviceName.jar","main", "serviceName", "0");
        assertSplit("serviceName-1.0-SNAPSHOT.jar","main", "serviceName", "1.0-SNAPSHOT");

        assertSplit("some-branch-name_some-service-name_123-SNAPSHOT-beta.jar","some-branch-name", "some-service-name", "123-SNAPSHOT-beta");
        assertSplit("some-branch-name_some-service-name-123-SNAPSHOT-beta.jar","some-branch-name", "some-service-name", "123-SNAPSHOT-beta");

        assertSplit("a/b", "a", "b", "0");
        assertSplit("a/b/c", "a", "b", "c");
        assertSplit("a//b//c", "a", "b", "c");
        assertSplit("a/b/c/d", DEFAULT_GROUP_NAME, "d", "0");

        assertThrows(IllegalArgumentException.class, () -> ServiceId.of(""));
        assertThrows(IllegalArgumentException.class, () -> ServiceId.of("__"));
        assertThrows(IllegalArgumentException.class, () -> ServiceId.of("/"));
        assertThrows(IllegalArgumentException.class, () -> ServiceId.of("//"));
        assertThrows(IllegalArgumentException.class, () -> ServiceId.of("/ /"));
    }
    @Test void initFromClassShouldAlsoWorkFromIDE() {
        assertThat(ServiceId.of("/C:/dev/some-service/").name, is("some-service")); // group and version depend on branch & module version
    }
    @Test void initShouldSanitizeInput() {
        assertThat(ServiceId.of("a/b", "c/d", "9/3"), equalsId("a-b", "c-d", "9-3"));
        assertThat(ServiceId.of("", "^name^", ""), equalsId("main", "name", "0"));
        //noinspection ConstantConditions -- be naughty to test sanitize
        assertThat(ServiceId.of(null, "name", null), equalsId("main", "name", "0"));
        assertThat(ServiceId.of("/name/"), equalsId("main", "name", "0"));

        assertThrows(IllegalArgumentException.class, () -> ServiceId.of("", " ", ""));
    }
    @Test void isNewerShouldCompareCorrectly() {
        assertTrue(ServiceId.of("a_b-1.6").isNewerThan(ServiceId.of("a_b-1.5")));
        assertTrue(ServiceId.of("a_b-1.5.3").isNewerThan(ServiceId.of("a_b-1.5.2")));
        assertTrue(ServiceId.of("1.2").isNewerThan(ServiceId.of("1.2-SNAPSHOT")));
    }
    @Test void jacksonShouldOnlyUseCombined() throws JsonProcessingException {
        final String idGroup   = "a";
        final String idName    = "b";
        final String idVersion = "123";
        final String idString  = String.join("/", idGroup, idName, idVersion);
        final ObjectMapper objectMapper = new ObjectMapper();
        final String  json = objectMapper.writeValueAsString(ServiceId.of(idString));
        final ServiceId id = objectMapper.readValue(json, ServiceId.class);

        assertThat(json, is("\"" + idString + "\""));
        assertThat(id.combined, is(idString));
        assertThat(id.group, is(idGroup));
        assertThat(id.name, is(idName));
        assertThat(id.version, is(idVersion));
    }
    @Test void branchNameShouldBeSanitized() {
        assertThat(ServiceId.branchToGroupName("some/origin/path/branch-name"), is("branch-name"));
        assertThat(ServiceId.branchToGroupName("STORY-1234-longer-text"), is("STORY-1234"));
        assertThat(ServiceId.branchToGroupName("STORY1234-longer-text"), is("STORY1234"));
        assertThat(ServiceId.branchToGroupName("STORY1234"), is("STORY1234"));
        assertThat(ServiceId.branchToGroupName("branch-name-that-is-too-long"), is("branch-name"));
        assertThat(ServiceId.branchToGroupName("doing-some-i18n-stuff"), is("doing-some"));
        assertThat(ServiceId.branchToGroupName("i18n-stuff"), is("i18n-stuff"));
        assertThat(ServiceId.branchToGroupName("i18n-some-more-stuff"), is("i18n-some-more"));
        assertThat(ServiceId.branchToGroupName("abcdefg-verylongnamewithoutdelimiters"), is("abcdefg-verylon"));
    }

    private static void assertSplit(String idText, String group, String name, String version) {
        assertThat(ServiceId.of(idText), equalsId(group, name, version));
    }
    private static Matcher<ServiceId> equalsId(String group, String name, String version) {
        return new BaseMatcher<>() {
            @Override
            public boolean matches(Object o) {
                if(!(o instanceof final ServiceId si)) return false;
                return si.group.equals(group)
                    && si.name.equals(name)
                    && si.version.equals(version);
            }

            @Override
            public void describeTo(Description description) {
                description.appendText("<" + ServiceId.of(group, name, version) + ">");
            }

            @Override
            public void describeMismatch(Object item, Description description) {
                description.appendValue(item);
            }
        };
    }
}
