package com.sangyunpark99.pgserver.common.exception;

import lombok.Getter;

@Getter
public class PgException extends RuntimeException {
    private final ErrorCode errorCode;

    public PgException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public PgException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public PgException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }
}
