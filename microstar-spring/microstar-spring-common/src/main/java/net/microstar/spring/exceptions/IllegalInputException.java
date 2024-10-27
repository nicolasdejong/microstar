package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

@Slf4j
public class IllegalInputException extends MicroStarException {
    public IllegalInputException(String message) { super(HttpStatus.BAD_REQUEST, message); }
    public IllegalInputException log() { super.log(); return this; }
}
