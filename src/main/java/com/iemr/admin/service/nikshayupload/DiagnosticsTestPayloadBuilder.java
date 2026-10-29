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
package com.iemr.admin.service.nikshayupload;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.iemr.admin.data.nikshayupload.NikshayDeviceCredentialDTO;
import com.iemr.admin.data.nikshayupload.NikshayTestResult;
import com.iemr.admin.service.nikshayclient.NikshayApiException;
import com.iemr.admin.service.nikshayjoblog.NikshayFailureReason;

/**
 * The part of an AddDiagnosticsTest payload every test type shares: reason "TB
 * Screening", the device's PHI as testing facility, "reported by" and the
 * beneficiary's Ni-kshay ID, plus the result mapped to Ni-kshay's final
 * interpretation. Subclasses give the test type, the mapping and any extra fields.
 */
public abstract class DiagnosticsTestPayloadBuilder implements NikshayTestPayloadBuilder {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Value("${nikshay.test.reported-by:TBMIJA}")
	private String reportedBy;

	/** Ni-kshay's TestType, e.g. "Chest X Ray". */
	protected abstract String nikshayTestType();

	/** EMR Lite result summary (lower case, single spaces) -> Ni-kshay final interpretation. */
	protected abstract Map<String, String> interpretations();

	/** Adds the fields only this test type sends (none by default). */
	protected void addTestFields(ObjectNode payload) {
	}

	@Override
	public final ObjectNode build(NikshayTestResult result, NikshayDeviceCredentialDTO device) throws NikshayApiException {
		if (result.getNikshayId() == null || result.getNikshayId().isBlank()) {
			throw new NikshayApiException(NikshayFailureReason.MANDATORY_FIELD_MISSING,
					"The beneficiary has no Ni-kshay ID yet");
		}
		String interpretation = interpretations().get(normalize(result.getResult()));
		if (interpretation == null) {
			throw new NikshayApiException(NikshayFailureReason.MANDATORY_FIELD_MISSING, getTestType() + " result '"
					+ result.getResult() + "' can't be mapped to a Ni-kshay interpretation");
		}
		long facilityKey = facilityKey(device);

		ObjectNode payload = objectMapper.createObjectNode();
		payload.put("ReasonForTesting", "TB Screening");
		payload.put("TestType", nikshayTestType());
		ObjectNode facility = payload.putObject("TestingFacility");
		facility.put("Key", facilityKey);
		facility.put("Value", device.getFacilityName());
		addTestFields(payload);
		payload.put("ResultReportedBy", reportedBy);
		payload.put("FinalInterpretation", interpretation);
		payload.put("PatientId", result.getNikshayId().trim());
		return payload;
	}

	// Testing facility = the device's PHI, keyed in Ni-kshay by m_nikshay_facility.NikshayCode
	private static long facilityKey(NikshayDeviceCredentialDTO device) throws NikshayApiException {
		String code = device.getFacilityNikshayCode();
		try {
			if (code != null && device.getFacilityName() != null) {
				return Long.parseLong(code.trim());
			}
		} catch (NumberFormatException e) {
			// fall through
		}
		throw new NikshayApiException(NikshayFailureReason.DEVICE_CONFIGURATION_MISSING,
				"Device " + device.getDeviceSerialNumber() + " has no valid testing facility (Ni-kshay facility code '" + code
						+ "')");
	}

	private static String normalize(String value) {
		return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
	}
}
