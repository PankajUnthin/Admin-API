package com.iemr.admin.data.nikshay;

import jakarta.persistence.*;
import java.sql.Timestamp;
import lombok.Data;

/**
 * Lean mapping of the EXISTING tb_suspected table – only the columns the transfer uses.
 * Never save()d: all writes are the @Modifying queries in NikshayTbSuspectedRepository, so the
 * van-sync columns (processed, SyncedDate, DownSynced…) are never touched by this code.
 */
@Data
@Entity
@Table(name = "tb_suspected")
public class NikshayTbSuspected {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "benRegID")
    private Long benRegId;

    @Column(name = "nikshay_id")
    private String nikshayId;

    @Column(name = "nikshay_village_id")
    private Integer nikshayVillageId;

    @Column(name = "is_tb_confirmed")
    private Boolean isTbConfirmed;

    // ---- new columns (sql/01_tb_suspected_transfer_columns.sql)
    @Column(name = "nikshay_designated_phi_id")
    private String nikshayDesignatedPhiId;

    @Column(name = "nikshay_destination_hierarchy_id")
    private String nikshayDestinationHierarchyId;

    @Column(name = "nikshay_transfer_status")
    private String nikshayTransferStatus;

    @Column(name = "nikshay_transfer_failure_reason")
    private String nikshayTransferFailureReason;

    @Column(name = "nikshay_transfer_attempts")
    private Integer nikshayTransferAttempts;

    @Column(name = "nikshay_transfer_date")
    private Timestamp nikshayTransferDate;

    @Column(name = "nikshay_transfer_device_id")
    private Integer nikshayTransferDeviceId;
}
