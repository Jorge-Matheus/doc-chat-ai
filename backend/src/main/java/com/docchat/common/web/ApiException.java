package com.docchat.common.web;

import org.springframework.http.HttpStatus;

/**
 * An expected failure with its HTTP status and machine-readable code. The message is sent to the
 * client, so it must not contain internal details.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String clientMessage) {
        super(clientMessage);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
