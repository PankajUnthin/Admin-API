/**
 * CLI for MMU-API: run enrollment and print ONE JSON object on stdout.
 *
 *   node enroll-json.js --csv /path/in.csv --env uat
 *   Env: NIKSHAY_USER, NIKSHAY_PASS (required)
 *
 * Progress logs go to stderr; stdout is JSON only.
 */
import { runEnrollment } from './enroll.js'
import { loadEnvFile } from './lib/env.js'

loadEnvFile()

function arg(name) {
  const i = process.argv.indexOf(`--${name}`)
  return i >= 0 ? process.argv[i + 1] : undefined
}

const inputPath = arg('csv')
const envName = arg('env') || process.env.NIKSHAY_ENV || 'uat'
const username = process.env.NIKSHAY_USER
const password = process.env.NIKSHAY_PASS

if (!inputPath) {
  console.error('Usage: NIKSHAY_USER=… NIKSHAY_PASS=… node enroll-json.js --csv file.csv [--env uat|production]')
  process.exit(2)
}
if (!username || !password) {
  console.error('NIKSHAY_USER and NIKSHAY_PASS are required')
  process.exit(2)
}

try {
  const result = await runEnrollment({
    inputPath,
    username,
    password,
    envName,
    printTable: false
  })
  process.stdout.write(
    JSON.stringify({
      succeeded: result.succeeded,
      skipped: result.skipped,
      failed: result.failed,
      total: result.total,
      rows: result.rows
    })
  )
} catch (e) {
  console.error(e.message || e)
  process.stdout.write(JSON.stringify({ error: e.message || String(e) }))
  process.exit(1)
}
