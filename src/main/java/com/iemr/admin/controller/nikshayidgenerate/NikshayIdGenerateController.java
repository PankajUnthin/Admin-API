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
package com.iemr.admin.controller.nikshayidgenerate;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.iemr.admin.service.nikshayidgenerate.NikshayIdGenerateService;

import io.swagger.v3.oas.annotations.Operation;

/** Triggers the Ni-kshay ID generation for Stop TB beneficiaries. */
@RestController
@RequestMapping(value = "/nikshay/idGenerate", headers = "Authorization")
public class NikshayIdGenerateController {
	private static final Logger logger = LoggerFactory.getLogger(NikshayIdGenerateController.class);

	@Autowired
	private NikshayIdGenerateService nikshayIdGenerateService;

	@Operation(summary = "Generate Ni-kshay IDs for the given beneficiary IDs")
	@PostMapping(value = "/generate")
	public ResponseEntity<?> generateNikshayIds(@RequestBody List<Long> beneficiaryIds) {
		if (beneficiaryIds == null || beneficiaryIds.isEmpty()) {
			return ResponseEntity.badRequest().body("At least one beneficiary ID is required");
		}
		try {
			nikshayIdGenerateService.generateNikshayIds(beneficiaryIds);
			return ResponseEntity.ok("Ni-kshay ID generation triggered for " + beneficiaryIds.size()
					+ " beneficiaries");
		} catch (Exception e) {
			logger.error("Error generating Ni-kshay IDs", e);
			return ResponseEntity.status(500).body("Could not generate Ni-kshay IDs");
		}
	}
}
