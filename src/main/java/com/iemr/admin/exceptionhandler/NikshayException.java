package com.iemr.admin.exceptionhandler;

/** Base type for every Ni-kshay failure. */
public class NikshayException extends RuntimeException {

    private final Integer httpStatus;

    public NikshayException(String message, Integer httpStatus, Throwable cause) {
        super(message, cause);
        this.httpStatus = httpStatus;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }
}
