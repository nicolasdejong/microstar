package net.microstar.common.conversions;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.DeserializationConfig;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.Deserializers;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.Serializers;
import net.microstar.common.throwingfunctionals.ThrowingFunction;
import net.microstar.common.throwingfunctionals.ThrowingTriConsumer;
import net.microstar.common.util.ByteSize;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static net.microstar.common.util.ExceptionUtils.noThrow;

public class MoreConversionsModule extends SimpleModule {
    private static final JsonDeserializer<Duration> DURATION_DESERIALIZER = createJsonDeserializer(Duration.class, DurationString::toDuration);
    private static final JsonSerializer<Duration>   DURATION_SERIALIZER   = createJsonSerializer((value, gen, ser) -> gen.writeString(DurationString.toString(value)));

    private static final JsonDeserializer<ByteSize> BYTE_SIZE_DESERIALIZER = createJsonDeserializer(ByteSize.class, ByteSizeString::fromString);
    private static final JsonSerializer<ByteSize>   BYTE_SIZE_SERIALIZER   = createJsonSerializer((value, gen, ser) -> gen.writeString(ByteSizeString.toString(value)));

    private static final JsonDeserializer<LocalDateTime> LOCAL_DATE_TIME_DESERIALIZER = createJsonDeserializer(LocalDateTime.class, timeText -> LocalDateTime.from(DateTimeFormatter.ISO_LOCAL_DATE_TIME.parse(timeText)));
    private static final JsonSerializer<LocalDateTime>   LOCAL_DATE_TIME_SERIALIZER   = createJsonSerializer((value, gen, ser) -> gen.writeString(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(value)));

    private static <T> JsonDeserializer<T> createJsonDeserializer(Class<T> type, ThrowingFunction<String,T> converter) {
        return new JsonDeserializer<T>() {
            @Override
            public T deserialize(JsonParser p, DeserializationContext context) throws IOException {
                final String text = noThrow(() -> p.getValueAsString()).orElse(""); // NOSONAR -- false positive: p::getValueAsString is ambiguous
                return noThrow(() -> converter.apply(text))
                    .orElseThrow(() -> new JsonMappingException(p, "Unable to create " + type.getSimpleName() + " from: " + text));
            }
        };
    }
    private static <T> JsonSerializer<T> createJsonSerializer(ThrowingTriConsumer<T,JsonGenerator,SerializerProvider> converter) {
        return new JsonSerializer<T>() {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                converter.accept(value, gen, serializers);
            }
        };
    }


    @Override
    public void setupModule(SetupContext context) {
        super.setupModule(context);
        context.addDeserializers(new Deserializers.Base() {
            @Override @Nullable
            public JsonDeserializer<?> findBeanDeserializer(JavaType type,
                                                            DeserializationConfig config, BeanDescription beanDesc) {
                if(Duration.class.isAssignableFrom(type.getRawClass())) return DURATION_DESERIALIZER;
                if(ByteSize.class.isAssignableFrom(type.getRawClass())) return BYTE_SIZE_DESERIALIZER;
                if(LocalDateTime.class.isAssignableFrom(type.getRawClass())) return LOCAL_DATE_TIME_DESERIALIZER;
                return null;
            }
        });
        context.addSerializers(new Serializers.Base() {
            @Override @Nullable
            public JsonSerializer<?> findSerializer(SerializationConfig config, JavaType type,
                                                    BeanDescription beanDesc) {
                if(Duration.class.isAssignableFrom(type.getRawClass())) return DURATION_SERIALIZER;
                if(ByteSize.class.isAssignableFrom(type.getRawClass())) return BYTE_SIZE_SERIALIZER;
                if(LocalDateTime.class.isAssignableFrom(type.getRawClass())) return LOCAL_DATE_TIME_SERIALIZER;
                return null;
            }
        });
    }
}
