import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

const source = readFileSync(new URL('./secret-findings-gate.mjs', import.meta.url), 'utf8');
const contract = readFileSync(new URL('./secret-findings-test.mjs', import.meta.url), 'utf8');
// A nested Node test runner must start a new harness, not inherit its parent's
// child-v8 protocol (which can otherwise exit zero without running any tests).
const childEnvironment = { ...process.env };
delete childEnvironment.NODE_TEST_CONTEXT;
const mutants = [
  ['verified allowed', 'result && result.Verified === false', 'result'],
  ['all unknowns allowed', "detail === '' || (historical[index].allowDnsResolutionError === true && dnsResolutionUnknown)", 'true'],
  ['DNS exception granted to every detector', 'historical[index].allowDnsResolutionError === true', 'true'],
  ['DNS error category ignored', '/\\blookup\\b/.test(detail) && /:\\s*no such host$/.test(detail)', 'true'],
  ['raw fingerprint ignored', ' && item.rawSHA256 === rawSHA256', ''],
  ['commit ignored', 'item.commit === git?.commit && ', ''],
  ['path ignored', 'item.path === git?.file && ', ''],
  ['line ignored', 'item.line === git?.line && ', ''],
  ['detector ignored', 'item.detector === result.DetectorName &&', 'true &&'],
  ['duplicate occurrence accepted', 'requireCondition(++counts[index] === 1);', 'counts[index]++;'],
  ['missing occurrence accepted', 'requireCondition(counts[index] === (commits.has(historical[index].commit) ? 1 : 0));', 'requireCondition(true);'],
  ['scanner exit ignored', 'requireCondition(code === (findings === 0 ? 0 : 183));', 'requireCondition(true);'],
  ['download failure ignored', 'requireCondition(pull.code === 0);', 'requireCondition(true);'],
  ['artifact digest ignored', "requireCondition(Array.isArray(digests) && digests.includes(SCANNER_IMAGE.replace(':3.97.4@', '@')));", 'requireCondition(true);'],
  ['different pinned artifact', 'd366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25', '0'.repeat(64)],
  ['all-status scan narrowed', '--results=verified,unverified,unknown', '--results=verified'],
  ['equal range accepted', ' && base !== head', ''],
  ['range resolution errors ignored', 'requireCondition(result.code === 0);', 'requireCondition(true);'],
  ['empty effective commit set accepted', 'commits.size > 0 && ', ''],
  ['raw stdout forwarded', "child.stdout.setEncoding('utf8');", "child.stdout.setEncoding('utf8'); child.stdout.pipe(process.stdout);"],
  ['raw stderr forwarded', 'child.stderr.resume();', 'child.stderr.pipe(process.stderr);'],
];

test('the pristine secret contract is green before mutation rejection is counted', () => {
  const result = spawnSync(process.execPath, ['--test', '--experimental-test-isolation=none', new URL('./secret-findings-test.mjs', import.meta.url).pathname],
    { encoding: 'utf8', env: childEnvironment });
  assert.equal(result.status, 0);
  assert(result.stdout.includes('# tests 58') && result.stdout.includes('# fail 0'), 'pristine contract must actually execute');
});

for (const [name, before, after] of mutants) {
  test(`the secret contract rejects production mutation: ${name}`, () => {
    assert(source.includes(before), 'mutation must match the production implementation');
    const fixture = mkdtempSync(path.join(tmpdir(), 'task8-secret-mutation-'));
    try {
      writeFileSync(path.join(fixture, 'secret-findings-gate.mjs'), source.replace(before, after));
      writeFileSync(path.join(fixture, 'secret-findings-test.mjs'), contract);
      // Run the fixture directly: a deliberate stdout-leak mutation must reach
      // the output assertion, not corrupt Node's child-v8 serialization pipe.
      const result = spawnSync(process.execPath, ['--test', '--experimental-test-isolation=none', path.join(fixture, 'secret-findings-test.mjs')],
        { encoding: 'utf8', env: childEnvironment, timeout: 20_000, maxBuffer: 4_194_304 });
      // Diagnostics can contain synthetic payloads: assert summaries, never echo them.
      assert.equal(result.status, 1, 'production weakening must be caught by the contract');
      assert(result.stdout.includes('# fail '), 'the contract must execute, not fail to load');
      assert(!/SyntaxError|ERR_MODULE_NOT_FOUND/.test(result.stdout + result.stderr), 'mutation must remain executable');
    } finally {
      rmSync(fixture, { recursive: true, force: true });
    }
  });
}
