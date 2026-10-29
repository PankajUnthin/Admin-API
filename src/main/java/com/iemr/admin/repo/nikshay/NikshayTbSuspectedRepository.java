package com.iemr.admin.repo.nikshay;


import java.util.Collection;
import java.util.List;

import com.iemr.admin.data.nikshay.NikshayTbSuspected;
import com.iemr.admin.data.nikshay.dtos.TransferCandidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transfer-out reads/writes on tb_suspected.
 * Every UPDATE is guarded by "nikshay_designated_phi_id IS NULL" so a row that is already
 * transferred is never overwritten (safe against double clicks / concurrent runs).
 */
@Repository
public interface NikshayTbSuspectedRepository extends JpaRepository<NikshayTbSuspected, Long> {

    /**
     * Beneficiaries to transfer:
     *   latest tb_suspected row per benRegID
     *   AND not yet transferred (no designated PHI)
     *   AND retryable state (never tried / HELD / FAILED below max attempts)
     *   AND TB confirmed:  is_tb_confirmed = 1
     *                  OR latest tb_stoptb_diagnostics NAAT positive
     *                  OR latest tb_stoptb_diagnostics X-ray positive
     * New rows first, then FAILED, then HELD, so a backlog of held rows never starves new cases.
     */
    @Query(value = """
        SELECT s.id                                             AS suspectedId,
               s.benRegID                                       AS benRegId,
               COALESCE(NULLIF(s.nikshay_id, ''), d.nikshay_id) AS nikshayId,
               COALESCE(s.nikshay_village_id, d.nikshay_village_id) AS nikshayVillageId,
               s.is_tb_confirmed                                AS tbConfirmed,
               d.truenat_result                                 AS truenatResult,
               d.digital_chest_xray_result                      AS xrayResult,
               COALESCE(s.nikshay_transfer_attempts, 0)         AS attempts
        FROM   tb_suspected s
        LEFT JOIN tb_stoptb_diagnostics d
               ON d.id = (SELECT MAX(d2.id) FROM tb_stoptb_diagnostics d2
                           WHERE d2.ben_reg_id = s.benRegID AND COALESCE(d2.deleted, 0) = 0)
        WHERE  s.benRegID IS NOT NULL
          AND  s.id = (SELECT MAX(s2.id) FROM tb_suspected s2 WHERE s2.benRegID = s.benRegID)
          AND  s.nikshay_designated_phi_id IS NULL
          AND (    s.nikshay_transfer_status IS NULL
               OR  s.nikshay_transfer_status = 'HELD'
               OR (s.nikshay_transfer_status = 'FAILED' AND COALESCE(s.nikshay_transfer_attempts, 0) < :maxAttempts))
          AND (    s.is_tb_confirmed = 1
               OR (REGEXP_LIKE(d.truenat_result, :naatPositive, 'i')
                   AND NOT REGEXP_LIKE(d.truenat_result, :naatNegative, 'i'))
               OR (REGEXP_LIKE(d.digital_chest_xray_result, :xrayPositive, 'i')
                   AND NOT REGEXP_LIKE(d.digital_chest_xray_result, :xrayNegative, 'i')))
        ORDER BY CASE WHEN s.nikshay_transfer_status IS NULL THEN 0
                      WHEN s.nikshay_transfer_status = 'FAILED' THEN 1 ELSE 2 END,
                 s.id
        LIMIT  :batchSize
        """, nativeQuery = true)
    List<TransferCandidate> fetchTransferCandidates(@Param("maxAttempts") int maxAttempts,
                                                    @Param("batchSize") int batchSize,
                                                    @Param("naatPositive") String naatPositive,
                                                    @Param("naatNegative") String naatNegative,
                                                    @Param("xrayPositive") String xrayPositive,
                                                    @Param("xrayNegative") String xrayNegative);

    /** Transfer succeeded: record where the beneficiary went. */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE tb_suspected
        SET    nikshay_designated_phi_id        = :designatedPhiId,
               nikshay_destination_hierarchy_id = :destinationHierarchyId,
               nikshay_transfer_status          = 'TRANSFERRED',
               nikshay_transfer_failure_reason  = NULL,
               nikshay_transfer_date            = NOW(),
               nikshay_transfer_device_id       = :deviceConfigId
        WHERE  id = :id AND nikshay_designated_phi_id IS NULL
        """, nativeQuery = true)
    int markTransferred(@Param("id") Long suspectedId,
                        @Param("designatedPhiId") String designatedPhiId,
                        @Param("destinationHierarchyId") String destinationHierarchyId,
                        @Param("deviceConfigId") Integer deviceConfigId);

    /** Config / data missing – no attempt counted, picked up again next run. */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE tb_suspected
        SET    nikshay_transfer_status         = 'HELD',
               nikshay_transfer_failure_reason = :reason,
               nikshay_transfer_device_id      = :deviceConfigId
        WHERE  id IN (:ids) AND nikshay_designated_phi_id IS NULL
        """, nativeQuery = true)
    int markHeld(@Param("ids") Collection<Long> suspectedIds,
                 @Param("reason") String reason,
                 @Param("deviceConfigId") Integer deviceConfigId);

    /** Portal / login failure – attempt counted. */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE tb_suspected
        SET    nikshay_transfer_status         = 'FAILED',
               nikshay_transfer_failure_reason = :reason,
               nikshay_transfer_attempts       = COALESCE(nikshay_transfer_attempts, 0) + 1,
               nikshay_transfer_device_id      = :deviceConfigId
        WHERE  id IN (:ids) AND nikshay_designated_phi_id IS NULL
        """, nativeQuery = true)
    int markFailed(@Param("ids") Collection<Long> suspectedIds,
                   @Param("reason") String reason,
                   @Param("deviceConfigId") Integer deviceConfigId);
}
