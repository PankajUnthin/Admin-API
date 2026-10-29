/**
 * UAT Ni-kshay bulk enroll (simple Node ESM).
 *
 *   cp .env.example .env   # set NIKSHAY_USER / NIKSHAY_PASS
 *   node enroll.js sample/sample.csv [uat|production]
 *
 * Flow (login once):
 *   1) Playwright login → session cookies / JWT
 *   2) For each CSV row:
 *        GET  /Api/Patients/GetPossibleDuplicates
 *        if DOB+name+mobile+gender all match → copy existing Nikshay ID (skip create)
 *        else POST /api/patients/AddIndiaTB
 *   3) Write <input>.api-results.csv
 */
import { createInterface } from 'node:readline'
import { readFile, writeFile } from 'node:fs/promises'
import { gunzipSync } from 'node:zlib'
import { join, dirname, basename, extname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { stringify } from 'csv-stringify/sync'
import { chromium } from 'playwright'
import { ENV, DEFAULT_ENV } from './lib/config.js'
import { login } from './lib/auth.js'
import { readCsv, COLUMNS } from './lib/csv.js'
import { buildPayload, dobFromAge } from './lib/payload.js'
import { loadEnvFile } from './lib/env.js'

loadEnvFile()

const __dirname = dirname(fileURLToPath(import.meta.url))
const JWT_NAMES = ['SSOAuthorizationCookieBeta', 'SSOAuthorizationCookie']

const RESULT_COLUMNS = [
  'benRegId',
  ...COLUMNS,
  'generatedId',
  'newlyGenerated',
  'status',
  'possibleDuplicateOf',
  'durationSec',
  'error'
]

function ts() {
  return new Date().toISOString().slice(11, 23) // HH:mm:ss.SSS
}

/** Progress logger — always stderr so results CSV stays clean on stdout if redirected. */
function log(step, message = '') {
  const suffix = message ? ` ${message}` : ''
  console.error(`[${ts()}] ${step}${suffix}`)
}

function norm(s) {
  return (s || '').trim().toLowerCase()
}

function normalizeText(text) {
  return text.replace(/\s+/g, ' ').trim().toLowerCase()
}

function containsName(haystack, fullName) {
  if (!fullName) return false
  const text = normalizeText(haystack)
  if (text.includes(fullName)) return true
  const words = new Set(text.split(' '))
  return fullName.split(' ').every((word) => words.has(word))
}

function recordMatchesName(record, fullName) {
  const values = Object.values(record).filter((v) => typeof v === 'string')
  return values.some((v) => containsName(v, fullName)) || containsName(values.join(' '), fullName)
}

/** Normalize DOB strings like 01-01-1991, 1991-01-01, 1/1/1991 → yyyy-mm-dd for compare. */
function normalizeDob(value) {
  if (value == null || value === '') return ''
  const s = String(value).trim()
  // already ISO-ish
  let m = s.match(/^(\d{4})[-\/](\d{1,2})[-\/](\d{1,2})/)
  if (m) return `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`
  // dd-mm-yyyy or dd/mm/yyyy
  m = s.match(/^(\d{1,2})[-\/](\d{1,2})[-\/](\d{4})/)
  if (m) return `${m[3]}-${m[2].padStart(2, '0')}-${m[1].padStart(2, '0')}`
  // bare year
  m = s.match(/^(\d{4})$/)
  if (m) return `${m[1]}-01-01`
  return normalizeText(s)
}

function recordDob(record) {
  return (
    record.DateOfBirth ||
    record.dateOfBirth ||
    record.Dob ||
    record.DOB ||
    record.BirthDate ||
    ''
  )
}

function recordPhone(record) {
  return String(
    record.PrimaryPhoneNumber ?? record.PrimaryPhone ?? record.primaryPhone ?? ''
  ).replace(/\D/g, '')
}

function recordGender(record) {
  return normalizeText(String(record.Gender ?? record.gender ?? ''))
}

function recordAge(record) {
  const a = record.Age ?? record.age
  return a == null || a === '' ? null : Number(a)
}

/**
 * True duplicate = same name + mobile + gender + DOB (age used if DOB missing on either side).
 * Portal GetPossibleDuplicates is usually phone+gender; we tighten the match here.
 */
function evaluateDuplicates(body, row) {
  const data = body?.Data ?? []
  if (!data.length) return { existingIds: [] }
  const existingIds = data.map((m) => String(m.PatientId))

  const fullName = normalizeText(`${row.firstName} ${row.middleLastName}`)
  const phone = String(row.primaryPhone || '').replace(/\D/g, '')
  const gender = normalizeText(row.gender || '')
  const rowDob = normalizeDob(row.dateOfBirth || row.dob || dobFromAge(row.age))
  const rowAge = Number(row.age)

  const hit = data.find((m) => {
    if (!recordMatchesName(m, fullName)) return false
    if (recordGender(m) !== gender) return false
    if (recordPhone(m) !== phone) return false

    const apiDob = normalizeDob(recordDob(m))
    if (apiDob && rowDob) return apiDob === rowDob

    // Fall back to age when API/CSV has no usable DOB
    const apiAge = recordAge(m)
    if (apiAge != null && Number.isFinite(rowAge)) return apiAge === rowAge

    // If neither side has DOB/age to compare, do not treat as a firm match
    return false
  })

  return { existingIds, matchedId: hit ? String(hit.PatientId) : undefined }
}

function prompt(question) {
  const rl = createInterface({ input: process.stdin, output: process.stdout })
  return new Promise((resolve) => {
    rl.question(question, (answer) => {
      rl.close()
      resolve(answer)
    })
  })
}

function toCookieHeader(cookies) {
  return cookies.map((c) => `${c.name}=${c.value}`).join('; ')
}

async function apiFetch(url, cookieHeader, init = {}) {
  const origin = new URL(url).origin
  const res = await fetch(url, {
    ...init,
    headers: {
      accept: '*/*',
      'content-type': 'application/json; charset=UTF-8',
      origin,
      referer: `${origin}/`,
      'x-requested-with': 'XMLHttpRequest',
      'user-agent':
        'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
      cookie: cookieHeader,
      ...(init.headers || {})
    }
  })
  const text = await res.text()
  let body = text
  try {
    body = JSON.parse(text)
  } catch {
    /* keep text */
  }
  return { status: res.status, body }
}

async function loadMaster() {
  const gz = join(__dirname, 'resources/location-master.json.gz')
  return JSON.parse(gunzipSync(await readFile(gz)).toString('utf8'))
}

function findHierarchy(master, row) {
  // Optional CSV override — same idea as villageId (yesterday used SelectedHierarchyId 297655)
  const explicitFac = Number(row.healthFacilityId || '')
  if (Number.isFinite(explicitFac) && explicitFac > 0) {
    const st = master.states.find((s) => norm(s.name) === norm(row.state))
    const d = st?.districts.find((x) => norm(x.name) === norm(row.district))
    const t = d?.tus.find((x) => norm(x.name) === norm(row.tu))
    return {
      stateId: st?.id ?? 1,
      districtId: d?.id ?? 0,
      tuId: t?.id ?? 0,
      facilityId: explicitFac
    }
  }

  const st = master.states.find((s) => norm(s.name) === norm(row.state))
  if (!st) throw new Error(`State "${row.state}" not in location master`)
  const d = st.districts.find((x) => norm(x.name) === norm(row.district))
  if (!d) throw new Error(`District "${row.district}" not found in ${st.name}`)
  const t = d.tus.find((x) => norm(x.name) === norm(row.tu))
  if (!t) throw new Error(`TU "${row.tu}" not found in ${d.name}`)
  const f = t.facilities.find((x) => norm(x.name) === norm(row.healthFacility))
  if (!f) throw new Error(`Health Facility "${row.healthFacility}" not found under TU ${t.name}`)
  return {
    stateId: st.id,
    districtId: d.id,
    tuId: t.id,
    facilityId: f.id
  }
}

async function fetchVillages(origin, cookieHeader, facilityId, cache) {
  if (cache.has(facilityId)) return cache.get(facilityId)
  const url =
    `${origin}/API/Hierarchy/GetHierarchyChildrenKeyValue` +
    `?id=${facilityId}&type=VILLAGE&fromAssociation=true&onlyActiveHierarchies=true`
  const res = await apiFetch(url, cookieHeader, { method: 'GET' })
  if (res.status >= 400) throw new Error(`Village list HTTP ${res.status}`)
  const data = res.body?.Data ?? []
  const villages = parseVillageRows(data)
  cache.set(facilityId, villages)
  return villages
}

function parseVillageRows(data) {
  const villages = []
  for (const row of data || []) {
    const name = String(row.Name ?? row.Value ?? row.name ?? row.value ?? '').trim()
    const id = Number(row.Id ?? row.Key ?? row.id ?? row.key)
    if (!name || !Number.isFinite(id)) continue
    villages.push({ id, name })
  }
  return villages
}

/** Pull villages from a descendents/children response (array or { Data }). */
function parseHierarchyList(body) {
  const rows = Array.isArray(body) ? body : body?.Data ?? body?.data ?? []
  return parseVillageRows(rows)
}

/**
 * Resolve village id from CSV name.
 * Order:
 *   1) optional row.villageId / row.ResidenceHierarchyId
 *   2) facility association list
 *   3) facility children without association filter
 *   4) TU-level villages
 *   5) district-level villages
 *   6) partial name contains match on any list we loaded
 */
async function resolveVillageId(origin, cookieHeader, path, row, cache, logRow) {
  const villageName = row.village
  const explicit = Number(row.villageId || row.ResidenceHierarchyId || '')
  if (Number.isFinite(explicit) && explicit > 0) {
    logRow(`villageId=${explicit} (from CSV villageId column)`)
    return { id: explicit, name: villageName }
  }

  const lists = []

  logRow('fetch village list for facility (association)')
  const assoc = await fetchVillages(origin, cookieHeader, path.facilityId, cache)
  lists.push(['association', assoc])
  logRow(`association list: ${assoc.length} village(s)`)
  let hit = matchVillage(assoc, villageName)
  if (hit) {
    logRow(`villageId=${hit.id} name="${hit.name}" (association exact)`)
    return hit
  }

  // Same facility, no association filter — sometimes returns a wider set.
  const noAssocKey = `fac-noassoc:${path.facilityId}`
  if (!cache.has(noAssocKey)) {
    const url =
      `${origin}/API/Hierarchy/GetHierarchyChildrenKeyValue` +
      `?id=${path.facilityId}&type=VILLAGE&onlyActiveHierarchies=true`
    const res = await apiFetch(url, cookieHeader, { method: 'GET' })
    cache.set(noAssocKey, res.status < 400 ? parseHierarchyList(res.body) : [])
  }
  const noAssoc = cache.get(noAssocKey)
  lists.push(['facility-children', noAssoc])
  logRow(`facility children (no association): ${noAssoc.length}`)
  hit = matchVillage(noAssoc, villageName)
  if (hit) {
    logRow(`villageId=${hit.id} name="${hit.name}" (facility children)`)
    return hit
  }

  for (const [label, parentId, level] of [
    ['tu', path.tuId, 6],
    ['district', path.districtId, 6]
  ]) {
    const key = `${label}:${parentId}`
    if (!cache.has(key)) {
      const url =
        `${origin}/API/Hierarchy/GetHierarchyDescendentsAtSpecificLevel` +
        `?parentId=${parentId}&level=${level}&types=VILLAGE`
      const res = await apiFetch(url, cookieHeader, { method: 'GET' })
      const list = res.status < 400 ? parseHierarchyList(res.body) : []
      // Also try untyped if empty (some envs reject types=)
      if (!list.length) {
        const res2 = await apiFetch(
          `${origin}/API/Hierarchy/GetHierarchyDescendentsAtSpecificLevel?parentId=${parentId}&level=${level}`,
          cookieHeader,
          { method: 'GET' }
        )
        cache.set(key, res2.status < 400 ? parseHierarchyList(res2.body) : [])
      } else {
        cache.set(key, list)
      }
    }
    const list = cache.get(key)
    lists.push([label, list])
    logRow(`${label} search parentId=${parentId}: ${list.length} village(s)`)
    hit = matchVillage(list, villageName)
    if (hit) {
      logRow(`villageId=${hit.id} name="${hit.name}" (from ${label})`)
      return hit
    }
  }

  // Partial / contains match across everything we already fetched
  const n = norm(villageName)
  for (const [label, list] of lists) {
    const partial = list.find(
      (v) => norm(v.name).includes(n) || n.includes(norm(v.name))
    )
    if (partial) {
      logRow(`villageId=${partial.id} name="${partial.name}" (partial match on ${label})`)
      return partial
    }
  }

  const sample = (lists.find(([, l]) => l.length)?.[1] || [])
    .slice(0, 10)
    .map((v) => v.name)
    .join(' | ')
  throw new Error(
    `Village "${villageName}" not found. Facility "${row.healthFacility}" (id=${path.facilityId}) OK. ` +
      `Tried association=${assoc.length}, facility-children, tu, district. ` +
      (sample ? `Sample names: ${sample}` : 'All village lists were empty (UAT may not expose district villages for this login).') +
      ` Tip: add a villageId column to the CSV (yesterday's Asaraladesa id was 715778).`
  )
}

function matchVillage(villages, villageName) {
  const n = norm(villageName)
  const exact = villages.find((v) => norm(v.name) === n)
  if (exact) return exact
  if (n === 'others') return villages.find((v) => norm(v.name) === 'others') || null
  return null
}

function resultsPath(inputPath) {
  const dir = dirname(inputPath)
  const name = basename(inputPath, extname(inputPath))
  return join(dir, `${name}.api-results.csv`)
}

async function writeResults(outputPath, rows, results) {
  const records = rows.map((r, i) => ({
    benRegId: r.benRegId ?? '',
    ...r,
    generatedId: results[i]?.generatedId ?? '',
    newlyGenerated: results[i]?.newlyGenerated ?? '',
    status: results[i]?.status ?? 'pending',
    possibleDuplicateOf: results[i]?.possibleDuplicateOf ?? '',
    durationSec: results[i]?.durationSec ?? '',
    error: results[i]?.error ?? ''
  }))
  await writeFile(outputPath, stringify(records, { header: true, columns: RESULT_COLUMNS }), 'utf8')
}

/**
 * Programmatic enrollment (CLI + HTTP server both call this).
 *
 * @param {{ inputPath: string, username: string, password: string, envName?: string, printTable?: boolean }} opts
 * @returns {Promise<{ outPath: string, resultsCsv: string, succeeded: number, skipped: number, failed: number, total: number }>}
 */
export async function runEnrollment(opts) {
  const {
    inputPath,
    username,
    password,
    envName: envNameOpt,
    printTable = false
  } = opts

  if (!inputPath) throw new Error('inputPath is required')
  if (!username || !password) throw new Error('NIKSHAY_USER and NIKSHAY_PASS are required')

  const envName = envNameOpt || process.env.NIKSHAY_ENV || DEFAULT_ENV
  const urls = ENV[envName] || ENV[DEFAULT_ENV]
  const origin = urls.origin
  const outPath = resultsPath(inputPath)

  log('CONFIG', `portal=${envName}`)

  log('STEP 1/4', `Read CSV → ${inputPath}`)
  const loaded = await readCsv(inputPath)
  if (loaded.errors.length) {
    const msg = loaded.errors.slice(0, 10).join('; ')
    throw new Error(`Input validation failed: ${msg}`)
  }
  log('OK', `${loaded.rows.length} valid row(s)`)

  if (loaded.rows.length === 0) {
    const emptyCsv = stringify([], { header: true, columns: RESULT_COLUMbodyNS })
    await writeFile(outPath, emptyCsv, 'utf8')
    return {
      outPath,
      resultsCsv: emptyCsv,
      succeeded: 0,
      skipped: 0,
      failed: 0,
      total: 0,
      rows: []
    }
  }

  log('STEP 2/4', 'Load location master')
  const master = await loadMaster()
  log('OK', `master loaded (${master.states?.length ?? '?'} states)`)

  const headless = process.env.HEADLESS !== '0'
  log('STEP 3/4', `Login → ${envName} (${origin}) [${headless ? 'headless' : 'headed'}]`)
  log('  …', `user=${username}`)
  // Prefer system Google Chrome when present — avoids Playwright's install-deps on this host.
  const useSystemChrome = process.env.PLAYWRIGHT_CHROME !== '0'
  const launchOpts = {
    headless,
    ...(useSystemChrome ? { channel: 'chrome' } : {})
  }
  log('  …', useSystemChrome ? 'browser=system Chrome (channel=chrome)' : 'browser=Playwright Chromium')
  const browser = await chromium.launch(launchOpts)
  const context = await browser.newContext({
    userAgent:
      'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36',
    viewport: { width: 1280, height: 800 }
  })
  const page = await context.newPage()
  page.setDefaultTimeout(30_000)

  let cookieHeader = ''
  try {
    log('  …', 'opening SSO login page')
    await login(page, username, password, urls.loginUrl, urls.homeUrlGlob)
    log('  …', 'SSO login succeeded')
    if (!page.url().startsWith(origin)) {
      log('  …', `navigating to ${origin}/Home`)
      await page.goto(`${origin}/Home`, { waitUntil: 'domcontentloaded' })
    }
    const cookies = await context.cookies(origin)
    cookieHeader = toCookieHeader(cookies)
    const jwt = cookies.find((c) => JWT_NAMES.includes(c.name))
    log(
      'OK',
      jwt
        ? `session ready (${cookies.length} cookies); JWT=${jwt.name}`
        : `session ready (${cookies.length} cookies)`
    )
  } finally {
    log('  …', 'closing browser')
    await browser.close().catch(() => undefined)
  }

  const total = loaded.rows.length
  log('STEP 4/4', `Process ${total} row(s) → ${outPath}`)
  const results = loaded.rows.map(() => ({
    generatedId: '',
    newlyGenerated: '',
    status: 'failed',
    possibleDuplicateOf: '',
    error: 'pending',
    durationSec: ''
  }))
  await writeResults(outPath, loaded.rows, results)
  log('OK', 'results file initialized')

  const villageCache = new Map()
  let succeeded = 0
  let skipped = 0
  let failed = 0

  for (let i = 0; i < loaded.rows.length; i++) {
    const row = loaded.rows[i]
    const n = i + 1
    const started = Date.now()
    const who = `${row.firstName} ${row.middleLastName} / ${row.gender} / ${row.primaryPhone}`
    log(`ROW ${n}/${total}`, who)

    try {
      log(`ROW ${n}`, 'resolve facility from location master')
      const path = findHierarchy(master, row)
      log(
        `ROW ${n}`,
        `facility OK id=${path.facilityId} name="${row.healthFacility}" ` +
          `(state=${path.stateId} district=${path.districtId} tu=${path.tuId})`
      )

      const village = await resolveVillageId(
        origin,
        cookieHeader,
        path,
        row,
        villageCache,
        (msg) => log(`ROW ${n}`, msg)
      )
      const rowDob = row.dateOfBirth || row.dob || dobFromAge(row.age)
      log(`ROW ${n}`, 'GetPossibleDuplicates — match on DOB + name + mobile + gender')
      const dupQs = new URLSearchParams({
        PrimaryPhone: row.primaryPhone,
        Gender: row.gender,
        FirstName: row.firstName,
        LastName: row.middleLastName,
        Age: row.age || '',
        DateOfBirth: rowDob
      })
      log(
        `ROW ${n}`,
        `dedup query name=${row.firstName} ${row.middleLastName} gender=${row.gender} ` +
          `mobile=${row.primaryPhone} age=${row.age} dob=${rowDob}`
      )
      const dup = await apiFetch(
        `${origin}/Api/Patients/GetPossibleDuplicates?${dupQs}`,
        cookieHeader,
        { method: 'GET' }
      )
      if (dup.status === 401 || dup.status === 403) {
        throw new Error(`Session rejected (${dup.status}) — re-login required`)
      }
      if (dup.status >= 400) throw new Error(`GetPossibleDuplicates HTTP ${dup.status}`)

      const dupData = dup.body?.Data ?? []
      log(`ROW ${n}`, `dedup HTTP ${dup.status} Success=${dup.body?.Success ?? '?'} hits=${dupData.length}`)
      if (dupData.length === 0) {
        log(`ROW ${n}`, 'dedup response: [] (no matches)')
      } else {
        for (const rec of dupData) {
          log(
            `ROW ${n}`,
            `dedup hit PatientId=${rec.PatientId}` +
              ` name="${[rec.FirstName, rec.LastName].filter(Boolean).join(' ')}"` +
              ` gender=${rec.Gender ?? '?'}` +
              ` phone=${rec.PrimaryPhoneNumber ?? rec.PrimaryPhone ?? '?'}` +
              ` age=${rec.Age ?? '?'}` +
              ` dob=${rec.DateOfBirth ?? rec.Dob ?? '?'}` +
              ` stage=${rec.Stage ?? '?'}`
          )
        }
        if (process.env.DEBUG_DEDUP === '1') {
          log(`ROW ${n} DEDUP_RAW`, JSON.stringify(dup.body))
        }
      }

      const { existingIds, matchedId } = evaluateDuplicates(dup.body, row)
      log(
        `ROW ${n}`,
        existingIds.length
          ? `dedup decision: NikshayId=[${existingIds.join(', ')}] ` +
              `fullMatch(dob+name+mobile+gender)=${matchedId ?? 'none'} → ` +
              `${matchedId ? 'SKIP (copy ID)' : 'CREATE (not all of dob/name/mobile/gender matched)'}`
          : 'dedup decision: no hits → CREATE'
      )

      if (matchedId) {
        results[i] = {
          generatedId: matchedId,
          newlyGenerated: 'no',
          status: 'skipped',
          possibleDuplicateOf: existingIds.join(' '),
          error: '',
          durationSec: ((Date.now() - started) / 1000).toFixed(1)
        }
        skipped++
        log(`ROW ${n} SKIP`, `copied existing Nikshay ID ${matchedId} (${results[i].durationSec}s)`)
      } else {
        if (existingIds.length) {
          log(`ROW ${n}`, 'shared-phone only — will create with IsPossibleDuplicate=true')
        }
        const payload = buildPayload(row, path, village, existingIds.length > 0)
        if (process.env.DEBUG_PAYLOAD === '1') {
          log(`ROW ${n} PAYLOAD`, JSON.stringify(payload))
        }
        log(`ROW ${n}`, 'POST AddIndiaTB')
        const add = await apiFetch(`${origin}/api/patients/AddIndiaTB`, cookieHeader, {
          method: 'POST',
          body: JSON.stringify(payload)
        })
        const body = add.body
        if (add.status >= 400 || !body?.Success || body.PatientId == null) {
          throw new Error(
            body?.ErrorMessage ||
              body?.Error ||
              `AddIndiaTB HTTP ${add.status}: ${JSON.stringify(body).slice(0, 200)}`
          )
        }
        results[i] = {
          generatedId: String(body.PatientId),
          newlyGenerated: 'yes',
          status: 'success',
          possibleDuplicateOf: existingIds.join(' '),
          error: '',
          durationSec: ((Date.now() - started) / 1000).toFixed(1)
        }
        succeeded++
        log(`ROW ${n} OK`, `created Nikshay ID ${body.PatientId} (${results[i].durationSec}s)`)
      }
    } catch (e) {
      results[i] = {
        generatedId: '',
        newlyGenerated: '',
        status: 'failed',
        possibleDuplicateOf: '',
        error: e.message.split('\n')[0],
        durationSec: ((Date.now() - started) / 1000).toFixed(1)
      }
      failed++
      log(`ROW ${n} FAIL`, `${results[i].error} (${results[i].durationSec}s)`)
    }

    log(`ROW ${n}`, `write progress → ${outPath}  [ok=${succeeded} skip=${skipped} fail=${failed}]`)
    await writeResults(outPath, loaded.rows, results)
  }

  log('DONE', `success=${succeeded} skipped=${skipped} failed=${failed} → ${outPath}`)

  if (printTable) {
    const table = loaded.rows.map((r, i) => ({
      '#': i + 1,
      benRegId: r.benRegId || '',
      name: `${r.firstName} ${r.middleLastName}`.trim(),
      gender: r.gender,
      mobile: r.primaryPhone,
      nikshayId: results[i]?.generatedId || '',
      newlyGenerated: results[i]?.newlyGenerated || '',
      status: results[i]?.status || '',
      possibleDuplicateOf: results[i]?.possibleDuplicateOf || '',
      error: results[i]?.error || '',
      sec: results[i]?.durationSec || ''
    }))
    console.error('')
    log('RESULTS', 'summary table')
    console.table(table)
  }

  const resultsCsv = await readFile(outPath, 'utf8')
  const rows = loaded.rows.map((r, i) => ({
    benRegId: r.benRegId || '',
    firstName: r.firstName,
    middleLastName: r.middleLastName,
    gender: r.gender,
    primaryPhone: r.primaryPhone,
    generatedId: results[i]?.generatedId || '',
    newlyGenerated: results[i]?.newlyGenerated || '',
    status: results[i]?.status || '',
    possibleDuplicateOf: results[i]?.possibleDuplicateOf || '',
    error: results[i]?.error || '',
    durationSec: results[i]?.durationSec || ''
  }))
  return { outPath, resultsCsv, succeeded, skipped, failed, total, rows }
}

async function main() {
  const argv = process.argv.slice(2)
  const envFlag = argv.find((a) => a === 'uat' || a === 'beta' || a === 'production')
  const inputPath = argv.find((a) => a !== 'uat' && a !== 'beta' && a !== 'production')
  if (!inputPath) {
    console.error('Usage: node enroll.js <input.csv> [uat|production]')
    console.error('Credentials: set NIKSHAY_USER / NIKSHAY_PASS in .env (see .env.example)')
    process.exit(1)
  }

  let username = process.env.NIKSHAY_USER
  let password = process.env.NIKSHAY_PASS
  const credSource =
    username && password ? '.env / environment' : 'prompt (missing from .env)'
  if (!username) username = await prompt('Username: ')
  if (!password) password = await prompt('Password: ')
  if (!username || !password) {
    console.error('Need NIKSHAY_USER and NIKSHAY_PASS (in .env or environment).')
    process.exit(1)
  }

  const envName =
    envFlag === 'production'
      ? 'production'
      : envFlag === 'beta'
        ? 'uat'
        : envFlag || process.env.NIKSHAY_ENV || DEFAULT_ENV

  log('CONFIG', `creds=${credSource}; portal=${envName}`)
  await runEnrollment({
    inputPath,
    username,
    password,
    envName,
    printTable: true
  })
}

const isCli =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isCli) {
  main().catch((e) => {
    log('FATAL', e.message)
    process.exit(1)
  })
}
