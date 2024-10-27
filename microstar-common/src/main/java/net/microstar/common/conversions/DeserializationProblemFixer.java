package net.microstar.common.conversions;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.DeserializationProblemHandler;
import com.fasterxml.jackson.databind.deser.ValueInstantiator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

public class DeserializationProblemFixer extends DeserializationProblemHandler {

    @Override
    public Object handleMissingInstantiator(DeserializationContext ctxt, Class<?> instClass, ValueInstantiator valueInsta, JsonParser p, String msg) throws IOException {
        // Assume the collection is of <String>, will throw otherwise
        return deserializeStringAsCollection(instClass, p.getText());
    }

    @Override
    public Object handleUnexpectedToken(DeserializationContext context, JavaType targetType, JsonToken token, JsonParser parser, String failureMsg) throws IOException {
        if (token == JsonToken.VALUE_STRING && targetType.isCollectionLikeType()) {
            return deserializeAsCollection(targetType, parser);
        }
        return super.handleUnexpectedToken(context, targetType, token, parser, failureMsg);
    }


    private static Object deserializeStringAsCollection(Class<?> type, String value) {
        final String[] parts = value.split("\\s*,\\s*");
        final Collection<Object> collection =
            Set.class.isAssignableFrom(type) ? new LinkedHashSet<>(parts.length) : new ArrayList<>(parts.length);

        collection.addAll(Arrays.asList(parts));
        return collection;
    }
    private static Object deserializeAsCollection(JavaType collectionType, JsonParser parser) throws IOException {
        final String[] values = readValues(parser);

        final ObjectMapper mapper = (ObjectMapper) parser.getCodec();
        final JavaType itemType = collectionType.getContentType();

        final Collection<Object> collection;
        if(collectionType.getRawClass().isAssignableFrom(Set.class)) {
            collection = new LinkedHashSet<>();
        } else {
            collection = new ArrayList<>();
        }
        for (final String value : values) {
            collection.add(convertToItemType(mapper, itemType, value));
        }
        return collection;
    }
    private static Object convertToItemType(ObjectMapper mapper, JavaType contentType, String value) throws IOException {
        final String json = "\"" + value.trim() + "\"";

        return mapper.readValue(json, contentType);
    }
    private static String[] readValues(JsonParser p) throws IOException {
        return p.getText().split(",");
    }
}
