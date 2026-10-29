import { readFile } from 'node:fs/promises'
import { parse } from 'csv-parse/sync'

export const COLUMNS = [
  'typeOfCaseFinding',
  'caste',
  'firstName',
  'middleLastName',
  'age',
  'gender',
  'primaryPhone',
  'address',
  'state',
  'district',
  'tu',
  'healthFacility',
  'village',
  'pincode',
  'area',
  'maritalStatus',
  'occupation',
  'socioeconomicStatus',
  'symptoms',
  'hivStatus'
]

const REQUIRED = COLUMNS.filter((c) => c !== 'state' && c !== 'district' && c !== 'tu')

/** @returns {{ path: string, rows: object[], errors: string[] }} */
export async function readCsv(path) {
  try {
    const raw = await readFile(path, 'utf8')
    const records = parse(raw, { columns: true, skip_empty_lines: true, trim: true })
    const rows = records.map((r) => {
      const row = {}
      for (const c of COLUMNS) row[c] = (r[c] ?? '').toString().trim()
      if (r.benRegId != null) row.benRegId = r.benRegId.toString().trim()
      // Optional numeric overrides (skip name→id lookup when set)
      if (r.villageId != null && String(r.villageId).trim()) row.villageId = String(r.villageId).trim()
      if (r.healthFacilityId != null && String(r.healthFacilityId).trim()) {
        row.healthFacilityId = String(r.healthFacilityId).trim()
      }
      // Optional DOB for stricter dedup (else derived from age as 01-01-{year-age})
      if (r.dateOfBirth != null && String(r.dateOfBirth).trim()) {
        row.dateOfBirth = String(r.dateOfBirth).trim()
      } else if (r.dob != null && String(r.dob).trim()) {
        row.dob = String(r.dob).trim()
      }
      return row
    })

    const errors = []
    if (!records.length) {
      errors.push('The file has no data rows.')
      return { path, rows, errors }
    }
    const header = Object.keys(records[0])
    const missing = COLUMNS.filter((c) => !header.includes(c))
    if (missing.length) errors.push(`Missing required columns: ${missing.join(', ')}`)

    rows.forEach((r, i) => {
      const line = i + 2
      for (const col of REQUIRED) {
        if (!r[col]) errors.push(`Row ${line}: ${col} is empty.`)
      }
      if (r.age && (!/^\d{1,2}$/.test(r.age) || +r.age < 1 || +r.age > 99)) {
        errors.push(`Row ${line}: age must be 1-99 (got "${r.age}").`)
      }
      if (r.primaryPhone && !/^[1-9]\d{9}$/.test(r.primaryPhone)) {
        errors.push(`Row ${line}: primaryPhone must be 10 digits, no leading zero.`)
      }
      if (r.pincode && !/^\d{6}$/.test(r.pincode)) {
        errors.push(`Row ${line}: pincode must be 6 digits.`)
      }
    })

    return { path, rows, errors }
  } catch (e) {
    return { path, rows: [], errors: [`Could not read file: ${e.message}`] }
  }
}
