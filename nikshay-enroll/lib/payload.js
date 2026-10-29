/**
 * Map one CSV row → AddIndiaTB JSON body.
 *
 * CSV column                → API field
 * -------------------------   ------------------------------------------
 * firstName                 → FirstName
 * middleLastName            → LastName
 * age                       → Age (+ DateOfBirth approx 01-01-{year-age})
 * gender                    → Gender
 * primaryPhone              → PrimaryPhone
 * address                   → Address
 * pincode                   → Pincode
 * area                      → Area
 * maritalStatus             → MaritalStatus
 * occupation                → Occupation
 * socioeconomicStatus       → SocioeconomicStatus
 * symptoms (pipe-separated) → Symptom (comma-separated, trailing comma)
 * hivStatus                 → HIVStatus
 * typeOfCaseFinding         → TypeOfCaseFinding (Passive→full portal label)
 * caste                     → caste (Other→Others)
 * healthFacility (+ path)   → SelectedHierarchyId (resolved from master)
 * village                   → ResidenceHierarchyId (+ ResidenceHierarchyAll)
 * state/district/tu         → used to resolve hierarchy IDs only
 */

const CASE_FINDING = {
  Passive: 'Passive (Routine programme)',
  Active: 'Active (Community screening)'
}

const CASTE = {
  SC: 'SC',
  ST: 'ST',
  Other: 'Others',
  Others: 'Others'
}

function dobFromAge(age) {
  const a = Number(age)
  const year = new Date().getFullYear() - (Number.isFinite(a) ? a : 30)
  return `01-01-${year}`
}

export { dobFromAge }

function symptomsForApi(symptoms) {
  const parts = (symptoms || 'Asymptomatic')
    .split('|')
    .map((s) => s.trim())
    .filter(Boolean)
  const joined = parts.join(',')
  return joined.endsWith(',') ? joined : `${joined},`
}

/**
 * @param {object} row CSV row
 * @param {{ stateId: number, districtId: number, tuId: number, facilityId: number }} path
 * @param {{ id: number, name: string }} village
 * @param {boolean} isPossibleDuplicate
 */
export function buildPayload(row, path, village, isPossibleDuplicate) {
  const today = new Date()
  const dd = String(today.getDate()).padStart(2, '0')
  const mm = String(today.getMonth() + 1).padStart(2, '0')
  const yyyy = today.getFullYear()

  return {
    TypeOfPatient: 'IndiaTbPublic',
    Stage: 'PRESUMPTIVE_OPEN',
    RegistrationDate: `${dd}-${mm}-${yyyy}`,
    SelectedHierarchyId: path.facilityId,
    FirstName: row.firstName,
    LastName: row.middleLastName,
    FathersName: null,
    Age: row.age,
    Gender: row.gender,
    PrimaryPhone: row.primaryPhone,
    SecondaryPhone1: null,
    SecondaryPhone2: null,
    SecondaryPhone3: null,
    Address: row.address,
    Ward: null,
    Taluka: null,
    Landmark: null,
    ResidenceHierarchyId: village.id,
    ResidenceHierarchyAll: [1, path.stateId, path.districtId, path.tuId, village.id],
    Pincode: row.pincode,
    Area: row.area || 'Unknown',
    MaritalStatus: row.maritalStatus || 'Unknown',
    Occupation: row.occupation || 'Unknown',
    SocioeconomicStatus: row.socioeconomicStatus || 'Unknown',
    KeyPopulation: 'Not Applicable',
    VaccinationDate: null,
    Symptom: symptomsForApi(row.symptoms),
    HIVStatus: row.hivStatus || 'Unknown',
    ContactPersonPhone: null,
    ContactPersonName: null,
    ContactPersonAddress: null,
    parentEpisodeId: null,
    IsPossibleDuplicate: isPossibleDuplicate,
    Source: null,
    SourcePatientId: null,
    TypeOfCaseFinding: CASE_FINDING[row.typeOfCaseFinding] || CASE_FINDING.Passive,
    InformantId: null,
    DateOfBirth: row.dateOfBirth || row.dob || dobFromAge(row.age),
    FollowUpsDone: [],
    TbWinReferenceId: null,
    caste: CASTE[row.caste] || CASTE.Other,
    healthId: null,
    healthAddress: null
  }
}
