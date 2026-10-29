package com.iemr.admin.exceptionhandler;

/** Thrown when a transfer-out run is triggered while another one is still running. */
public class TransferOutInProgressException extends RuntimeException {
    public TransferOutInProgressException(String message) {
        super(message);
    }
}
