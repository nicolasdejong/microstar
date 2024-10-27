package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class NotAuthorizedException extends MicroStarException {
    public NotAuthorizedException(String message) { super(HttpStatus.UNAUTHORIZED, message); }
    public NotAuthorizedException log() { super.log(); return this; }
}
