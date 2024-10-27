package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class RestartingException extends MicroStarException {
    public RestartingException(String message) {
        super(HttpStatus.OK, message);
    }
    public RestartingException log() { super.log(); return this; }
}
