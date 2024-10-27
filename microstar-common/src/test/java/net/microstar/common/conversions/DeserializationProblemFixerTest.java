package net.microstar.common.conversions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.extern.jackson.Jacksonized;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static net.microstar.common.conversions.DeserializationProblemFixerTest.YesNo.NO;
import static net.microstar.common.conversions.DeserializationProblemFixerTest.YesNo.YES;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

class DeserializationProblemFixerTest {

    enum YesNo { YES, NO }

    @Builder @Jacksonized
    public static class TestClass {
        public final List<String> someList;
        public final Set<String> someSet;
        public final List<YesNo> answers;
    }

    private static ObjectMapper getObjectMapper() {
        return new ObjectMapper()
            .addHandler(new DeserializationProblemFixer());
    }

    @Test void fixerShouldConvertStringToList() throws JsonProcessingException {
        final ObjectMapper objectMapper = getObjectMapper();

        final TestClass tc1 = objectMapper.readerFor(TestClass.class).readValue("{`someList`: [`a`,`b`,`c`,`d`]}".replace("`", "\""));
        assertThat(tc1.someList, is(List.of("a", "b", "c", "d")));

        final TestClass tc2 = objectMapper.readerFor(TestClass.class).readValue("{`someList`:`a,b,c,d`}".replace("`", "\""));
        assertThat(tc2.someList, is(List.of("a", "b", "c", "d")));
    }

    @Test void fixerShouldConvertStringToSet() throws JsonProcessingException {
        final ObjectMapper objectMapper = getObjectMapper();

        final TestClass tc1 = objectMapper.readerFor(TestClass.class).readValue("{`someSet`:[`k`,`l`,`m`,`n`]}".replace("`", "\""));
        assertThat(tc1.someSet, is(Set.of("k", "l", "m", "n")));

        final TestClass tc2 = objectMapper.readerFor(TestClass.class).readValue("{`someSet`:`k,l,  m, n`}".replace("`", "\""));
        assertThat(tc2.someSet, is(Set.of("k", "l", "m", "n")));
    }

    @Test void fixerShouldConvertStringToCustomType() throws JsonProcessingException {
        final ObjectMapper objectMapper = getObjectMapper();

        final TestClass tc2 = objectMapper.readerFor(TestClass.class).readValue("{`answers`:`YES,NO,NO, YES`}".replace("`", "\""));
        assertThat(tc2.answers, is(List.of(YES, NO, NO, YES)));
    }
}