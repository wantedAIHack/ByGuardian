import assert from 'node:assert/strict'
import { existsSync } from 'node:fs'
import { mkdtemp, readFile, stat } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { spawnSync } from 'node:child_process'

import { renderCloudflareAssets } from './cloudflare-assets.mjs'

const { test } = process.env.VITEST ? await import('vitest') : await import('node:test')
const validSha = '0123456789abcdef0123456789abcdef01234567'
const scriptPath = [
  path.resolve(process.cwd(), 'scripts/cloudflare-assets.mjs'),
  path.resolve(process.cwd(), 'frontend/scripts/cloudflare-assets.mjs'),
].find(existsSync)

async function temporaryOutputDir() {
  const root = await mkdtemp(path.join(tmpdir(), 'cloudflare-assets-'))
  return path.join(root, 'dist')
}

test('renders exact security headers, SPA redirect, and minimal build metadata', async () => {
  const outputDir = await temporaryOutputDir()

  await renderCloudflareAssets({
    apiBase: 'https://api.nextvisit.test',
    buildSha: validSha,
    outputDir,
  })

  const headers = await readFile(path.join(outputDir, '_headers'), 'utf8')
  const redirects = await readFile(path.join(outputDir, '_redirects'), 'utf8')
  const build = JSON.parse(await readFile(path.join(outputDir, 'build.json'), 'utf8'))

  assert.equal(
    headers,
    "/*\n  Content-Security-Policy: default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' https://api.nextvisit.test; manifest-src 'self'; worker-src 'self'\n  Strict-Transport-Security: max-age=31536000; includeSubDomains\n  X-Content-Type-Options: nosniff\n  Referrer-Policy: no-referrer\n  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()\n",
  )
  assert.equal(redirects, '/* /index.html 200\n')
  assert.deepEqual(build, {
    commit: validSha,
    apiOrigin: 'https://api.nextvisit.test',
  })
  assert.deepEqual(Object.keys(build).sort(), ['apiOrigin', 'commit'])
  assert.equal(/connect-src[^\n]*\*/.test(headers), false)
  assert.equal((headers.match(/https:\/\/api\.nextvisit\.test/g) ?? []).length, 1)
})

for (const [name, apiBase] of [
  ['non-HTTPS protocol', 'http://api.nextvisit.test'],
  ['credentials', 'https://user:secret@api.nextvisit.test'],
  ['path', 'https://api.nextvisit.test/v1'],
  ['trailing slash path ambiguity', 'https://api.nextvisit.test/'],
  ['query string', 'https://api.nextvisit.test?region=kr'],
  ['fragment', 'https://api.nextvisit.test#production'],
  ['wildcard host', 'https://*.nextvisit.test'],
  ['hostname case ambiguity', 'https://API.nextvisit.test'],
  ['explicit default port ambiguity', 'https://api.nextvisit.test:443'],
  ['invalid port', 'https://api.nextvisit.test:99999'],
]) {
  test(`rejects API origin with ${name} without creating partial output`, async () => {
    const outputDir = await temporaryOutputDir()

    await assert.rejects(
      renderCloudflareAssets({ apiBase, buildSha: validSha, outputDir }),
      /VITE_API_BASE/,
    )
    await assert.rejects(stat(outputDir), { code: 'ENOENT' })
  })
}

for (const [name, buildSha] of [
  ['missing SHA', undefined],
  ['short SHA', '0123456789abcdef'],
  ['long SHA', `${validSha}0`],
  ['uppercase SHA', '0123456789ABCDEF0123456789ABCDEF01234567'],
  ['non-hex SHA', 'g123456789abcdef0123456789abcdef01234567'],
]) {
  test(`rejects ${name} without creating partial output`, async () => {
    const outputDir = await temporaryOutputDir()

    await assert.rejects(
      renderCloudflareAssets({
        apiBase: 'https://api.nextvisit.test',
        buildSha,
        outputDir,
      }),
      /build SHA/,
    )
    await assert.rejects(stat(outputDir), { code: 'ENOENT' })
  })
}

test('CLI rejects disagreeing Vite and Pages commit SHAs without writing assets', async () => {
  const outputDir = await temporaryOutputDir()
  const result = spawnSync(process.execPath, [scriptPath], {
    cwd: path.dirname(outputDir),
    env: {
      ...process.env,
      VITE_API_BASE: 'https://api.nextvisit.test',
      VITE_BUILD_SHA: validSha,
      CF_PAGES_COMMIT_SHA: 'abcdef0123456789abcdef0123456789abcdef01',
    },
    encoding: 'utf8',
  })

  assert.notEqual(result.status, 0)
  assert.match(result.stderr, /must match/)
  await assert.rejects(stat(outputDir), { code: 'ENOENT' })
})
