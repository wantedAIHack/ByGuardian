#!/bin/sh
set -eu

repo_root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
node --input-type=commonjs - "$repo_root" "${1:-}" 3<&0 <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');
const root = process.argv[2];
const mode = process.argv[3];
const fail = message => { throw new Error(message); };
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const assert = (condition, message) => { if (!condition) fail(message); };

function checkArtifacts(source) {
  const xml = source.replace(/<!--[\s\S]*?-->/g, '');
  assert(/<verify-metadata>true<\/verify-metadata>/.test(xml), 'metadata verification must be enabled');
  assert(!/<(?:trusted-artifacts|ignored-keys|trust|md5|sha1|sha512)\b/.test(xml), 'verification exceptions and weaker/non-SHA256 entries are forbidden');
  const artifacts = [...xml.matchAll(/<artifact\s+name="[^"]+">([\s\S]*?)<\/artifact>/g)];
  assert(artifacts.length > 0, 'artifact SHA256 verification entries are required');
  assert(artifacts.length === (xml.match(/<artifact\b/g) || []).length, 'empty or malformed artifact verification entry');
  for (const [, body] of artifacts) {
    assert(/<sha256\s+value="[a-f0-9]{64}"(?:\s[^>]*)?\/>/.test(body), 'every artifact must have a strict SHA256 checksum');
    assert(!/<sha256\s+value="(?![a-f0-9]{64}")/.test(body), 'invalid SHA256 checksum');
  }
  return artifacts.length;
}

function checkDiff(paths) {
  const changed = new Set(paths.filter(Boolean));
  const has = name => changed.has(name);
  const manifest = 'frontend/package.json';
  const npmLock = 'frontend/package-lock.json';
  assert(has(manifest) === has(npmLock), 'frontend manifest and package-lock must change together');
  const verification = 'backend/gradle/verification-metadata.xml';
  const settings = has('backend/settings.gradle.kts');
  for (const project of ['api', 'engine']) {
    const build = has(`backend/${project}/build.gradle.kts`) || settings;
    const lock = has(`backend/${project}/gradle.lockfile`);
    assert(!build || (lock && has(verification)), `${project} build change requires its lock and verification metadata`);
    assert(!lock || build, `${project} lock change requires its owning build file`);
  }
  assert(!has(verification) || settings || has('backend/api/build.gradle.kts') || has('backend/engine/build.gradle.kts'),
    'verification metadata change requires an owning build file');
}

function fixtures() {
  const verification = 'backend/gradle/verification-metadata.xml';
  const cases = [
    [[], true],
    [['docs/plan.md'], true],
    [['frontend/package.json'], false],
    [['frontend/package-lock.json'], false],
    [['frontend/package.json', 'frontend/package-lock.json'], true],
    [[verification], false],
    [['backend/settings.gradle.kts'], false],
    [['backend/settings.gradle.kts', 'backend/api/gradle.lockfile', 'backend/engine/gradle.lockfile', verification], true],
  ];
  for (const project of ['api', 'engine']) {
    const build = `backend/${project}/build.gradle.kts`;
    const lock = `backend/${project}/gradle.lockfile`;
    const otherLock = `backend/${project === 'api' ? 'engine' : 'api'}/gradle.lockfile`;
    cases.push([[build], false], [[lock], false], [[build, lock], false],
      [[build, verification], false], [[build, otherLock, verification], false], [[build, lock, verification], true]);
  }
  for (const [paths, expected] of cases) {
    let passed = true;
    try { checkDiff(paths); } catch { passed = false; }
    assert(passed === expected, `diff fixture failed: ${paths.join(', ')}`);
  }
  console.log(`dependency contract: ${cases.length} diff fixtures passed`);
  const valid = '<verify-metadata>true</verify-metadata><artifact name="fixture.jar"><sha256 value="' + 'a'.repeat(64) + '"/></artifact>';
  const artifactCases = [
    [valid, true],
    [valid + '<artifact name="empty.jar"/>', false],
    [valid.replace(/<sha256[^>]+\/>/, ''), false],
    [valid.replace('a'.repeat(64), 'bad-digest'), false],
    [valid + '<trusted-artifacts/>', false],
    [valid + '<ignored-keys/>', false],
    [valid.replace('true', 'false'), false],
    [valid.replace('sha256', 'sha1'), false],
  ];
  for (const [xml, expected] of artifactCases) {
    let passed = true;
    try { checkArtifacts(xml); } catch { passed = false; }
    assert(passed === expected, 'artifact verification fixture failed');
  }
  console.log(`dependency contract: ${artifactCases.length} artifact fixtures passed`);
}

try {
  if (mode === '--diff-stdin') {
    // The shell heredoc supplies this program; fd 3 preserves the caller's paths.
    checkDiff(fs.readFileSync(3, 'utf8').split(/\r?\n/));
    console.log('dependency contract: changed-file ownership passed');
  } else {
    assert(mode === '' || mode === '--fixtures', 'unknown contract mode');
    fixtures();
    if (mode === '--fixtures') process.exit(0);
    const manifest = JSON.parse(read('frontend/package.json'));
    const lock = JSON.parse(read('frontend/package-lock.json'));
    assert(lock.lockfileVersion === 3 && lock.packages && lock.packages[''], 'frontend requires an npm v3 package lock');
    assert(lock.name === manifest.name && lock.packages[''].name === manifest.name, 'package-lock owner mismatch');
    for (const key of ['dependencies', 'devDependencies', 'optionalDependencies', 'engines']) {
      const normalize = value => JSON.stringify(Object.entries(value || {}).sort(([a], [b]) => a.localeCompare(b)));
      assert(normalize(manifest[key]) === normalize(lock.packages[''][key]), `package-lock root ${key} drift`);
    }
    const npmLocks = execFileSync('git', ['ls-files', '*package-lock.json'], { cwd: root, encoding: 'utf8' }).trim().split('\n');
    assert(npmLocks.length === 1 && npmLocks[0] === 'frontend/package-lock.json', 'only frontend owns the npm package lock');
    for (const project of ['api', 'engine']) {
      const build = read(`backend/${project}/build.gradle.kts`).replace(/\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, '');
      assert(/dependencyLocking\s*\{\s*lockAllConfigurations\(\)/.test(build), `${project}: lockAllConfigurations() is required`);
      const lines = read(`backend/${project}/gradle.lockfile`).split(/\r?\n/).filter(line => line && !line.startsWith('#') && !line.startsWith('empty='));
      assert(lines.length > 0, `${project}: project-local dependency lock is empty`);
      for (const line of lines) {
        const match = /^([^:]+):([^:]+):([^=]+)=([^=]+)$/.exec(line);
        assert(match && !/[+\[\](),\s]|latest|SNAPSHOT/i.test(match[3]), `${project}: changing or malformed locked dependency`);
      }
      assert(!/changing\s*(?:=\s*true|\(\s*true\s*\))|isChanging\s*=\s*true/.test(build), `${project}: changing dependencies are forbidden`);
    }
    const artifacts = checkArtifacts(read('backend/gradle/verification-metadata.xml'));
    for (const file of ['backend/gradle.properties', 'gradle.properties']) {
      if (fs.existsSync(path.join(root, file))) assert(!/org\.gradle\.dependency\.verification\s*=\s*(off|lenient)/.test(read(file)), 'strict Gradle verification cannot be disabled');
    }
    console.log(`dependency contract: npm ownership, both Gradle locks, ${artifacts} SHA256 artifacts passed`);
  }
} catch (error) {
  console.error(`dependency contract: ${error.message}`);
  process.exitCode = 1;
}
NODE
