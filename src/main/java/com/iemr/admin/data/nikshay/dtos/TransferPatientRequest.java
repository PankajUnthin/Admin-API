package com.iemr.admin.data.nikshay.dtos;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Value;

/** POST /Api/Patients/TransferPatient body (from enrollment-flow.pdf – confirm on portal network tab). */
@Value
@Builder
public class TransferPatientRequest {

    @JsonProperty("PatientId")
    String patientId;

    @JsonProperty("DestinationHierarchyId")
    Long destinationHierarchyId;

    /** dd-MM-yyyy */
    @JsonProperty("TransferDate")
    String transferDate;

    @JsonProperty("Reason")
    String reason;
}
