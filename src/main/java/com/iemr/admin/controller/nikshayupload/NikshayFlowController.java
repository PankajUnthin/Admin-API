/*
* AMRIT – Accessible Medical Records via Integrated Technology
* Integrated EHR (Electronic Health Records) Solution
*
* Copyright (C) "Piramal Swasthya Management and Research Institute"
*
* This file is part of AMRIT.
*
* This program is free software: you can redistribute it and/or modify
* it under the terms of the GNU General Public License as published by
* the Free Software Foundation, either version 3 of the License, or
* (at your option) any later version.
*
* This program is distributed in the hope that it will be useful,
* but WITHOUT ANY WARRANTY; without even the implied warranty of
* MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
* GNU General Public License for more details.
*
* You should have received a copy of the GNU General Public License
* along with this program.  If not, see https://www.gnu.org/licenses/.
*/
package com.iemr.admin.controller.nikshayupload;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.iemr.admin.service.nikshayjoblog.NikshayJobRunContext;
import com.iemr.admin.service.nikshayjoblog.NikshayTriggerType;
import com.iemr.admin.service.nikshayupload.NikshayTestType;
import com.iemr.admin.service.nikshayupload.NikshayUploadService;
import com.iemr.admin.utils.JwtUtil;

import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;

/** Triggers the Ni-kshay upload of Stop TB test results (NAAT / X-ray). */
@RestController
@RequestMapping(value = "/nikshay/flow", headers = "Authorization")
public class NikshayFlowController {
	private static final Logger logger = LoggerFactory.getLogger(NikshayFlowController.class);

	@Autowired
	private NikshayUploadService nikshayUploadService;

	@Autowired
	private JwtUtil jwtUtil;

	/** Best-effort, as in NikshayExportController: only recorded as triggered_by in the history log. */
	private String currentUsername(HttpServletRequest request) {
		try {
			String header = request.getHeader("Authorization");
			if (header == null) {
				return "unknown";
			}
			String token = header.startsWith("Bearer ") ? header.substring(7) : header;
			String username = jwtUtil.extractUsername(token);
			return username != null ? username : "unknown";
		} catch (Exception e) {
			return "unknown";
		}
	}

	@Operation(summary = "Upload NAAT or X-ray results to Ni-kshay for the given beneficiary IDs")
	@PostMapping(value = "/uploadResults")
	public ResponseEntity<?> uploadResults(@RequestParam("testType") NikshayTestType testType,
			@RequestBody List<Long> beneficiaryIds, HttpServletRequest request) {
		if (beneficiaryIds == null || beneficiaryIds.isEmpty()) {
			return ResponseEntity.badRequest().body("At least one beneficiary ID is required");
		}
		try {
			NikshayJobRunContext ctx = new NikshayJobRunContext(NikshayTriggerType.MANUAL, currentUsername(request));
			nikshayUploadService.uploadResults(testType, beneficiaryIds, ctx);
			return ResponseEntity.ok(testType + " results upload triggered for " + beneficiaryIds.size()
					+ " beneficiaries");
		} catch (Exception e) {
			logger.error("Error uploading {} results to Ni-kshay", testType, e);
			return ResponseEntity.status(500).body("Could not upload " + testType + " results");
		}
	}
}
