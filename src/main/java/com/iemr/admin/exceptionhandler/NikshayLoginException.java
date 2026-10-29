package com.iemr.admin.exceptionhandler;

/** Credentials rejected (wrong / expired password, unknown user). Never retried – lockout risk. */
public class NikshayLoginException extends NikshayException {
    public NikshayLoginException(String message, Integer httpStatus) {
        super(message, httpStatus, null);
    }
}
