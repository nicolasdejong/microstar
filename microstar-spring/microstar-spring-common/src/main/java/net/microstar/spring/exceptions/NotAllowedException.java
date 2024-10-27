package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class NotAllowedException extends MicroStarException {
    public NotAllowedException(String message) { super(HttpStatus.FORBIDDEN, message); }
    public NotAllowedException log() { super.log(); return this; }
}
