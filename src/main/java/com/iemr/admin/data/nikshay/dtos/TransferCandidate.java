package com.iemr.admin.data.nikshay.dtos;

/**
 * Projection for NikshayTbSuspectedRepository.fetchTransferCandidates().
 * Getter names match the column aliases in the query.
 */
public interface TransferCandidate {
    /** tb_suspected.id – the row that gets updated */
    Long getSuspectedId();
    Long getBenRegId();
    /** tb_suspected.nikshay_id, else latest tb_stoptb_diagnostics.nikshay_id */
    String getNikshayId();
    /** tb_suspected.nikshay_village_id, else latest tb_stoptb_diagnostics.nikshay_village_id */
    Integer getNikshayVillageId();
    /** tb_suspected.is_tb_confirmed (1 / 0 / null) */
    Integer getTbConfirmed();
    String getTruenatResult();
    String getXrayResult();
    Integer getAttempts();
}
