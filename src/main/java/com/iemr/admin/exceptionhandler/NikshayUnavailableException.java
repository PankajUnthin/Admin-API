package com.iemr.admin.exceptionhandler;

/** Portal down, timeout, I/O error or HTTP 5xx – transient. */
public class NikshayUnavailableException extends NikshayException {
    public NikshayUnavailableException(String message, Integer httpStatus, Throwable cause) {
        super(message, httpStatus, cause);
    }
}
