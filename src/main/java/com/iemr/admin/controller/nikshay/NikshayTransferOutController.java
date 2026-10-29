package com.iemr.admin.controller.nikshay;

import java.util.Map;

import com.iemr.admin.data.nikshay.dtos.TransferOutRequest;
import com.iemr.admin.data.nikshay.dtos.TransferOutResponse;
import com.iemr.admin.exceptionhandler.TransferOutInProgressException;
import com.iemr.admin.service.nikshay.NikshayTransferOutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual trigger for Ni-kshay transfer out.
 *
 *   POST /nikshay/transfer-out
 *   { "username": "...", "password": "..." }
 *
 * Phase 1: username/password are placeholders (presence-checked only). The Ni-kshay login uses
 * each NAAT device's credentials from m_nikshay_device_configuration.
 */
@Slf4j
@RestController
@RequestMapping("/nikshay")
@RequiredArgsConstructor
public class NikshayTransferOutController {

    private final NikshayTransferOutService transferOutService;

    @PostMapping("/transfer-out")
    public ResponseEntity<?> transferOut(@RequestBody TransferOutRequest request) {
        if (request == null || isBlank(request.getUsername()) || isBlank(request.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "username and password are required"));
        }
        try {
            TransferOutResponse response = transferOutService.transferOut();
            return ResponseEntity.ok(response);
        } catch (TransferOutInProgressException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Transfer out failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Transfer out failed: " + e.getMessage()));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
