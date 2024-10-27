package net.microstar.common.exceptions;

public class WrappedException extends RuntimeException {
    public WrappedException(Throwable toWrap) { super(toWrap); }

    public static RuntimeException wrap(Throwable t) {
        return t instanceof RuntimeException rt ? rt : new WrappedException(t);
    }
}
