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
package com.iemr.admin.service.nikshayidgenerate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.iemr.admin.data.nikshayidgenerate.NikshayIdGenerateBeneficiaryDTO;
import com.iemr.admin.data.nikshayidgenerate.NikshayIdGenerateVillageBatch;
import com.iemr.admin.data.nikshayupload.NikshayDeviceCredentialDTO;
import com.iemr.admin.repo.nikshayidgenerate.NikshayIdGenerateRepository;
import com.iemr.admin.service.nikshayclient.NikshayDeviceCredentialService;
import com.iemr.admin.service.nikshayupload.NikshayTestType;

/** Generates Ni-kshay IDs for the given Stop TB beneficiaries. */
@Service
public class NikshayIdGenerateService {
	private static final Logger logger = LoggerFactory.getLogger(NikshayIdGenerateService.class);

	@Autowired
	private NikshayIdGenerateRepository nikshayIdGenerateRepository;

	@Autowired
	private NikshayDeviceCredentialService credentialService;

	/** Generates Ni-kshay IDs for the given beneficiaries. */
	public void generateNikshayIds(List<Long> beneficiaryIds) {
		logger.info("Generating Ni-kshay IDs for {} beneficiaries", beneficiaryIds.size());
	}

	/** Fetches the beneficiaries eligible for a Ni-kshay ID and processes them. */
	public void fetchAndProcessBeneficiaries() {
		List<NikshayIdGenerateBeneficiaryDTO> beneficiaries = nikshayIdGenerateRepository.findEligibleBeneficiaries();
		logger.info("Found {} beneficiaries eligible for Ni-kshay ID generation", beneficiaries.size());
		processBeneficiaries(beneficiaries);
	}

	/** Processes the beneficiaries returned by the eligibility query. */
	public void processBeneficiaries(List<NikshayIdGenerateBeneficiaryDTO> beneficiaries) {
		logger.info("Processing {} beneficiaries for Ni-kshay ID generation", beneficiaries.size());
		Map<Integer, NikshayIdGenerateVillageBatch> batchesByVillage = groupByVillage(beneficiaries);
		logger.info("Grouped beneficiaries into {} village batch(es)", batchesByVillage.size());
	}

	/**
	 * Groups the beneficiaries by Ni-kshay village ID, pairing each village with its NAAT
	 * device login. Beneficiaries with no Ni-kshay village, or whose village has no usable
	 * NAAT device login, are left out.
	 */
	private Map<Integer, NikshayIdGenerateVillageBatch> groupByVillage(
			List<NikshayIdGenerateBeneficiaryDTO> beneficiaries) {
		List<Integer> villageIds = new ArrayList<>();
		for (NikshayIdGenerateBeneficiaryDTO beneficiary : beneficiaries) {
			if (beneficiary.getNikshayVillageId() != null) {
				villageIds.add(beneficiary.getNikshayVillageId());
			}
		}
		Set<Integer> undecryptableVillages = new HashSet<>();
		Map<Integer, NikshayDeviceCredentialDTO> credentialsByVillage = credentialService.findCredentials(villageIds,
				NikshayTestType.NAAT.name(), undecryptableVillages);

		Map<Integer, NikshayIdGenerateVillageBatch> batchesByVillage = new LinkedHashMap<>();
		for (NikshayIdGenerateBeneficiaryDTO beneficiary : beneficiaries) {
			Integer villageId = beneficiary.getNikshayVillageId();
			if (villageId == null) {
				logger.warn("Skipping beneficiary {}: no Ni-kshay village", beneficiary.getBeneficiaryId());
				continue;
			}
			NikshayDeviceCredentialDTO credential = credentialsByVillage.get(villageId);
			if (credential == null) {
				logger.warn("Skipping beneficiary {}: {} for Ni-kshay village {}", beneficiary.getBeneficiaryId(),
						undecryptableVillages.contains(villageId) ? "NAAT device password could not be decrypted"
								: "no active NAAT device mapped",
						villageId);
				continue;
			}
			NikshayIdGenerateVillageBatch batch = batchesByVillage.get(villageId);
			if (batch == null) {
				batch = new NikshayIdGenerateVillageBatch(villageId, credential);
				batchesByVillage.put(villageId, batch);
			}
			batch.getBeneficiaries().add(beneficiary);
		}
		return batchesByVillage;
	}
}
