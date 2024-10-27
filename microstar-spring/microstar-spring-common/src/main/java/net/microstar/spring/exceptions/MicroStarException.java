package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

import static net.microstar.common.util.ExceptionUtils.noThrow;
import static net.microstar.common.util.Reflection.isJavaClass;

@Slf4j
public class MicroStarException extends ResponseStatusException {
    public MicroStarException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
    public MicroStarException(HttpStatus status, String message) {
        super(status, message);
    }
    public MicroStarException(String message, Exception cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message, cause);
    }
    public MicroStarException(HttpStatus status, String message, Exception cause) {
        super(status, message, cause);
    }
    public MicroStarException log() {
        // Try to use the logger of the calling class
        final org.slf4j.Logger logger = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE).walk(stream -> stream
            .map(StackWalker.StackFrame::getDeclaringClass)
            .filter(clazz -> !Exception.class.isAssignableFrom(clazz))
            .filter(clazz -> !isJavaClass(clazz))
            .flatMap(clazz -> Arrays.stream(clazz.getDeclaredFields())
                .filter(field -> field.getType().isAssignableFrom(org.slf4j.Logger.class))
                .flatMap(field ->
                    noThrow(() -> {
                        field.setAccessible(true);
                        field.trySetAccessible();
                        return (org.slf4j.Logger)field.get(null);
                    }).stream()
                )
                .findFirst()
                .stream()
            )
            .findFirst()
            .orElse(log)
        );
        logger.error(getMessage());
        return this;
    }
}
