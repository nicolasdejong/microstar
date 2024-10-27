package net.microstar.dispatcher.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.microstar.spring.authorization.UserToken;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class RelayRequestTest {

    @Test void jacksonShouldBeAbleToSerialize() throws JsonProcessingException {
        final ObjectMapper objectMapper = new ObjectMapper();

        final RelayRequest req = RelayRequest.builder()
            .method("GET")
            .serviceName("serviceName")
            .servicePath("servicePath")
            .payload("payload")
            .star("star")
            .userToken(UserToken.SERVICE_TOKEN)
            .includeLocalStar(false)
            .binary(true)
            .param("p1", "p1val")
            .param("p2", "p2val")
            .build();

        final String json = objectMapper.writeValueAsString(req);
        final RelayRequest copy = objectMapper.readValue(json, RelayRequest.class);

        assertThat(copy.toString(), is(req.toString()));
    }

    @Test void noParamsShouldLeadToEmptyParamsMap() {
        assertThat(RelayRequest.forGet("foo").build().params, notNullValue());
    }
    @Test void paramsShouldBeSet() {
        assertThat(RelayRequest.forGet("foo").param("a", "aVal").build().params.get("a"), is("aVal"));
    }
    @Test void settingNullParamKeyShouldBeIgnored() {
        assertThat(RelayRequest.forGet("foo").param(null, "aVal").build().params.isEmpty(), is(true));
    }
    @Test void settingNullParamValueShouldRemoveIt() {
        assertThat(RelayRequest.forGet("foo").param("a", "aVal").param("a", null).build().params.containsKey("a"), is(false));
    }
}