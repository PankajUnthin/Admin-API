package com.iemr.admin.exceptionhandler;

/** Portal answered but rejected the request (Success:false or HTTP 4xx). */
public class NikshayApiException extends NikshayException {
    public NikshayApiException(String message, Integer httpStatus) {
        super(message, httpStatus, null);
    }
}
