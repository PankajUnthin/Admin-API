package com.iemr.admin.data.nikshay;

public enum NikshayTransferStatus {

    TRANSFERRED,
    /** Config/mapping problem (PHI or device). Re-picked every run. */
    HELD,
    /** Call made and rejected, or portal down, or login rejected. See failure reason for retry policy. */
    FAILED,
    /** Supervisor transferred manually on the portal. Terminal. */
    MANUAL_DONE
}
