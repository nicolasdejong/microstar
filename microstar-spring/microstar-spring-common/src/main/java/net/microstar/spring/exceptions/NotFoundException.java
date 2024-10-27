package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class NotFoundException extends MicroStarException {
    public NotFoundException(String message) { super(HttpStatus.NOT_FOUND, message); }
    public NotFoundException log() { super.log(); return this; }
}
