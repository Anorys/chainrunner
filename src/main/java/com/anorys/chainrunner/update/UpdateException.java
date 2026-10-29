package com.anorys.chainrunner.update;

/** The update cannot be done; the message says why and what to do instead. */
public class UpdateException extends RuntimeException {

    public UpdateException(String message) {
        super(message);
    }

    public UpdateException(String message, Throwable cause) {
        super(message, cause);
    }
}
