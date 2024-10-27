package net.microstar.tools.watchdog;

class ExitException extends RuntimeException {
    final int code;

    ExitException(int code) {
        this.code = code;
    }
}
