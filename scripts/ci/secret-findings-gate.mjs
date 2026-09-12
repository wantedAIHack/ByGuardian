import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

export const SCANNER_IMAGE = 'ghcr.io/trufflesecurity/trufflehog:3.97.4@sha256:d366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25';
const FAILURE = 'Secret scan failed closed; inspect the security contract without printing scanner output.';
const fail = () => { throw new Error(FAILURE); };
const requireCondition = condition => { if (!condition) fail(); };
const isCommit = value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value) && !/^0{40}$/.test(value);

// These are individual historical false positives, not path/detector exclusions.
// The first is a userinfo-rejection test using the RFC 2606/IANA-reserved .test
// TLD, not an authentication configuration; the second is a public metric name.
// Only that exact URI may also have an NXDOMAIN DNS-resolution verification
// error. Other unknowns, every verified result, and changed tuples/counts fail.
// No raw values, hosts, or original verification errors are stored or printed.
export const HISTORICAL_FINDINGS = Object.freeze([
  Object.freeze({ detector: 'URI', commit: '056ba9996d401705bc0bb071369be0298391cedb',
    path: 'frontend/scripts/cloudflare-assets.test.mjs', line: 51,
    rawSHA256: '79cbd27eafd5315a35b10a27b35f58a2327f07ec23713a77212fc268c14a731f', allowDnsResolutionError: true }),
  Object.freeze({ detector: 'Hiveage', commit: '953280b46b35f78a177a04103f2261cf7b2b346f',
    path: 'docs/superpowers/plans/2026-09-10-aws-backend-production.md', line: 608,
    rawSHA256: '7499f5a277f64a6974bf8084d0d4369fb5bdaa1164db1c06c74d1119b7ce4931' }),
]);

export function createFindingGate(commits, historical = HISTORICAL_FINDINGS) {
  const counts = historical.map(() => 0);
  let findings = 0;
  return {
    acceptLine(line) {
      try {
        const result = JSON.parse(line);
        requireCondition(result && result.Verified === false);
        requireCondition(result.VerificationError === undefined || typeof result.VerificationError === 'string');
        requireCondition(typeof result.Raw === 'string' && result.Raw.length > 0);
        const rawSHA256 = createHash('sha256').update(result.Raw).digest('hex');
        const git = result.SourceMetadata?.Data?.Git;
        const index = historical.findIndex(item => item.detector === result.DetectorName &&
          item.commit === git?.commit && item.path === git?.file && item.line === git?.line && item.rawSHA256 === rawSHA256);
        requireCondition(index >= 0 && commits.has(historical[index].commit));
        const detail = result.VerificationError || '';
        const dnsResolutionUnknown = /\blookup\b/.test(detail) && /:\s*no such host$/.test(detail);
        requireCondition(detail === '' || (historical[index].allowDnsResolutionError === true && dnsResolutionUnknown));
        requireCondition(++counts[index] === 1);
        findings++;
      } catch { fail(); }
    },
    finish(code) {
      requireCondition(code === (findings === 0 ? 0 : 183));
      for (let index = 0; index < historical.length; index++) {
        requireCondition(counts[index] === (commits.has(historical[index].commit) ? 1 : 0));
      }
      return { findings, historicalFindings: counts.reduce((sum, count) => sum + count, 0) };
    },
  };
}

// Never inherit child output: JSON/diagnostics can contain raw credentials.
// Parse one bounded line at a time in memory; discard stderr and generalize errors.
export function runQuiet(command, args, { onLine, collect = false, timeout = 300_000 } = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] });
    let pending = '';
    let output = '';
    let invalid = false;
    const timer = setTimeout(() => { invalid = true; child.kill('SIGTERM'); }, timeout);
    const consume = line => { if (line && onLine) onLine(line); };
    child.stdout.setEncoding('utf8');
    child.stderr.resume();
    child.stdout.on('data', chunk => {
      if (invalid) return;
      try {
        pending += chunk;
        requireCondition(Buffer.byteLength(pending) <= 1_048_576);
        if (collect) {
          output += pending;
          pending = '';
          requireCondition(Buffer.byteLength(output) <= 1_048_576);
        } else {
          let newline;
          while ((newline = pending.indexOf('\n')) !== -1) {
            consume(pending.slice(0, newline));
            pending = pending.slice(newline + 1);
          }
        }
      } catch { invalid = true; pending = ''; output = ''; }
    });
    child.on('error', () => { invalid = true; });
    child.on('close', code => {
      clearTimeout(timer);
      try {
        requireCondition(!invalid);
        consume(pending);
        resolve({ code, output });
      } catch { reject(new Error(FAILURE)); }
    });
  });
}

export async function scanGate({ repository, base, head, execute = runQuiet }) {
  try {
    requireCondition(isCommit(head) && (base === '' || isCommit(base)) && base !== head);
    requireCondition(typeof repository === 'string' && path.isAbsolute(repository));
    const git = async args => {
      const result = await execute('git', ['-C', repository, ...args], { collect: true });
      requireCondition(result.code === 0);
      return result.output.trim();
    };
    await git(['cat-file', '-e', `${head}^{commit}`]);
    let mergeBase = '';
    if (base !== '') {
      await git(['cat-file', '-e', `${base}^{commit}`]);
      mergeBase = await git(['merge-base', base, head]);
      requireCondition(isCommit(mergeBase));
    }
    const commitOutput = await git(['rev-list', head, ...(mergeBase ? [`^${mergeBase}`] : []), '--']);
    const commits = new Set(commitOutput ? commitOutput.split('\n') : []);
    requireCondition([...commits].every(isCommit));
    const gate = createFindingGate(commits);

    // Docker verifies the manifest/layer hashes while pulling this immutable digest.
    // Check RepoDigests as well; never fall back to the tag or another artifact.
    const pull = await execute('docker', ['pull', '--platform', 'linux/amd64', SCANNER_IMAGE]);
    requireCondition(pull.code === 0);
    const inspect = await execute('docker', ['image', 'inspect', SCANNER_IMAGE, '--format', '{{json .RepoDigests}}'], { collect: true });
    requireCondition(inspect.code === 0);
    const digests = JSON.parse(inspect.output);
    requireCondition(Array.isArray(digests) && digests.includes(SCANNER_IMAGE.replace(':3.97.4@', '@')));
    const scan = await execute('docker', ['run', '--rm', '--platform', 'linux/amd64', '--cap-drop', 'ALL',
      '--security-opt', 'no-new-privileges', '--read-only', '--tmpfs', '/tmp:rw,noexec,nosuid,size=512m',
      '--env', 'HOME=/tmp', '--volume', `${repository}:/src:ro`, SCANNER_IMAGE, 'git', 'file:///src',
      '--since-commit', base, '--branch', head, '--results=verified,unverified,unknown',
      '--json', '--fail', '--no-update', '--fail-on-scan-errors', '--log-level=-1'], { onLine: gate.acceptLine });
    return gate.finish(scan.code);
  } catch { fail(); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    requireCondition(process.argv.length === 2);
    const result = await scanGate({ repository: process.cwd(), base: process.env.BASE_SHA, head: process.env.HEAD_SHA });
    process.stdout.write(`Secret scan passed; ${result.historicalFindings} exact historical false-positive occurrences.\n`);
  } catch {
    process.stderr.write(`${FAILURE}\n`);
    process.exitCode = 1;
  }
}
