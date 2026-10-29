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
package com.iemr.admin.repo.nikshayidgenerate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.iemr.admin.data.nikshayidgenerate.NikshayIdGenerateBeneficiaryDTO;

/**
 * Reads the beneficiaries that need a Ni-kshay ID: TB confirmed (or NAAT detected), alive,
 * and with no Ni-kshay ID yet. Plain SQL through JdbcTemplate (as in NikshayExportRepository)
 * because the query joins db_identity and db_iemr tables on the same MySQL server.
 */
@Repository
public class NikshayIdGenerateRepository {

	private static final String ELIGIBLE_BENEFICIARIES_SQL = "SELECT "
			+ "  m.BenRegId AS beneficiary_id, "
			+ "  hh.houseoldId, "
			+ "  d.FirstName, "
			+ "  NULLIF(TRIM(CONCAT_WS(' ', d.MiddleName, d.LastName)), '') AS LastName, "
			+ "  NULLIF(TRIM(d.FatherName), '') AS FathersName, "
			+ "  TIMESTAMPDIFF(YEAR, d.DOB, CURDATE()) AS Age, "
			+ "  DATE_FORMAT(d.DOB, '%d-%m-%Y') AS DateOfBirth, "
			+ "  CASE "
			+ "    WHEN UPPER(IFNULL(d.Gender, '')) LIKE 'M%' THEN 'Male' "
			+ "    WHEN UPPER(IFNULL(d.Gender, '')) LIKE 'F%' THEN 'Female' "
			+ "    WHEN UPPER(IFNULL(d.Gender, '')) LIKE 'T%' THEN 'Transgender' "
			+ "    ELSE NULL "
			+ "  END AS Gender, "
			+ "  COALESCE(c.PreferredPhoneNum, c.PhoneNum1, c.EmergencyContactNum) AS PrimaryPhone, "
			+ "  NULLIF(TRIM(ad.PermAddrLine1), '') AS Address, "
			+ "  NULLIF(TRIM(ad.PermPinCode), '') AS Pincode, "
			+ "  COALESCE(NULLIF(TRIM(hh.residentialArea), ''), 'Unknown') AS Area, "
			+ "  COALESCE(NULLIF(TRIM(d.MaritalStatus), ''), 'Unknown') AS MaritalStatus, "
			+ "  COALESCE(NULLIF(TRIM(d.occupation), ''), 'Unknown') AS Occupation, "
			+ "  COALESCE(NULLIF(TRIM(hh.type_bpl_apl), ''), 'Unknown') AS SocioeconomicStatus, "
			+ "  CASE "
			+ "    WHEN UPPER(TRIM(IFNULL(d.community, ''))) IN ('SC', 'ST') THEN UPPER(TRIM(d.community)) "
			+ "    ELSE 'Others' "
			+ "  END AS caste, "
			+ "  'Not Applicable' AS KeyPopulation, "
			+ "  CASE "
			+ "    WHEN CONCAT_WS(',', "
			+ "      IF(LOWER(TRIM(IFNULL(scr.cough_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Cough for more than 2 weeks', NULL), "
			+ "      IF(LOWER(TRIM(IFNULL(scr.fever_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Fever', NULL), "
			+ "      IF(LOWER(TRIM(IFNULL(scr.weight_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Weight loss', NULL), "
			+ "      IF(LOWER(TRIM(IFNULL(scr.sweats_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Night sweats', NULL) "
			+ "    ) = '' THEN 'Asymptomatic,' "
			+ "    ELSE CONCAT(CONCAT_WS(',', "
			+ "      IF(LOWER(TRIM(IFNULL(scr.cough_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Cough for more than 2 weeks', NULL), "
			+ "      IF(LOWER(TRIM(IFNULL(scr.fever_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Fever', NULL), "
			+ "      IF(LOWER(TRIM(IFNULL(scr.weight_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Weight loss', NULL), "
			+ "      IF(LOWER(TRIM(IFNULL(scr.sweats_check, ''))) IN ('1', 'yes', 'y', 'true'), 'Night sweats', NULL) "
			+ "    ), ',') "
			+ "  END AS Symptom, "
			+ "  CASE "
			+ "    WHEN UPPER(IFNULL(scr.hiv_status, '')) IN ('POSITIVE', 'REACTIVE', 'R') THEN 'Reactive' "
			+ "    WHEN UPPER(IFNULL(scr.hiv_status, '')) IN ('NEGATIVE', 'NON-REACTIVE', 'NON REACTIVE', 'NR') THEN 'Non-Reactive' "
			+ "    ELSE 'Unknown' "
			+ "  END AS HIVStatus, "
			+ "  hh.villageid         AS ResidenceHierarchyId, "
			+ "  nv.NikshayVillageID  AS nikshay_village_id, "
			+ "  ns.NikshayStateID    AS nikshay_state_id, "
			+ "  nd.NikshayDistrictID AS nikshay_district_id, "
			+ "  nt.NikshayTUID       AS nikshay_tu_id, "
			+ "  nf.NikshayFacilityID AS SelectedHierarchyId "
			+ "FROM db_identity.i_beneficiarymapping m "
			+ "INNER JOIN db_identity.i_beneficiarydetails d "
			+ "  ON d.VanSerialNo = m.BenDetailsId AND d.VanID = m.VanID AND d.Deleted = 0 "
			+ "INNER JOIN db_identity.i_beneficiaryaddress ad "
			+ "  ON ad.BenAddressID = m.BenAddressID AND ad.Deleted = 0 "
			+ "LEFT JOIN db_identity.i_beneficiarycontacts c "
			+ "  ON c.BenContactsID = m.BenContactsId "
			+ "LEFT JOIN db_iemr.tb_confirmed_cases conf "
			+ "  ON conf.id = ( "
			+ "    SELECT c2.id FROM db_iemr.tb_confirmed_cases c2 "
			+ "    WHERE c2.benRegID = m.BenRegId ORDER BY c2.id DESC LIMIT 1) "
			+ "LEFT JOIN db_iemr.tb_suspected sus "
			+ "  ON sus.id = ( "
			+ "    SELECT x.id FROM db_iemr.tb_suspected x "
			+ "    WHERE x.benRegID = m.BenRegId OR x.ben_id = m.BenRegId "
			+ "    ORDER BY x.id DESC LIMIT 1) "
			+ "LEFT JOIN db_iemr.tb_stoptb_diagnostics diag "
			+ "  ON diag.id = ( "
			+ "    SELECT d2.id FROM db_iemr.tb_stoptb_diagnostics d2 "
			+ "    WHERE d2.ben_reg_id = m.BenRegId AND d2.deleted = 0 "
			+ "    ORDER BY d2.id DESC LIMIT 1) "
			+ "LEFT JOIN db_iemr.tb_screening scr "
			+ "  ON scr.id = ( "
			+ "    SELECT s2.id FROM db_iemr.tb_screening s2 "
			+ "    WHERE s2.ben_reg_id = m.BenRegId AND s2.deleted = 0 "
			+ "    ORDER BY s2.id DESC LIMIT 1) "
			+ "LEFT JOIN db_identity.i_beneficiarydetails_rmnch rm "
			+ "  ON rm.beneficiaryDetails_RmnchId = ( "
			+ "    SELECT MAX(r2.beneficiaryDetails_RmnchId) "
			+ "    FROM db_identity.i_beneficiarydetails_rmnch r2 "
			+ "    WHERE r2.BeneficiaryRegID = m.BenRegId "
			+ "      AND r2.houseoldId IS NOT NULL "
			+ "      AND (r2.Deleted = 0 OR r2.Deleted IS NULL) "
			+ "  ) "
			+ "LEFT JOIN db_identity.i_householddetails hh "
			+ "  ON hh.houseHoldDetailsId = ( "
			+ "    SELECT MAX(h2.houseHoldDetailsId) "
			+ "    FROM db_identity.i_householddetails h2 "
			+ "    WHERE h2.houseoldId = rm.houseoldId "
			+ "      AND (h2.Deleted = 0 OR h2.Deleted IS NULL) "
			+ "  ) "
			+ "LEFT JOIN db_iemr.m_nikshay_village nv "
			+ "  ON nv.NikshayVillageID = hh.villageid "
			+ " AND (nv.Deleted = 0 OR nv.Deleted IS NULL) "
			+ "LEFT JOIN db_iemr.m_nikshay_facility nf "
			+ "  ON nf.NikshayFacilityID = nv.NikshayFacilityID "
			+ " AND (nf.Deleted = 0 OR nf.Deleted IS NULL) "
			+ "LEFT JOIN db_iemr.m_nikshay_tu nt "
			+ "  ON nt.NikshayTUID = nf.NikshayTUID "
			+ " AND (nt.Deleted = 0 OR nt.Deleted IS NULL) "
			+ "LEFT JOIN db_iemr.m_nikshay_district nd "
			+ "  ON nd.NikshayDistrictID = nt.NikshayDistrictID "
			+ " AND (nd.Deleted = 0 OR nd.Deleted IS NULL) "
			+ "LEFT JOIN db_iemr.m_nikshay_state ns "
			+ "  ON ns.NikshayStateID = nd.NikshayStateID "
			+ " AND (ns.Deleted = 0 OR ns.Deleted IS NULL) "
			+ "WHERE m.Deleted = 0 "
			+ "  AND m.BenRegId IS NOT NULL "
			+ "  AND IFNULL(d.is_death, 0) = 0 "
			+ "  AND ( "
			+ "    conf.benRegID IS NOT NULL "
			+ "    OR IFNULL(sus.is_tb_confirmed, 0) = 1 "
			+ "    OR IFNULL(sus.is_confirmed, 0) = 1 "
			+ "    OR ( "
			+ "      LOWER(IFNULL(COALESCE(diag.truenat_result, sus.sputum_test_result), '')) LIKE '%detect%' "
			+ "      AND LOWER(IFNULL(COALESCE(diag.truenat_result, sus.sputum_test_result), '')) NOT LIKE '%not detect%' "
			+ "    ) "
			+ "  ) "
			+ "  AND IFNULL(NULLIF(TRIM(d.nikshayId), ''), '') = '' "
			+ "  AND IFNULL(NULLIF(TRIM(sus.nikshay_id), ''), '') = '' "
			+ "  AND IFNULL(NULLIF(TRIM(diag.nikshay_id), ''), '') = '' "
			+ "ORDER BY m.BenRegId DESC";

	@Autowired
	private DataSource dataSource;

	/** Returns every beneficiary that is eligible for a Ni-kshay ID. */
	public List<NikshayIdGenerateBeneficiaryDTO> findEligibleBeneficiaries() {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		return jdbcTemplate.query(ELIGIBLE_BENEFICIARIES_SQL, (rs, rowNum) -> mapRow(rs));
	}

	private NikshayIdGenerateBeneficiaryDTO mapRow(ResultSet rs) throws SQLException {
		NikshayIdGenerateBeneficiaryDTO dto = new NikshayIdGenerateBeneficiaryDTO();
		dto.setBeneficiaryId(rs.getObject("beneficiary_id", Long.class));
		dto.setHouseoldId(rs.getObject("houseoldId", Long.class));
		dto.setFirstName(rs.getString("FirstName"));
		dto.setLastName(rs.getString("LastName"));
		dto.setFathersName(rs.getString("FathersName"));
		dto.setAge(rs.getObject("Age", Integer.class));
		dto.setDateOfBirth(rs.getString("DateOfBirth"));
		dto.setGender(rs.getString("Gender"));
		dto.setPrimaryPhone(rs.getString("PrimaryPhone"));
		dto.setAddress(rs.getString("Address"));
		dto.setPincode(rs.getString("Pincode"));
		dto.setArea(rs.getString("Area"));
		dto.setMaritalStatus(rs.getString("MaritalStatus"));
		dto.setOccupation(rs.getString("Occupation"));
		dto.setSocioeconomicStatus(rs.getString("SocioeconomicStatus"));
		dto.setCaste(rs.getString("caste"));
		dto.setKeyPopulation(rs.getString("KeyPopulation"));
		dto.setSymptom(rs.getString("Symptom"));
		dto.setHivStatus(rs.getString("HIVStatus"));
		dto.setResidenceHierarchyId(rs.getObject("ResidenceHierarchyId", Long.class));
		dto.setNikshayVillageId(rs.getObject("nikshay_village_id", Integer.class));
		dto.setNikshayStateId(rs.getObject("nikshay_state_id", Long.class));
		dto.setNikshayDistrictId(rs.getObject("nikshay_district_id", Long.class));
		dto.setNikshayTuId(rs.getObject("nikshay_tu_id", Long.class));
		dto.setSelectedHierarchyId(rs.getObject("SelectedHierarchyId", Long.class));
		return dto;
	}
}
