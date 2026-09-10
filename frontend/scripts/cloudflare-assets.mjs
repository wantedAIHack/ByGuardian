import { mkdir, readFile, writeFile } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const sourceDir = path.dirname(fileURLToPath(import.meta.url))
const cloudflareDir = path.resolve(sourceDir, '../cloudflare')
const shaPattern = /^[0-9a-f]{40}$/

function validateApiOrigin(apiBase) {
  if (typeof apiBase !== 'string' || apiBase.length === 0) {
    throw new Error('VITE_API_BASE must be one canonical HTTPS origin')
  }

  let parsed
  try {
    parsed = new URL(apiBase)
  } catch {
    throw new Error('VITE_API_BASE must be one canonical HTTPS origin')
  }

  if (
    parsed.protocol !== 'https:' ||
    parsed.username !== '' ||
    parsed.password !== '' ||
    parsed.hostname.includes('*') ||
    parsed.hostname.endsWith('.') ||
    apiBase !== parsed.origin
  ) {
    throw new Error('VITE_API_BASE must be one canonical HTTPS origin')
  }

  return parsed.origin
}

function validateBuildSha(buildSha) {
  if (typeof buildSha !== 'string' || !shaPattern.test(buildSha)) {
    throw new Error('build SHA must be exactly forty lowercase hexadecimal characters')
  }
  return buildSha
}

function resolveBuildSha(viteSha, pagesSha) {
  const viteShaSupplied = viteSha !== undefined
  const pagesShaSupplied = pagesSha !== undefined

  if (viteShaSupplied) validateBuildSha(viteSha)
  if (pagesShaSupplied) validateBuildSha(pagesSha)
  if (viteShaSupplied && pagesShaSupplied && viteSha !== pagesSha) {
    throw new Error('VITE_BUILD_SHA and CF_PAGES_COMMIT_SHA must match')
  }
  return viteShaSupplied ? viteSha : pagesSha
}

export async function renderCloudflareAssets({ apiBase, buildSha, outputDir }) {
  const apiOrigin = validateApiOrigin(apiBase)
  const commit = validateBuildSha(buildSha)

  const [headersTemplate, redirects] = await Promise.all([
    readFile(path.join(cloudflareDir, '_headers.template'), 'utf8'),
    readFile(path.join(cloudflareDir, '_redirects'), 'utf8'),
  ])
  const headers = headersTemplate.replace('__API_ORIGIN__', () => apiOrigin)
  const build = `${JSON.stringify({ commit, apiOrigin }, null, 2)}\n`

  await mkdir(outputDir, { recursive: true })
  await Promise.all([
    writeFile(path.join(outputDir, '_headers'), headers),
    writeFile(path.join(outputDir, '_redirects'), redirects),
    writeFile(path.join(outputDir, 'build.json'), build),
  ])
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    await renderCloudflareAssets({
      apiBase: process.env.VITE_API_BASE,
      buildSha: resolveBuildSha(
        process.env.VITE_BUILD_SHA,
        process.env.CF_PAGES_COMMIT_SHA,
      ),
      outputDir: path.resolve(process.cwd(), 'dist'),
    })
  } catch (error) {
    console.error(error instanceof Error ? error.message : error)
    process.exitCode = 1
  }
}
