package com.iemr.admin.service.nikshay;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import com.iemr.admin.config.NikshayProperties;
import com.iemr.admin.data.nikshay.NikshayDeviceConfiguration;
import com.iemr.admin.data.nikshay.NikshayDeviceName;
import com.iemr.admin.data.nikshay.NikshayDeviceVillageMapping;
import com.iemr.admin.data.nikshay.NikshayTransferOutPhiVillageMapping;
import com.iemr.admin.data.nikshay.dtos.NikshaySession;
import com.iemr.admin.data.nikshay.dtos.TransferCandidate;
import com.iemr.admin.data.nikshay.dtos.TransferOutResponse;
import com.iemr.admin.data.nikshay.dtos.TransferOutcome;
import com.iemr.admin.data.nikshay.dtos.TransferPatientRequest;
import com.iemr.admin.exceptionhandler.NikshayApiException;
import com.iemr.admin.exceptionhandler.NikshayLoginException;
import com.iemr.admin.exceptionhandler.NikshaySessionExpiredException;
import com.iemr.admin.exceptionhandler.NikshayUnavailableException;
import com.iemr.admin.exceptionhandler.TransferOutInProgressException;
import com.iemr.admin.repo.nikshay.NikshayDeviceConfigurationRepo;
import com.iemr.admin.repo.nikshay.NikshayDeviceVillageMappingRepository;
import com.iemr.admin.repo.nikshay.NikshayTbSuspectedRepository;
import com.iemr.admin.repo.nikshay.NikshayTransferOutPhiRepo;
import com.iemr.admin.utils.nikshay.NikshayApiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.iemr.admin.data.nikshay.dtos.TransferStatus.*;


/**
 * Phase 1 transfer out (manual trigger).
 *
 *  1. fetch TB-confirmed beneficiaries not yet transferred          (tb_suspected + tb_stoptb_diagnostics)
 *  2. Ni-kshay village → NAAT device                                (m_nikshay_device_village_mapping)
 *  3. Ni-kshay village → designated PHI                             (m_nikshay_transfer_out_phi_village_mapping)
 *  4. Map<device, List<beneficiary>>
 *  5. per device: login with its credentials → TransferPatient per beneficiary → logout
 *  6. after EVERY beneficiary: write the outcome on its tb_suspected row
 *
 * HELD   = configuration/data missing, no attempt counted, retried on the next trigger.
 * FAILED = login/portal/rejection, attempt counted, retried until nikshay.transfer.max-attempts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NikshayTransferOutService {

    private static final DateTimeFormatter DD_MM_YYYY = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int REASON_MAX = 500;

    private final NikshayProperties props;
    private final NikshayLoginService loginService;
    private final NikshayApiClient apiClient;
    private final NikshayTbSuspectedRepository tbSuspectedRepo;
    private final NikshayDeviceVillageMappingRepository deviceVillageRepo;
    private final NikshayDeviceConfigurationRepo deviceRepo;
    private final NikshayTransferOutPhiRepo phiVillageRepo;

    /** one manual run at a time per application instance */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** A beneficiary that has a device and a designated PHI resolved. */
    private record Item(TransferCandidate candidate, NikshayTransferOutPhiVillageMapping phi) {}

    public boolean isRunning() {
        return running.get();
    }

    /**
     * Runs one transfer-out pass.
     *
     * @throws TransferOutInProgressException if a pass is already running
     */
    public TransferOutResponse transferOut() {
        if (!running.compareAndSet(false, true)) {
            throw new TransferOutInProgressException("A transfer-out run is already in progress");
        }
        long t0 = System.currentTimeMillis();
        List<TransferOutcome> outcomes = new ArrayList<>();
        try {
            // ---------- 1. eligible beneficiaries
            NikshayProperties.Transfer cfg = props.getTransfer();
            List<TransferCandidate> fetched = tbSuspectedRepo.fetchTransferCandidates(
                    cfg.getMaxAttempts(), cfg.getBatchSize(),
                    cfg.getNaatPositiveRegex(), cfg.getNaatNegativeRegex(),
                    cfg.getXrayPositiveRegex(), cfg.getXrayNegativeRegex());
            log.info("Transfer out: {} candidate(s) fetched", fetched.size());
            if (fetched.isEmpty()) {
                return summary(0, 0, outcomes, t0);
            }

            List<TransferCandidate> candidates = new ArrayList<>();
            for (TransferCandidate c : fetched) {
                if (isBlank(c.getNikshayId())) {
                    held(outcomes, List.of(c), null, null, RS_NIKSHAY_ID_MISSING, "No Ni-kshay ID on tb_suspected or tb_stoptb_diagnostics");
                } else if (c.getNikshayVillageId() == null) {
                    held(outcomes, List.of(c), null, null, RS_VILLAGE_MISSING, "No Ni-kshay village on tb_suspected or tb_stoptb_diagnostics");
                } else {
                    candidates.add(c);
                }
            }
            Set<Integer> villageIds = candidates.stream().map(TransferCandidate::getNikshayVillageId).collect(Collectors.toSet());

            // ---------- 2. village → NAAT device
            Map<Integer, List<NikshayDeviceVillageMapping>> villageToDevices = villageIds.isEmpty() ? Map.of()
                    : deviceVillageRepo.findActiveByVillageIds(villageIds, NikshayDeviceName.NAAT)
                        .stream().collect(Collectors.groupingBy(NikshayDeviceVillageMapping::getVillageId));
            Set<Integer> deviceIds = villageToDevices.values().stream().flatMap(List::stream)
                    .map(NikshayDeviceVillageMapping::getDeviceDetailId).collect(Collectors.toSet());
            Map<Integer, NikshayDeviceConfiguration> devices = deviceIds.isEmpty() ? Map.of()
                    : StreamSupport.stream(deviceRepo.findAllById(deviceIds).spliterator(), false)
                        .collect(Collectors.toMap(NikshayDeviceConfiguration::getId, Function.identity()));

            // ---------- 3. village → designated PHI
            Map<Integer, List<NikshayTransferOutPhiVillageMapping>> villageToPhis = villageIds.isEmpty() ? Map.of()
                    : phiVillageRepo.findByVillageIdInAndStatusIgnoreCase(villageIds, NikshayTransferOutPhiVillageMapping.STATUS_ACTIVE)
                        .stream().collect(Collectors.groupingBy(NikshayTransferOutPhiVillageMapping::getVillageId));

            // ---------- 4. Map<device, List<beneficiary>>
            Map<Integer, List<Item>> buckets = new LinkedHashMap<>();
            for (TransferCandidate c : candidates) {
                Integer village = c.getNikshayVillageId();

                Set<Integer> deviceForVillage = villageToDevices.getOrDefault(village, List.of()).stream()
                        .map(NikshayDeviceVillageMapping::getDeviceDetailId).collect(Collectors.toSet());
                if (deviceForVillage.isEmpty()) {
                    held(outcomes, List.of(c), null, null, RS_DEVICE_NOT_MAPPED, "No active NAAT device mapped to village " + village);
                    continue;
                }
                if (deviceForVillage.size() > 1) {
                    held(outcomes, List.of(c), null, null, RS_MULTIPLE_DEVICES, "Village " + village + " mapped to devices " + deviceForVillage);
                    continue;
                }
                Integer deviceId = deviceForVillage.iterator().next();

                List<NikshayTransferOutPhiVillageMapping> phis = villageToPhis.getOrDefault(village, List.of());
                if (phis.isEmpty()) {
                    held(outcomes, List.of(c), deviceId, null, RS_PHI_NOT_MAPPED, "No active designated PHI for village " + village);
                    continue;
                }
                if (phis.size() > 1) {
                    held(outcomes, List.of(c), deviceId, null, RS_MULTIPLE_PHI, phis.size() + " active designated PHIs for village " + village);
                    continue;
                }
                NikshayTransferOutPhiVillageMapping phi = phis.get(0);
                if (parseHierarchyId(phi.getDesignationHierarchyId()) == null) {
                    held(outcomes, List.of(c), deviceId, phi, RS_DESTINATION_MISSING,
                            "designation_hierarchy_id empty/invalid for PHI " + phi.getDesignatedPhiId() + " (mapping id " + phi.getId() + ")");
                    continue;
                }
                buckets.computeIfAbsent(deviceId, k -> new ArrayList<>()).add(new Item(c, phi));
            }

            // ---------- 5 + 6. per device
            int eligible = buckets.values().stream().mapToInt(List::size).sum();
            for (Map.Entry<Integer, List<Item>> e : buckets.entrySet()) {
                processDevice(devices.get(e.getKey()), e.getValue(), outcomes);
            }
            return summary(fetched.size(), eligible, buckets.size(), outcomes, t0);
        } finally {
            running.set(false);
        }
    }

    // ------------------------------------------------------------------ one device

    private void processDevice(NikshayDeviceConfiguration device, List<Item> items, List<TransferOutcome> outcomes) {
        log.info("Transfer out: device {} ({}) – {} beneficiaries", device.getDeviceId(), device.getPhiUserId(), items.size());

        NikshaySession session;
        try {
            session = loginService.login(device);
        } catch (NikshayLoginException ex) {
            failed(outcomes, items, device.getId(), RS_DEVICE_LOGIN_FAILED, ex.getMessage());
            return;
        } catch (NikshayUnavailableException ex) {
            failed(outcomes, items, device.getId(), RS_PORTAL_UNAVAILABLE, "Login: " + ex.getMessage());
            return;
        }

        try {
            for (int i = 0; i < items.size(); i++) {
                Item item = items.get(i);
                try {
                    transferOne(session, device, item, outcomes);
                } catch (NikshaySessionExpiredException expired) {
                    log.warn("Session expired for device {} – logging in again", device.getDeviceId());
                    try {
                        session = loginService.login(device);
                    } catch (NikshayLoginException | NikshayUnavailableException ex) {
                        String reason = ex instanceof NikshayLoginException ? RS_DEVICE_LOGIN_FAILED : RS_PORTAL_UNAVAILABLE;
                        failed(outcomes, items.subList(i, items.size()), device.getId(), reason, "Re-login: " + ex.getMessage());
                        return;
                    }
                    try {
                        transferOne(session, device, item, outcomes);
                    } catch (NikshaySessionExpiredException again) {
                        failed(outcomes, items.subList(i, items.size()), device.getId(), RS_DEVICE_LOGIN_FAILED,
                                "Session rejected right after re-login: " + again.getMessage());
                        return;
                    }
                }
                pace();
            }
        } finally {
            loginService.logout(session);
        }
    }

    /**
     * Calls TransferPatient for one beneficiary and persists the result immediately.
     * Only NikshaySessionExpiredException escapes (caller re-logs in and retries this item).
     */
    private void transferOne(NikshaySession session, NikshayDeviceConfiguration device, Item item, List<TransferOutcome> outcomes) {
        TransferCandidate c = item.candidate();
        NikshayTransferOutPhiVillageMapping phi = item.phi();
        TransferPatientRequest req = TransferPatientRequest.builder()
                .patientId(c.getNikshayId().trim())
                .destinationHierarchyId(parseHierarchyId(phi.getDesignationHierarchyId()))
                .transferDate(LocalDate.now().format(DD_MM_YYYY))
                .reason(props.getTransfer().getReason())
                .build();
        try {
            apiClient.transferPatient(session, req);
            int updated = tbSuspectedRepo.markTransferred(c.getSuspectedId(), phi.getDesignatedPhiId(),
                    phi.getDesignationHierarchyId(), device.getId());
            outcomes.add(outcome(c, device.getId(), phi, TRANSFERRED, null,
                    updated == 1 ? "Transferred to PHI " + phi.getDesignatedPhiId() : "Transferred, but row was already marked (concurrent update)"));
            log.info("ben {} (Ni-kshay {}) → PHI {} TRANSFERRED", c.getBenRegId(), c.getNikshayId(), phi.getDesignatedPhiId());
        } catch (NikshayApiException ex) {
            failed(outcomes, List.of(item), device.getId(), RS_TRANSFER_REJECTED, ex.getMessage());
        } catch (NikshayUnavailableException ex) {
            failed(outcomes, List.of(item), device.getId(), RS_PORTAL_UNAVAILABLE, ex.getMessage());
        }
    }

    // ------------------------------------------------------------------ persistence + outcomes

    private void held(List<TransferOutcome> outcomes, List<TransferCandidate> list, Integer deviceId,
                      NikshayTransferOutPhiVillageMapping phi, String reason, String detail) {
        tbSuspectedRepo.markHeld(list.stream().map(TransferCandidate::getSuspectedId).toList(), reasonText(reason, detail), deviceId);
        for (TransferCandidate c : list) {
            outcomes.add(outcome(c, deviceId, phi, HELD, reason, detail));
        }
        log.warn("{} beneficiary(ies) HELD – {}: {}", list.size(), reason, detail);
    }

    private void failed(List<TransferOutcome> outcomes, List<Item> items, Integer deviceId, String reason, String detail) {
        tbSuspectedRepo.markFailed(items.stream().map(i -> i.candidate().getSuspectedId()).toList(), reasonText(reason, detail), deviceId);
        for (Item i : items) {
            outcomes.add(outcome(i.candidate(), deviceId, i.phi(), FAILED, reason, detail));
        }
        log.warn("{} beneficiary(ies) FAILED – {}: {}", items.size(), reason, detail);
    }

    private static TransferOutcome outcome(TransferCandidate c, Integer deviceId, NikshayTransferOutPhiVillageMapping phi,
                                           String status, String reason, String detail) {
        return TransferOutcome.builder()
                .benRegId(c.getBenRegId())
                .suspectedId(c.getSuspectedId())
                .nikshayId(c.getNikshayId())
                .nikshayVillageId(c.getNikshayVillageId())
                .deviceConfigId(deviceId)
                .designatedPhiId(phi == null ? null : phi.getDesignatedPhiId())
                .destinationHierarchyId(phi == null ? null : phi.getDesignationHierarchyId())
                .status(status)
                .reason(reason)
                .detail(detail)
                .build();
    }

    private static TransferOutResponse summary(int fetched, int eligible, List<TransferOutcome> outcomes, long t0) {
        return summary(fetched, eligible, 0, outcomes, t0);
    }

    private static TransferOutResponse summary(int fetched, int eligible, int devices, List<TransferOutcome> outcomes, long t0) {
        return TransferOutResponse.builder()
                .fetched(fetched)
                .eligible(eligible)
                .transferred((int) outcomes.stream().filter(o -> TRANSFERRED.equals(o.getStatus())).count())
                .held((int) outcomes.stream().filter(o -> HELD.equals(o.getStatus())).count())
                .failed((int) outcomes.stream().filter(o -> FAILED.equals(o.getStatus())).count())
                .devicesProcessed(devices)
                .durationMs(System.currentTimeMillis() - t0)
                .results(outcomes)
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private static Long parseHierarchyId(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(v.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String reasonText(String reason, String detail) {
        String s = detail == null ? reason : reason + ": " + detail;
        return s.length() <= REASON_MAX ? s : s.substring(0, REASON_MAX);
    }

    private void pace() {
        int ms = props.getTransfer().getCallDelayMs();
        if (ms > 0) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
