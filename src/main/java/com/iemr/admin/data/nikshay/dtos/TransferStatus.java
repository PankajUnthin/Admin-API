package com.iemr.admin.data.nikshay.dtos;

/** Values written to tb_suspected.nikshay_transfer_status / nikshay_transfer_failure_reason. */
public final class TransferStatus {

    private TransferStatus() {}

    // nikshay_transfer_status
    public static final String TRANSFERRED = "TRANSFERRED";
    /** configuration / data missing – retried on every run, no attempt counted */
    public static final String HELD = "HELD";
    /** portal call failed – retried until max-attempts */
    public static final String FAILED = "FAILED";

    // reason codes (prefix of nikshay_transfer_failure_reason)
    public static final String RS_NIKSHAY_ID_MISSING = "NIKSHAY_ID_MISSING";
    public static final String RS_VILLAGE_MISSING = "VILLAGE_MISSING";
    public static final String RS_DEVICE_NOT_MAPPED = "DEVICE_NOT_MAPPED";
    public static final String RS_MULTIPLE_DEVICES = "MULTIPLE_DEVICES_MAPPED";
    public static final String RS_DEVICE_CONFIG_INVALID = "DEVICE_CONFIG_INVALID";
    public static final String RS_PHI_NOT_MAPPED = "DESIGNATED_PHI_NOT_MAPPED";
    public static final String RS_MULTIPLE_PHI = "MULTIPLE_PHI_MAPPED";
    public static final String RS_DESTINATION_MISSING = "DESTINATION_HIERARCHY_MISSING";
    public static final String RS_DEVICE_LOGIN_FAILED = "DEVICE_LOGIN_FAILED";
    public static final String RS_PORTAL_UNAVAILABLE = "PORTAL_UNAVAILABLE";
    public static final String RS_TRANSFER_REJECTED = "TRANSFER_REJECTED";
}
