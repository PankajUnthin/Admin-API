package com.iemr.admin.exceptionhandler;

/** Session cookie no longer accepted (401/403 or redirect to SSO). Caller logs in again once. */
public class NikshaySessionExpiredException extends NikshayException {
    public NikshaySessionExpiredException(String message, Integer httpStatus) {
        super(message, httpStatus, null);
    }
}
