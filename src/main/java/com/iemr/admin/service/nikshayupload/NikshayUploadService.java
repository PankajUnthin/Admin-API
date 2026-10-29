package com.iemr.admin.service.nikshayupload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.iemr.admin.data.nikshay.NikshayJobHistoryLog;
import com.iemr.admin.data.nikshayupload.NikshayDeviceCredentialDTO;
import com.iemr.admin.data.nikshayupload.NikshayTestResult;
import com.iemr.admin.data.nikshayupload.NikshayVillageBatch;
import com.iemr.admin.repo.nikshayupload.DiagnosticOrderRepo;
import com.iemr.admin.service.nikshayclient.NikshayDeviceCredentialService;
import com.iemr.admin.service.nikshayjoblog.NikshayFailureReason;
import com.iemr.admin.service.nikshayjoblog.NikshayJobHistoryLogService;
import com.iemr.admin.service.nikshayjoblog.NikshayJobRunContext;

/**
 * Uploads Stop TB test results (NAAT and X-ray) to Ni-kshay for the given
 * beneficiaries.
 */
@Service
public class NikshayUploadService {
	private static final Logger logger = LoggerFactory.getLogger(NikshayUploadService.class);

	@Autowired
	private DiagnosticOrderRepo diagnosticOrderRepo;

	@Autowired
	private NikshayDeviceCredentialService credentialService;

	@Autowired
	private NikshayJobHistoryLogService logService;

	@Autowired
	private NikshayTestResultPusher testResultPusher;

	@Autowired
	private XrayTestPayloadBuilder xrayPayloadBuilder;

	@Autowired
	private NaatTestPayloadBuilder naatPayloadBuilder;

	/** Uploads the given test type's results for the given beneficiaries. */
	public void uploadResults(NikshayTestType testType, List<Long> beneficiaryIds, NikshayJobRunContext ctx) {
		switch (testType) {
			case NAAT:
				uploadNaatResults(beneficiaryIds, ctx);
				break;
			case XRAY:
				uploadXrayResults(beneficiaryIds, ctx);
				break;
		}
	}

	/** Uploads NAAT (TrueNat MTB) results for the given beneficiaries. */
	public void uploadNaatResults(List<Long> beneficiaryIds, NikshayJobRunContext ctx) {
		List<NikshayTestResult> naatResults = new ArrayList<>(diagnosticOrderRepo.findMtbResults(beneficiaryIds));
		logger.info("Found {} NAAT result(s) for {} beneficiaries", naatResults.size(), beneficiaryIds.size());
		List<NikshayJobHistoryLog> logEntries = new ArrayList<>();
		Map<Integer, NikshayVillageBatch> batchesByVillage = prepareBatches(naatResults,
				NikshayTestType.NAAT, ctx, logEntries);
		try {
			testResultPusher.push(batchesByVillage, naatPayloadBuilder, ctx, logEntries);
		} finally {
			// Whatever happened, keep the outcome of every result handled so far
			saveLog(logEntries);
		}
	}

	/** Uploads chest X-ray results for the given beneficiaries. */
	public void uploadXrayResults(List<Long> beneficiaryIds, NikshayJobRunContext ctx) {
		List<NikshayTestResult> xrayResults = new ArrayList<>(diagnosticOrderRepo.findXrayResults(beneficiaryIds));
		logger.info("Found {} X-ray result(s) for {} beneficiaries", xrayResults.size(), beneficiaryIds.size());
		List<NikshayJobHistoryLog> logEntries = new ArrayList<>();
		Map<Integer, NikshayVillageBatch> batchesByVillage = prepareBatches(xrayResults,
				NikshayTestType.XRAY, ctx, logEntries);
		try {
			testResultPusher.push(batchesByVillage, xrayPayloadBuilder, ctx, logEntries);
		} finally {
			// Whatever happened, keep the outcome of every result handled so far
			saveLog(logEntries);
		}
	}

	/**
	 * Keeps the results whose village is a Ni-kshay village, finds each village's device
	 * login and groups the results by village. Every result left out gets a FAILED entry
	 * in logEntries with its reason, so none is dropped silently.
	 */
	private Map<Integer, NikshayVillageBatch> prepareBatches(List<NikshayTestResult> results,
			NikshayTestType testType, NikshayJobRunContext ctx, List<NikshayJobHistoryLog> logEntries) {
		List<NikshayTestResult> inNikshayVillage = new ArrayList<>();
		for (NikshayTestResult result : results) {
			String problem = villageProblem(result);
			if (problem == null) {
				inNikshayVillage.add(result);
			} else {
				logEntries.add(logService.failure(ctx, testType.getPhase(), result.getBenRegId(),
						result.getExternalOrderId(), NikshayFailureReason.VILLAGE_MISSING, problem));
			}
		}
		Set<Integer> undecryptableVillages = new HashSet<>();
		Map<Integer, NikshayDeviceCredentialDTO> credentialsByVillage = credentialService.findCredentials(
				inNikshayVillage.stream().map(NikshayTestResult::getVillageId).collect(Collectors.toList()),
				testType.name(), undecryptableVillages);
		return groupByVillage(inNikshayVillage, testType, credentialsByVillage, undecryptableVillages, ctx,
				logEntries);
	}

	/**
	 * Groups results by village, pairing each village with its device login. A result
	 * from a village with no usable login is logged as failed: DEVICE_CONFIGURATION_MISSING
	 * when the device's password couldn't be decrypted, VILLAGE_MISSING when no active
	 * device of this type is mapped to the village.
	 */
	private Map<Integer, NikshayVillageBatch> groupByVillage(List<NikshayTestResult> results,
			NikshayTestType testType, Map<Integer, NikshayDeviceCredentialDTO> credentialsByVillage,
			Set<Integer> undecryptableVillages, NikshayJobRunContext ctx, List<NikshayJobHistoryLog> logEntries) {
		Map<Integer, NikshayVillageBatch> batchesByVillage = new LinkedHashMap<>();
		for (NikshayTestResult result : results) {
			Integer villageId = result.getVillageId();
			NikshayDeviceCredentialDTO credential = credentialsByVillage.get(villageId);
			if (credential == null) {
				boolean undecryptable = undecryptableVillages.contains(villageId);
				logEntries.add(logService.failure(ctx, testType.getPhase(), result.getBenRegId(),
						result.getExternalOrderId(),
						undecryptable ? NikshayFailureReason.DEVICE_CONFIGURATION_MISSING
								: NikshayFailureReason.VILLAGE_MISSING,
						undecryptable
								? "The " + testType + " device password for village " + villageId
										+ " could not be decrypted"
								: "No active " + testType + " device is mapped to village '" + result.getVillage()
										+ "' (" + villageId + ")"));
				continue;
			}
			batchesByVillage.computeIfAbsent(villageId,
					id -> new NikshayVillageBatch(id, result.getVillage(), credential)).getResults().add(result);
		}
		logger.info("Grouped {} result(s) into {} village batch(es)",
				batchesByVillage.values().stream().mapToInt(b -> b.getResults().size()).sum(),
				batchesByVillage.size());
		return batchesByVillage;
	}

	/**
	 * Null when the result's village ID is a Ni-kshay village ID, i.e. the village name
	 * saved at registration matches m_nikshay_village's name for that ID; otherwise the
	 * reason it isn't. Stop TB registrations before the 2026-07-28 fix saved AMRIT village
	 * IDs, whose numbers can belong to a different Ni-kshay village; using them would pick
	 * the wrong device and send the result under the wrong village.
	 */
	private String villageProblem(NikshayTestResult result) {
		if (result.getVillageId() == null) {
			return "The beneficiary has no current village";
		}
		if (result.getNikshayVillageName() == null) {
			return "Village '" + result.getVillage() + "' (" + result.getVillageId()
					+ ") is not a Ni-kshay village ID";
		}
		if (!normalizeName(result.getVillage()).equals(normalizeName(result.getNikshayVillageName()))) {
			return "Village '" + result.getVillage() + "' (" + result.getVillageId()
					+ ") is not a Ni-kshay village; Ni-kshay has '" + result.getNikshayVillageName()
					+ "' for this ID";
		}
		return null;
	}

	// Writes the run's history entries. A failure here is logged and doesn't stop the upload.
	private void saveLog(List<NikshayJobHistoryLog> logEntries) {
		try {
			logService.saveAll(logEntries);
		} catch (Exception e) {
			logger.error("Could not write {} Ni-kshay history log entries", logEntries.size(), e);
		}
	}

	// Case- and whitespace-insensitive form of a village name for comparison.
	private static String normalizeName(String name) {
		return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase();
	}
}
