import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { test } from 'node:test';
import { createFindingGate, HISTORICAL_FINDINGS, runQuiet, scanGate, SCANNER_IMAGE } from './secret-findings-gate.mjs';

const raw = 'disposable synthetic fixture, not a credential';
const commit = 'a'.repeat(40);
const tuple = { detector: 'Fixture', commit, path: 'fixture.txt', line: 3,
  rawSHA256: createHash('sha256').update(raw).digest('hex') };
const record = () => ({ DetectorName: tuple.detector, Verified: false, Raw: raw,
  SourceMetadata: { Data: { Git: { commit, file: tuple.path, line: tuple.line } } } });
const gate = () => createFindingGate(new Set([commit]), [tuple]);
const generalFailure = /^(?:Error: )?Secret scan failed closed; inspect the security contract without printing scanner output\.$/;

test('only the two reviewed historical occurrences are configured', () => {
  assert.equal(SCANNER_IMAGE, 'ghcr.io/trufflesecurity/trufflehog:3.97.4@sha256:d366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25');
  assert.equal(HISTORICAL_FINDINGS.length, 2);
  assert.deepEqual(HISTORICAL_FINDINGS.map(x => Boolean(x.allowDnsResolutionError)), [true, false]);
  assert.deepEqual(HISTORICAL_FINDINGS.map(x => [x.detector, x.commit, x.path, x.line, x.rawSHA256]), [
    ['URI', '056ba9996d401705bc0bb071369be0298391cedb', 'frontend/scripts/cloudflare-assets.test.mjs', 51,
      '79cbd27eafd5315a35b10a27b35f58a2327f07ec23713a77212fc268c14a731f'],
    ['Hiveage', '953280b46b35f78a177a04103f2261cf7b2b346f', 'docs/superpowers/plans/2026-09-10-aws-backend-production.md', 608,
      '7499f5a277f64a6974bf8084d0d4369fb5bdaa1164db1c06c74d1119b7ce4931'],
  ]);
});

test('one exact unverified historical occurrence succeeds', () => {
  const current = gate();
  current.acceptLine(JSON.stringify(record()));
  assert.deepEqual(current.finish(183), { findings: 1, historicalFindings: 1 });
});
test('a range with no historical occurrences and no findings succeeds', () => {
  assert.deepEqual(createFindingGate(new Set(), [tuple]).finish(0), { findings: 0, historicalFindings: 0 });
});

for (const [name, mutate] of [
  ['new raw value', r => { r.Raw += ' changed'; }],
  ['new commit in the same path', r => { r.SourceMetadata.Data.Git.commit = 'b'.repeat(40); }],
  ['another detector', r => { r.DetectorName = 'Other'; }],
  ['another path', r => { r.SourceMetadata.Data.Git.file = 'other.txt'; }],
  ['another line in the same path', r => { r.SourceMetadata.Data.Git.line++; }],
  ['verified historical finding', r => { r.Verified = true; }],
  ['unknown historical finding', r => { r.VerificationError = raw; }],
  ['missing verification status', r => { delete r.Verified; }],
  ['malformed verification status', r => { r.Verified = 'false'; }],
  ['malformed verification error', r => { r.VerificationError = null; }],
  ['missing raw value', r => { delete r.Raw; }],
  ['malformed location', r => { r.SourceMetadata = null; }],
]) {
  test(`rejects ${name} without leaking raw data`, () => {
    const current = gate();
    const candidate = record();
    mutate(candidate);
    assert.throws(() => current.acceptLine(JSON.stringify(candidate)), error => {
      assert.match(error.message, generalFailure);
      assert.equal(error.stack.includes(raw), false);
      return true;
    });
  });
}
test('rejects duplicate occurrences', () => {
  const current = gate();
  current.acceptLine(JSON.stringify(record()));
  assert.throws(() => current.acceptLine(JSON.stringify(record())), generalFailure);
});
test('rejects a missing expected historical occurrence', () => assert.throws(() => gate().finish(0), generalFailure));
test('rejects an out-of-range historical occurrence', () => {
  assert.throws(() => createFindingGate(new Set(), [tuple]).acceptLine(JSON.stringify(record())), generalFailure);
});
test('rejects a changed approved fingerprint', () => {
  assert.throws(() => createFindingGate(new Set([commit]), [{ ...tuple, rawSHA256: '0'.repeat(64) }])
    .acceptLine(JSON.stringify(record())), generalFailure);
});
const dnsError = 'Get [redacted URL]: dial tcp: lookup [reserved test domain]: no such host';
test('an explicitly reviewed DNS-resolution unknown occurrence succeeds', () => {
  const current = createFindingGate(new Set([commit]), [{ ...tuple, allowDnsResolutionError: true }]);
  current.acceptLine(JSON.stringify({ ...record(), VerificationError: dnsError }));
  assert.deepEqual(current.finish(183), { findings: 1, historicalFindings: 1 });
});
for (const detail of ['request timeout', 'x509: certificate error', 'connection refused',
  'lookup [test domain]: server misbehaving', 'lookup [test domain]: i/o timeout',
  'no such host', 'lookup [test domain]: no such host; another failure']) {
  test('the DNS exception rejects other verification error categories', () => {
    const current = createFindingGate(new Set([commit]), [{ ...tuple, allowDnsResolutionError: true }]);
    assert.throws(() => current.acceptLine(JSON.stringify({ ...record(), VerificationError: detail })), generalFailure);
  });
}
test('a reviewed DNS error never allows a verified occurrence', () => {
  const current = createFindingGate(new Set([commit]), [{ ...tuple, allowDnsResolutionError: true }]);
  assert.throws(() => current.acceptLine(JSON.stringify({ ...record(), Verified: true, VerificationError: dnsError })), generalFailure);
});
test('an unverified-only historical occurrence cannot use the DNS exception', () => {
  assert.throws(() => gate().acceptLine(JSON.stringify({ ...record(), VerificationError: dnsError })), generalFailure);
});
test('a new unknown candidate fails even if its error category is DNS resolution', () => {
  const current = createFindingGate(new Set([commit]), [{ ...tuple, allowDnsResolutionError: true }]);
  assert.throws(() => current.acceptLine(JSON.stringify({ ...record(), Raw: raw + ' changed', VerificationError: dnsError })), generalFailure);
});
for (const invalid of ['not JSON', '{"Raw":"' + raw, 'null', '[]']) {
  test('malformed JSON fails with no payload in its diagnostic', () => {
    assert.throws(() => gate().acceptLine(invalid), generalFailure);
  });
}
for (const code of [1, 2, null, 183]) {
  test(`scanner exit ${code} without findings fails closed`, () => {
    assert.throws(() => createFindingGate(new Set()).finish(code), generalFailure);
  });
}
test('scanner success with a finding is inconsistent and fails', () => {
  const current = gate(); current.acceptLine(JSON.stringify(record()));
  assert.throws(() => current.finish(0), generalFailure);
});
test('the process adapter consumes stdout in memory and never forwards stderr', async () => {
  const lines = [];
  const result = await runQuiet(process.execPath, ['-e', 'process.stdout.write("one\\ntwo\\n"); process.stderr.write("private diagnostic")'],
    { onLine: line => lines.push(line) });
  assert.deepEqual(lines, ['one', 'two']);
  assert.equal(result.output, '');
  assert.equal(result.code, 0);
});
test('child spawn errors have generic diagnostics', async () => {
  await assert.rejects(runQuiet('/missing-task8-fixture-executable', []), generalFailure);
});
test('parser errors do not reflect a child payload or stack', async () => {
  await assert.rejects(runQuiet(process.execPath, ['-e', `console.log(${JSON.stringify(raw)})`],
    { onLine: line => { throw new Error(line); } }), generalFailure);
});
test('overlarge child output fails closed', async () => {
  await assert.rejects(runQuiet(process.execPath, ['-e', 'process.stdout.write("x".repeat(1048577))']), generalFailure);
});
test('the full process boundary never prints raw stdout or stderr', () => {
  const moduleURL = new URL('./secret-findings-gate.mjs', import.meta.url).href;
  const program = `import {runQuiet} from ${JSON.stringify(moduleURL)};
    try { await runQuiet(process.execPath, ['-e', ${JSON.stringify('console.log(' + JSON.stringify(raw) + '); console.error(' + JSON.stringify(raw) + ')')}],
      {onLine: line => {throw new Error(line)}}); } catch (error) {console.log(error.message)}`;
  const result = spawnSync(process.execPath, ['--input-type=module', '-e', program], { encoding: 'utf8' });
  assert.equal(result.status, 0);
  assert.match(result.stdout.trim(), generalFailure);
  assert.equal(result.stderr, '');
  assert.equal((result.stdout + result.stderr).includes(raw), false);
});

function scannerFixture({ stepFailure, digest = SCANNER_IMAGE.replace(':3.97.4@', '@'), lines = [], scannerCode = 0 } = {}) {
  const calls = [];
  const execute = async (command, args, options = {}) => {
    calls.push([command, args]);
    if (command === 'git') {
      if (stepFailure === 'range') return { code: 1, output: '' };
      return { code: 0, output: args.includes('merge-base') ? 'c'.repeat(40) + '\n' : '' };
    }
    if (args[0] === 'pull') return { code: stepFailure === 'download' ? 1 : 0, output: '' };
    if (args[0] === 'image') return { code: 0, output: JSON.stringify([digest]) };
    if (stepFailure === 'scanner') throw new Error(raw);
    for (const line of lines) options.onLine(line);
    return { code: scannerCode, output: '' };
  };
  return { execute, calls };
}
test('the combined scan pins and verifies its artifact before scanning every status over the same range', async () => {
  const fixture = scannerFixture();
  await scanGate({ repository: process.cwd(), base: 'b'.repeat(40), head: commit, execute: fixture.execute });
  const run = fixture.calls.find(([command, args]) => command === 'docker' && args[0] === 'run')[1];
  assert(run.includes(SCANNER_IMAGE));
  assert(run.includes('--results=verified,unverified,unknown'));
  assert(run.includes('--json') && run.includes('--fail') && run.includes('--no-update') && run.includes('--fail-on-scan-errors'));
  assert.equal(run[run.indexOf('--since-commit') + 1], 'b'.repeat(40));
  assert.equal(run[run.indexOf('--branch') + 1], commit);
  assert.equal(run.includes('--no-verification'), false);
  const dockerSteps = fixture.calls.filter(([command]) => command === 'docker').map(([, args]) => args[0]);
  assert.deepEqual(dockerSteps, ['pull', 'image', 'run']);
});
test('empty validated base scans the complete reachable head', async () => {
  const fixture = scannerFixture();
  await scanGate({ repository: process.cwd(), base: '', head: commit, execute: fixture.execute });
  const args = fixture.calls.find(([command, args]) => command === 'docker' && args[0] === 'run')[1];
  assert.equal(args[args.indexOf('--since-commit') + 1], '');
});
for (const stepFailure of ['range', 'download', 'scanner']) {
  test(`${stepFailure} errors fail closed without raw diagnostics`, async () => {
    await assert.rejects(scanGate({ repository: process.cwd(), base: '', head: commit,
      execute: scannerFixture({ stepFailure }).execute }), generalFailure);
  });
}
test('artifact digest mismatch fails before scan', async () => {
  const fixture = scannerFixture({ digest: SCANNER_IMAGE.replace('d366', '0000') });
  await assert.rejects(scanGate({ repository: process.cwd(), base: '', head: commit, execute: fixture.execute }), generalFailure);
  assert.equal(fixture.calls.some(([command, args]) => command === 'docker' && args[0] === 'run'), false);
});
for (const [base, head] of [['', ''], ['', '0'.repeat(40)], ['0'.repeat(40), commit], [commit, commit], ['', 'HEAD']]) {
  test('invalid or empty range is rejected before scanner execution', async () => {
    const fixture = scannerFixture();
    await assert.rejects(scanGate({ repository: process.cwd(), base, head, execute: fixture.execute }), generalFailure);
    assert.equal(fixture.calls.length, 0);
  });
}
