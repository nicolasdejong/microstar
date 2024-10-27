package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class MissingRequiredArgumentsException extends MicroStarException {
    public MissingRequiredArgumentsException(String message) { super(HttpStatus.BAD_REQUEST, message); }
    public MissingRequiredArgumentsException log() { super.log(); return this; }
}
