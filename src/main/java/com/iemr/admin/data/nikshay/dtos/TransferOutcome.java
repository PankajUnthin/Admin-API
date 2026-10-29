package com.iemr.admin.data.nikshay.dtos;

import lombok.Builder;
import lombok.Value;

/** What happened to one beneficiary in this run (returned to the caller). */
@Value
@Builder
public class TransferOutcome {
    Long benRegId;
    Long suspectedId;
    String nikshayId;
    Integer nikshayVillageId;
    Integer deviceConfigId;
    String designatedPhiId;
    String destinationHierarchyId;
    /** TRANSFERRED | HELD | FAILED */
    String status;
    String reason;
    String detail;
}
