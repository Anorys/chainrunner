package com.anorys.chainrunner.config;

/** The configuration cannot be read or is invalid; the message says where and why. */
public class ConfigException extends RuntimeException {

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
