package net.microstar.spring.mvc;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.microstar.common.model.ServiceId;
import net.microstar.common.util.Reflection;
import net.microstar.spring.ExceptionProperties;
import net.microstar.spring.settings.DynamicPropertiesRef;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
@ControllerAdvice
public class ExceptionHandlers {
    private static final DynamicPropertiesRef<ExceptionProperties> props = DynamicPropertiesRef.of(ExceptionProperties.class);
    private static class ExceptionResponseEntity extends ResponseEntity<Map<String,String>> {
        ExceptionResponseEntity(Map<String,String> map, HttpHeaders headers, HttpStatusCode status) {
            super(map, headers, status);
        }
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    public ExceptionResponseEntity handleRestExceptions(HttpStatusCodeException ex) {
        return createEntity(ex.getStatusCode(), ex);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ExceptionResponseEntity handleFatalExceptions(ResponseStatusException ex) {
        return createEntity(ex.getStatusCode(), ex);
    }

    @ExceptionHandler(Exception.class)
    public ExceptionResponseEntity handleOtherExceptions(Exception ex) {
        return createEntity(HttpStatus.INTERNAL_SERVER_ERROR, ex);
    }

    private static void log(HttpStatusCode status, Object... info) {
        if(!props.get().errorStatusNotToLog.contains(status.value())) {
            log.error(status + " {}", String.join(",", Stream.of(info).map(Objects::toString).toList()));
        }
    }

    private static ExceptionResponseEntity createEntity(HttpStatusCode status, Exception ex) {
        return createEntity(status,
            "type", ex.getClass().getSimpleName(),
            "error", ex.getMessage(),
            "stacktrace", Optional.of(ex)
                .filter(e -> props.get().errorStatusToSendLogStackTrace.contains(status.value()))
                .map(e -> {
                    final String stackTrace = props.get().errorStatusTruncateStackTrace
                        ? truncateStacktrace(Reflection.stackTraceToString(ex))
                        : Reflection.stackTraceToString(ex);
                    log.error(ex.getClass().getName() + (ex.getMessage() == null ? "" : (": " + ex.getMessage())) + "\n" + stackTrace);
                    return stackTrace;
                }).orElse(null)
        );
    }
    private static String truncateStacktrace(String trace) {
        final int end = trace.indexOf("at java.base/java.lang.reflect.Method.invoke");
        final String truncated1 = (end < 0 ? trace : trace.substring(0, end));
        return truncated1
            .replaceAll("\\s+at java.base/jdk.internal.reflect\\.[^\n]+", "\n")
            .replaceAll("\\s+$", "")
            ;
    }

    private static ExceptionResponseEntity createEntity(HttpStatusCode status, String... infoTuples) {
        if(Stream.of(infoTuples).noneMatch("stacktrace"::equals) && !props.get().errorStatusNotToLog.contains(status.value())) {
            // stack already logged in createEntity(status, Exception)
            log(status, Stream.of(infoTuples).map(t -> t == null ? "[null]" : t).toArray(Object[]::new));
        }
        final ServiceId sid = ServiceId.get();
        return new ExceptionResponseEntity(
            Stream.of(orderedFlatMap(
                "name",         sid.name,
                "serviceGroup", sid.group,
                "version",      sid.version
            ), orderedFlatMap(infoTuples))
                .flatMap(map -> map.entrySet().stream())
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (map1,map2) -> map1, LinkedHashMap::new)),
            createHeaders(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE),
            status);
    }

    public static HttpHeaders createHeaders(String... tuples) {
        final HttpHeaders headers = new HttpHeaders();
        for(int i=0; i<tuples.length; i+=2) headers.set(tuples[i], tuples[i+1]);
        return headers;
    }
    public static Map<String,String> orderedFlatMap(String... tuples) {
        final Map<String,String> map = new LinkedHashMap<>();
        for(int i=0; i<tuples.length; i+=2) map.put(tuples[i], tuples[i+1]);
        return map;
    }
}
