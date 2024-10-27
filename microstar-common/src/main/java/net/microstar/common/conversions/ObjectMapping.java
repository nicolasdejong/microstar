package net.microstar.common.conversions;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.guava.GuavaModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;

import java.util.function.UnaryOperator;

/** Single place to configure the Jackson object mapper */
public final class ObjectMapping {
    private ObjectMapping() {/*singleton*/}

    private static ObjectMapper objectMapper = JsonMapper.builder()
        .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_PROPERTIES)
        .enable(MapperFeature.AUTO_DETECT_FIELDS)
        .build()
        .registerModule(new ParameterNamesModule())
        .registerModule(new Jdk8Module())
        .registerModule(new JavaTimeModule())
        .registerModule(new GuavaModule())
        .registerModule(new MoreConversionsModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false)
        .setSerializationInclusion(JsonInclude.Include.NON_ABSENT)
        .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)
        .addHandler(new DeserializationProblemFixer())
        ;

    public static ObjectMapper get() { return objectMapper; }

    public static ObjectMapper mutate(UnaryOperator<ObjectMapper> mutator) {
        return (objectMapper = mutator.apply(objectMapper)); // NOSONAR -- single line
    }
}
