package net.microstar.spring.exceptions;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class FatalException extends MicroStarException {
    public FatalException(String message) { super(message); }
    public FatalException(String message, Exception cause) { super(message, cause); }
    public FatalException log() { super.log(); return this; }
}
