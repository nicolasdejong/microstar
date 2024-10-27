package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class TimeoutException extends MicroStarException {
    public TimeoutException(String message) {
        super(HttpStatus.GATEWAY_TIMEOUT, message);
    }
    public TimeoutException log() { super.log(); return this; }
}
