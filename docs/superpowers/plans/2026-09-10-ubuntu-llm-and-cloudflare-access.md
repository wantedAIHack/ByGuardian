# Ubuntu LLM and Cloudflare Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the currently powered-off Ubuntu gaming laptop into a reboot-safe, GPU-backed Ollama origin reachable only by the EC2 backend through Cloudflare Access.

**Architecture:** Offline-first repository work replaces the legacy Tunnel environment variable with a raw file secret, adds an idempotent host bootstrap and stable release directory, and hardens the existing self-hosted deployment workflow. Live work is a later, explicit gate: Access policy first, Tunnel route second, user-entered secrets, GPU/reboot tests, and only then restricted runner registration.

**Tech Stack:** Ubuntu Server 24.04 x86_64, NVIDIA GTX 1060 6 GiB/driver 580.173.02, Docker Engine and Compose v2.24.4+, NVIDIA Container Toolkit, Ollama 0.33.3, qwen3:4b-q8_0, cloudflared, systemd, POSIX shell/ShellCheck, Cloudflare Zero Trust Access/Tunnel, GitHub Actions self-hosted runner.

**Spec:** `docs/superpowers/specs/2026-09-10-full-service-deployment-design.md`

## Global Constraints

- The laptop is **OFFLINE as of 2026-09-10 because the owner left for work**. Do not run `ssh llm`, wake-on-LAN, router changes, or any reachability probe until the owner explicitly says it is powered on and available.
- Offline source/tests may proceed. Every live task below begins with a separate availability/approval gate and stops cleanly if the laptop is unreachable.
- Never repeat, store, transmit, or use the password exposed in chat. The owner changes it directly before any package install, public route, or runner registration.
- Keep the installed NVIDIA driver unless a post-bootstrap GPU container test proves it incompatible. Do not pre-emptively reinstall it.
- Pin Ollama to `ollama/ollama:0.33.3@sha256:32931b46719f673c05fdbaa81ccb26da18ea4a1c57590a754874ab28ba269eb2`, model `qwen3:4b-q8_0`, context 2048, and the existing pinned cloudflared digest until an explicit reviewed upgrade.
- Final Compose uses exactly `compose.yml + compose.gpu.yml + compose.tunnel.yml`, project `nextvisit-llm`, and publishes no Ollama host port.
- Tunnel token exists only as raw one-line `/etc/nextvisit/llm.token`, host `root:65532`, mode `0440`, mounted at `/run/secrets/tunnel_token`; it never appears in Env/Cmd/Labels.
- Access Client ID/Secret exist only in AWS Parameter Store and EC2 file secrets. They never exist on the laptop, GitHub, FE, or local planning worktree.
- Create and verify the Access application and exact Service Auth policy before adding the Tunnel public hostname.
- No PR, fork, or unreviewed workflow may run on the laptop. Register a runner only after the Organization group proves selected repository plus selected workflow restriction.
- If that restriction is unavailable, do not register the runner; keep audited manual deployment.
- `LLM_DEPLOY_ENABLED=false` and backend `NEXTVISIT_LLM_ENABLED=false` remain until their explicit late gates.
- Do not use `docker compose down --volumes`; model data must survive deploy, stop, reboot, and rollback.
- All smoke scripts suppress provider bodies, prompts, sentences, and credentials on stdout/stderr.
- Live test data is synthetic. Do not send actual protector notes or user records during infrastructure acceptance.
- Run RED/GREEN/mutation tests and `git diff --check` before every commit.

---

## File and Interface Map

| Area | File | Responsibility |
| --- | --- | --- |
| Tunnel secret | `infra/llm/compose.tunnel.yml`, `infra/llm/scripts/verify-tunnel-token-file.sh` | File-only token mount and host metadata validation |
| Bootstrap | `infra/llm/scripts/bootstrap-ubuntu-host.sh` | Idempotent official Docker/Toolkit/account/power setup |
| Stable releases | `infra/llm/scripts/stage-runtime.sh` | Copy one reviewed SHA to `/opt/nextvisit/llm/releases` and atomically switch current |
| Reboot recovery | `infra/llm/systemd/nextvisit-llm.service` | Start exact current three-file stack without deleting volume |
| Access smoke | `infra/llm/scripts/smoke-access.sh` | Fixed-code unauth/auth boundary check with file credentials |
| Local inference | `infra/llm/scripts/smoke-openai.sh` | Existing no-body OpenAI-compatible model check |
| Deployment | `.github/workflows/llm-deploy.yml` | Main-only restricted runner staging/deploy after admission |
| Hosted checks | `.github/workflows/ci.yml` | Offline shell/fake/render/mutation verification only |
| Policy tests | `infra/llm/tests/*.sh` | Reject env tokens, workflow trust expansion, unsafe host/release units |
| Owner docs | `infra/llm/README.md`, `infra/llm/OWNER_CHECKLIST.md`, `docs/operations/full-service-runbook.md` | Exact account, host, rotation, recovery, and deferred-live steps |

### Task 1: Replace the legacy Tunnel environment with a file-only secret

**Files:**
- Modify: `infra/llm/compose.tunnel.yml`
- Create: `infra/llm/scripts/verify-tunnel-token-file.sh`
- Create: `infra/llm/tests/tunnel-token-file-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/llm-deploy.yml`
- Modify: `infra/llm/tests/workflows-test.sh`
- Modify: `infra/llm/tests/workflows-runner-group-test.sh`
- Modify: `docs/superpowers/specs/2026-09-07-llm-server-design.md`
- Modify: `infra/llm/README.md`
- Modify: `infra/llm/OWNER_CHECKLIST.md`

**Interfaces:**
- Consumes: host path from `NEXTVISIT_LLM_TOKEN_FILE`, defaulting only in operator commands to `/etc/nextvisit/llm.token`.
- Produces: Compose secret `tunnel_token` at `/run/secrets/tunnel_token` and cloudflared command `tunnel --no-autoupdate run --token-file /run/secrets/tunnel_token`.

- [ ] **Step 1: Write failing structure and fake-file tests**

For any supplied absolute fixture path, require parent `root:65532 0750`, no symlink in either path component, one regular non-symlink file, exactly one non-empty LF-terminated line, no CR/NUL, size at most 4096 bytes, numeric owner/group `0:65532`, and mode `440`. Production workflow/systemd policy additionally requires the supplied path to equal `/etc/nextvisit/llm.token` and no legacy `/etc/nextvisit/llm.env`. Require final Compose to contain no `env_file`, `TUNNEL_TOKEN`, token literal, or host Ollama port.

- [ ] **Step 2: Run RED against the current env-file design**

```bash
sh infra/llm/tests/tunnel-token-file-test.sh
sh infra/llm/tests/workflows-test.sh
```

Expected: failure because `compose.tunnel.yml` still consumes `NEXTVISIT_LLM_ENV_FILE` and `TUNNEL_TOKEN`.

- [ ] **Step 3: Implement file verification without reading content into output**

`verify-tunnel-token-file.sh` accepts one path, resolves parent and file with `lstat`, checks metadata/line count/size, and prints only `tunnel token file verified`. It must never use `set -x`, echo the path's content, or include the token in a diagnostic.

Define Compose equivalent to:

```yaml
services:
  ollama:
    ports: !reset []
  cloudflared:
    command: ["tunnel", "--no-autoupdate", "run", "--token-file", "/run/secrets/tunnel_token"]
    secrets:
      - source: tunnel_token
        target: tunnel_token
    depends_on:
      ollama:
        condition: service_healthy
secrets:
  tunnel_token:
    file: "${NEXTVISIT_LLM_TOKEN_FILE:?Set NEXTVISIT_LLM_TOKEN_FILE to the protected raw token file}"
```

Retain the existing pinned cloudflared image and restart policy.

- [ ] **Step 4: Update every owner and workflow contract in the same change**

Remove `NEXTVISIT_LLM_ENV_FILE`, `.env` token examples, and runner secret forwarding. Add policy assertions that any `env_file`, `TUNNEL_TOKEN`, `--token` without `-file`, or GitHub `secrets.` reference in LLM deploy is rejected.

- [ ] **Step 5: Render and inspect with a synthetic raw token**

Create a mode-0440 root:65532 fixture only where the local OS permits ownership; otherwise use the fake metadata test and render with a mode-0600 temporary file. Render all three Compose files, assert `.services.ollama.ports == null`, and inspect the JSON structurally without printing it. Confirm the sentinel is absent from Env/Cmd/Labels fields.

- [ ] **Step 6: Mutation-test and commit**

Temporarily restore `TUNNEL_TOKEN` as environment, prove the test fails, restore, rerun all shell/workflow tests, then commit:

```bash
git add infra/llm/compose.tunnel.yml infra/llm/scripts/verify-tunnel-token-file.sh infra/llm/tests/tunnel-token-file-test.sh .github/workflows/ci.yml .github/workflows/llm-deploy.yml infra/llm/tests/workflows-test.sh infra/llm/tests/workflows-runner-group-test.sh docs/superpowers/specs/2026-09-07-llm-server-design.md infra/llm/README.md infra/llm/OWNER_CHECKLIST.md
git commit -m "fix: Tunnel token을 파일 secret으로 격리"
```

### Task 2: Add an idempotent Ubuntu host bootstrap

**Files:**
- Create: `infra/llm/scripts/bootstrap-ubuntu-host.sh`
- Create: `infra/llm/tests/bootstrap-ubuntu-host-test.sh`
- Modify: `infra/llm/scripts/verify-host.sh`
- Modify: `infra/llm/tests/verify-host-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `infra/llm/README.md`
- Modify: `infra/llm/OWNER_CHECKLIST.md`

**Interfaces:**
- Consumes: `bootstrap-ubuntu-host.sh prepare` followed by `bootstrap-ubuntu-host.sh apply APPROVED_LOCK_SHA256`, both as owner-controlled root on Ubuntu 24.04 x86_64 with a working existing `nvidia-smi`.
- Produces: root-owned non-secret `/etc/nextvisit/ubuntu-packages.lock`, exact Docker/Compose/NVIDIA Toolkit runtime, locked `nextvisit-runner`, protected directories, and persistent server-style power settings.

- [ ] **Step 1: Build a fake-root command harness first**

The test injects fake `apt-cache`, `apt-get`, `install`, `gpg`, `curl`, `systemctl`, `useradd`, `usermod`, `nvidia-ctk`, `nvidia-smi`, `dpkg`, `uname`, and file roots. It proves `prepare` changes only the exact signed repository/key files and writes a sorted version lock; `apply` rejects a missing/wrong lock SHA before package mutation; then it runs the approved apply twice and asserts no duplicate repositories/users/drop-ins, no driver package command, no secret/password argument, and byte-identical second-run state.

- [ ] **Step 2: Run RED**

```bash
sh infra/llm/tests/bootstrap-ubuntu-host-test.sh
```

Expected: missing bootstrap.

- [ ] **Step 3: Implement fail-closed platform and repository setup**

Require effective UID 0, Ubuntu ID/version `ubuntu/24.04`, `x86_64`, successful existing `nvidia-smi`, AC power, and at least 20 GiB free before mutation. `prepare` installs no package: it may create secret-free `/etc/nextvisit` as `root:root 0755`, writes only the exact official signed Docker/NVIDIA repository keys and lists, resolves Docker Engine/CLI/containerd/Compose and NVIDIA Container Toolkit/libnvidia-container candidate versions, and atomically records the sorted values plus source fingerprints in `/etc/nextvisit/ubuntu-packages.lock` mode 0444. It prints those non-secret values and the file SHA-256. `apply` first matches its `APPROVED_LOCK_SHA256` argument, reruns candidate resolution to reject drift, then invokes apt with explicit `package=version` arguments; it never runs a general distribution upgrade. It creates GID 65532 and tightens `/etc/nextvisit` to `root:65532 0750` before any token can exist. Run `nvidia-ctk runtime configure --runtime=docker`, restart Docker, require the `nvidia` runtime, and record installed versions in the owner checklist.

- [ ] **Step 4: Implement account/directory/power idempotency**

Create system group `nextvisit-cloudflared` with numeric GID 65532 after first proving the GID is unused or already belongs to that exact group. Create `nextvisit-runner` locked with no usable password, home `/home/nextvisit-runner`, shell `/bin/bash`, and groups `docker,nextvisit-cloudflared`. Create `/opt/nextvisit/llm/releases` owned only by that account. Create `/etc/nextvisit` as `root:65532` mode `0750` and refuse to overwrite an existing token. This lets only root, cloudflared's numeric group, and the dedicated root-equivalent deployment account traverse/read the file; ordinary host users cannot.

Back up any changed logind files once, then write a dedicated drop-in with `HandleLidSwitch=ignore`, `HandleLidSwitchExternalPower=ignore`, and `IdleAction=ignore`; mask sleep/suspend/hibernate/hybrid-sleep targets for this dedicated server host. Do not reboot inside the script.

- [ ] **Step 5: Extend host verification**

GPU mode must verify Docker/Compose 2.24.4+, disk, `nvidia-smi`, `nvidia-ctk`, Docker nvidia runtime, service account lock state, stable release directories, power targets, and absence of public port 11434. It must not require a token before the token-install phase.

- [ ] **Step 6: Run GREEN twice and commit**

```bash
sh infra/llm/tests/bootstrap-ubuntu-host-test.sh
sh infra/llm/tests/bootstrap-ubuntu-host-test.sh
sh infra/llm/tests/verify-host-test.sh
shellcheck infra/llm/scripts/bootstrap-ubuntu-host.sh infra/llm/scripts/verify-host.sh infra/llm/tests/bootstrap-ubuntu-host-test.sh infra/llm/tests/verify-host-test.sh
git add infra/llm/scripts/bootstrap-ubuntu-host.sh infra/llm/tests/bootstrap-ubuntu-host-test.sh infra/llm/scripts/verify-host.sh infra/llm/tests/verify-host-test.sh .github/workflows/ci.yml infra/llm/README.md infra/llm/OWNER_CHECKLIST.md
git commit -m "feat: Ubuntu LLM 호스트 bootstrap 추가"
```

### Task 3: Stage immutable laptop releases and recover them with systemd

**Files:**
- Create: `infra/llm/scripts/stage-runtime.sh`
- Create: `infra/llm/tests/stage-runtime-test.sh`
- Create: `infra/llm/systemd/nextvisit-llm.service`
- Create: `infra/llm/tests/systemd-unit-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `.github/workflows/llm-deploy.yml`
- Modify: `infra/llm/README.md`
- Modify: `infra/llm/OWNER_CHECKLIST.md`

**Interfaces:**
- Consumes: `stage-runtime.sh FULL_COMMIT_SHA CHECKOUT_ROOT` where checkout HEAD equals the first argument.
- Produces: `/opt/nextvisit/llm/releases/FULL_COMMIT_SHA`, atomic `/opt/nextvisit/llm/current`, and `nextvisit-llm.service` using only the current release under `nextvisit-runner`.

- [ ] **Step 1: Write release staging failure tests**

Reject non-40-hex/lowercase SHA, checkout mismatch, symlinks escaping checkout, missing required Compose/script files, world-writable source, pre-existing release with different bytes, and any copied `.git`, `.env`, token, key, or credential. Verify a failed stage leaves `current` unchanged.

- [ ] **Step 2: Run RED**

```bash
sh infra/llm/tests/stage-runtime-test.sh
sh infra/llm/tests/systemd-unit-test.sh
```

- [ ] **Step 3: Implement an allowlisted atomic release copy**

Copy only `infra/llm/Dockerfile`, the three Compose files, and the audited runtime scripts into a temporary sibling directory; checksum a manifest; fsync; atomically rename to the SHA; then atomically replace `current`. Existing byte-identical releases are idempotent. Keep the previous SHA directory for rollback.

- [ ] **Step 4: Define reboot recovery without secret exposure**

The unit starts after `docker.service` and `network-online.target`, requires `/opt/nextvisit/llm/current`, and sets `User=nextvisit-runner`, `SupplementaryGroups=docker nextvisit-cloudflared`, `WorkingDirectory=/opt/nextvisit/llm/current`, and the non-secret path `NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token`. Its token metadata preflight therefore runs with no root privilege and can inspect/read only through the dedicated group already granted to that Docker-root-equivalent account. It applies the exact three files/project, starts healthy Ollama, runs model-init idempotently over the Compose network, and then starts cloudflared. The final no-body inference proof is the later EC2 Access smoke because the final stack intentionally has no host Ollama port. `ExecStop` uses the exact three-file `down` command without `--volumes`; restart is `on-failure` with a bounded start timeout.

- [ ] **Step 5: Verify unit and rollback semantics**

Run fake staging twice, mutate one copied byte and prove rejection, run `systemd-analyze verify` where available, and assert no unit command contains token values/env files or volume deletion.

- [ ] **Step 6: Commit**

```bash
git add infra/llm/scripts/stage-runtime.sh infra/llm/tests/stage-runtime-test.sh infra/llm/systemd/nextvisit-llm.service infra/llm/tests/systemd-unit-test.sh .github/workflows/ci.yml .github/workflows/llm-deploy.yml infra/llm/README.md infra/llm/OWNER_CHECKLIST.md
git commit -m "feat: LLM immutable release와 재부팅 복구 추가"
```

### Task 4: Add response-body-silent Cloudflare Access smoke

**Files:**
- Create: `infra/llm/scripts/smoke-access.sh`
- Create: `infra/llm/tests/smoke-access-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `infra/llm/README.md`
- Modify: `infra/llm/OWNER_CHECKLIST.md`

**Interfaces:**
- Consumes: `smoke-access.sh BASE_URL CLIENT_ID_FILE CLIENT_SECRET_FILE`; arguments two and three are file paths, never values.
- Produces: zero only when unauthenticated is 302/401/403 and authenticated is 200 with an additive OpenAI envelope whose decoded assistant content has the exact one-question key set required by `smoke-openai.sh`.

- [ ] **Step 1: Write fake curl/jq tests with sentinels**

Cover unauthenticated 200, unexpected 404/429/5xx, auth non-200, malformed outer JSON, missing/wrong-typed required outer fields, unexpected/extra decoded question fields, timeout, one missing credential file, CR/LF credential injection, body sentinel, and credential sentinel. Additive outer OpenAI fields remain accepted. No sentinel may appear on stdout/stderr even when jq fails.

- [ ] **Step 2: Run RED**

```bash
sh infra/llm/tests/smoke-access-test.sh
```

- [ ] **Step 3: Implement file-path credentials and fixed diagnostics**

Require HTTPS, regular non-symlink mode-0440/0400/0600 credential files with bounded single-line header-safe ASCII values, create a mode-0600 curl config containing the exact `CF-Access-Client-Id` and `CF-Access-Client-Secret` headers, and pass only that config path in curl argv. Store bodies in private temporary files, run jq with both output streams suppressed, and clean all files in a trap. Set connect timeout 5 seconds and total timeout 45 seconds. The AWS release bundle allowlists this script so SSM can run it on EC2 beside the Access configtree files; it is not executed on the laptop for external authentication.

- [ ] **Step 4: Run GREEN and mutation test**

```bash
sh infra/llm/tests/smoke-access-test.sh
```

Temporarily accept every unauthenticated non-200 status; prove the 404 fixture fails the mutation assertion, restore, rerun.

- [ ] **Step 5: Commit**

```bash
git add infra/llm/scripts/smoke-access.sh infra/llm/tests/smoke-access-test.sh .github/workflows/ci.yml infra/llm/README.md infra/llm/OWNER_CHECKLIST.md
git commit -m "feat: Cloudflare Access 무본문 smoke 추가"
```

### Task 5: Harden the laptop deployment workflow around the stable runtime

**Files:**
- Modify: `.github/workflows/llm-deploy.yml`
- Modify: `infra/llm/tests/workflows-test.sh`
- Modify: `infra/llm/tests/workflows-runner-group-test.sh`
- Create: `infra/llm/tests/llm-deploy-mutation-test.sh`
- Modify: `.github/workflows/ci.yml`

**Interfaces:**
- Consumes: exact `github.sha`, group `llm-production`, labels `[self-hosted, linux, llm]`, and repository variable `LLM_DEPLOY_ENABLED`.
- Produces: one non-cancelling deployment that stages the checkout, applies current, proves no port/token leak, and never receives GitHub secrets.

- [ ] **Step 1: Add all trust-boundary mutations**

Reject PR/pull_request_target, missing or changed main/variable gate, label-only runner selection, group change, any CI self-hosted job, checkout other than `github.sha`, `persist-credentials: true`, `secrets.` context, token env/command, direct work from Actions `_work`, missing stable stage, host port, `down --volumes`, multiple deployment jobs, or broader permissions.

- [ ] **Step 2: Run mutations against the current workflow**

```bash
sh infra/llm/tests/workflows-test.sh
sh infra/llm/tests/workflows-runner-group-test.sh
sh infra/llm/tests/llm-deploy-mutation-test.sh
```

Expected: at least stable-release/token-file cases fail until the workflow changes.

- [ ] **Step 3: Implement the exact deploy job**

Keep job-level `environment: llm-production`, timeout, concurrency, group+labels, and contents read. Checkout `github.sha` with credentials false; run offline tests; call `stage-runtime.sh "$GITHUB_SHA" "$GITHUB_WORKSPACE"`; operate `/opt/nextvisit/llm/current`; run the existing loopback-only base+GPU smoke before applying the exact final three-file Compose; assert no Ollama port and no token in container Env/Cmd/Labels; and require the root-installed unit to remain enabled/active. The workflow does not call privileged `systemctl`, use any GitHub secret, or send a secret to a shell.

- [ ] **Step 4: Verify every mutation and commit**

```bash
sh infra/llm/tests/workflows-test.sh
sh infra/llm/tests/workflows-runner-group-test.sh
sh infra/llm/tests/llm-deploy-mutation-test.sh
actionlint -ignore 'label "llm" is unknown' .github/workflows/ci.yml .github/workflows/llm-deploy.yml
git add .github/workflows/llm-deploy.yml .github/workflows/ci.yml infra/llm/tests/workflows-test.sh infra/llm/tests/workflows-runner-group-test.sh infra/llm/tests/llm-deploy-mutation-test.sh
git commit -m "ci: 제한된 main LLM 배포 경계 강화"
```

### Task 6: Complete all offline verification while the laptop remains off

**Files:**
- Verify only; fix findings in focused commits.

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: reviewed host artifacts that make no claim about live Ubuntu/GPU/Tunnel success.

- [ ] **Step 1: Run every fast test twice**

```bash
find infra/llm -type f -name '*.sh' -exec shellcheck {} +
sh infra/llm/tests/ensure-model-test.sh
sh infra/llm/tests/verify-host-test.sh
sh infra/llm/tests/tunnel-token-file-test.sh
sh infra/llm/tests/bootstrap-ubuntu-host-test.sh
sh infra/llm/tests/stage-runtime-test.sh
sh infra/llm/tests/systemd-unit-test.sh
sh infra/llm/tests/smoke-access-test.sh
sh infra/llm/tests/workflows-test.sh
sh infra/llm/tests/workflows-runner-group-test.sh
sh infra/llm/tests/llm-deploy-mutation-test.sh
```

Run the same block a second time. Render CPU, GPU, and Tunnel Compose with a synthetic file path; require final Ollama ports null and exact cloudflared token-file command.

- [ ] **Step 2: Run the combined Java/FE/static gate**

Run the hosted-equivalent commands from the source integration plan. Do not build/pull the 4.4 GiB model merely for this offline milestone; the existing image/model pins are static-tested.

- [ ] **Step 3: Request security review**

Review token transport, numeric ownership, fake-root escape, symlinks, systemd privilege, Docker-root implications, workflow scope, response-body silence, and rollback. Fix all Critical/Important findings and repeat their mutations plus the full offline block.

- [ ] **Step 4: Record deferred status**

Update the checklist with exact source commit/test date and these unchecked gates: password rotation, bootstrap apply, reboot, GPU inference, Access policy, Tunnel, external smoke, runner restriction, automated deploy, backend enable. The laptop being off is not a failure.

### Task 7: Bootstrap the laptop only after the owner says it is online

**Files:**
- Live laptop state; append non-secret evidence to `infra/llm/OWNER_CHECKLIST.md`.

**Interfaces:**
- Consumes: explicit “laptop powered on and available” message, owner-controlled password rotation/sudo/reboot, reviewed source SHA.
- Produces: prepared Ubuntu host without Tunnel route or runner.

- [ ] **Step 1: Perform read-only identity preflight**

Only after the availability message, run one bounded `ssh llm` read-only probe for hostname, OS/arch, CPU/RAM/disk, `nvidia-smi`, current driver, Secure Boot, AC power, Docker/toolkit absence/presence, time sync, and public ports. Do not send a password or use sudo. Compare with the recorded GTX 1060/driver 580.173.02 baseline and stop on drift.

- [ ] **Step 2: Have the owner rotate the exposed password directly**

Do not request or observe the new value. Require the owner to confirm rotation completed before continuing.

- [ ] **Step 3: Prepare and approve the exact package lock before installation**

Show repository keys/lists, package families, account, directory, Docker group, power targets, systemd units, and that the driver is untouched. After approval, the owner runs only `prepare` and enters sudo locally/through their own terminal; the agent never supplies credentials. Show the resulting exact versions/fingerprints/SHA-256, obtain a second install/power-change approval, then have the owner run `apply` with that non-secret approved SHA.

- [ ] **Step 4: Reboot and wait for a new explicit availability confirmation**

After the owner-approved reboot, stop. Do not poll indefinitely. Resume only when the owner says `ssh llm` is available again.

- [ ] **Step 5: Verify host and GPU runtime without Tunnel**

Run `verify-host.sh gpu`, a pinned CUDA visibility container, and the base+GPU Compose. Run model-init once, then local loopback smoke three times; each must be under 45 seconds. Require `ollama ps` to report `100% GPU`, model volume to remain after a no-volume down/up, and port 11434 to remain loopback-only during this local phase.

### Task 8: Create Access before exposing the Tunnel hostname

**Files:**
- Cloudflare/AWS state; append only resource names and fixed outcomes to runbooks.

**Interfaces:**
- Consumes: owner-selected base domain, Cloudflare zone/account approval, EC2 EIP, AWS Parameter paths.
- Produces: one self-hosted Access application whose only allow path is a 90-day backend Service Token plus required EC2 EIP.

- [ ] **Step 1: Validate account/domain and show planned changes**

Confirm zone ownership and hostname availability. Present the Access application, exact Service Auth policy, one Service Token with 90-day expiry, EIP Require condition, one remotely managed Tunnel, and later public hostname. Obtain explicit external-change approval.

- [ ] **Step 2: Create Access application and policy first**

Create the application for `llm.${NEXTVISIT_BASE_DOMAIN}`. Policy is Action `Service Auth`, Include `Service Token` equal to exactly the new backend token, Require IP equal to the EC2 EIP, with no Bypass or extra Includes. Read it back via dashboard/API and record the non-secret IDs/policy shape.

- [ ] **Step 3: Store Access values only in AWS**

The owner enters Client ID/Secret into their exact SecureString paths without chat or logs and writes the expiry date to the non-secret expiry String path. Run Parameter metadata preflight only; keep backend LLM false.

- [ ] **Step 4: Create the Tunnel without a public hostname**

Create one remotely managed Tunnel and verify it has no route. The owner writes the raw connector token to `/etc/nextvisit/llm.token` using a history-free protected editor; set root:65532 and 0440; remove the legacy env only after confirming it contains no other value. Never display either token.

- [ ] **Step 5: Add the public hostname last and start final Compose**

Route `llm.${NEXTVISIT_BASE_DOMAIN}` to `http://ollama:11434`, then have the owner install the reviewed unit as a root-owned systemd file and run `systemctl enable --now nextvisit-llm.service`; the service itself runs as `nextvisit-runner`. Immediately prove no host port 11434, one healthy connector, unauthenticated denial, and authenticated 200 via the EC2 file-credential smoke. If any check fails, remove the public hostname first and keep backend LLM false.

### Task 9: Register and test the runner only if workflow restriction is proven

**Files:**
- GitHub Organization and laptop runner state; docs evidence only.

**Interfaces:**
- Consumes: Organization/repository names and the exact workflow `${NEXTVISIT_GITHUB_ORG}/${NEXTVISIT_GITHUB_REPO}/.github/workflows/llm-deploy.yml@refs/heads/main` formed from verified account state.
- Produces: one runner in `llm-production` or a documented manual-deploy decision.

- [ ] **Step 1: Create/read back the runner group before registration**

Require `visibility=selected`, exactly one selected repository, `restricted_to_workflows=true`, exactly one selected main workflow, and non-public access. Verify through both UI and REST response fields. If any field is unavailable or read-only in the wrong state, stop without a runner.

- [ ] **Step 2: Register with history disabled and no reusable credential**

The owner obtains the short-lived registration token in GitHub, opens a history-disabled shell as `nextvisit-runner`, and runs GitHub's exact generated registration command for group `llm-production` and labels `self-hosted,linux,llm`. The token is not pasted into chat, saved in a file, or logged. Install/start the official runner service and confirm it is idle.

- [ ] **Step 3: Keep admission false for the first negative proof**

With `LLM_DEPLOY_ENABLED=false`, dispatch the workflow and require the deploy job to be skipped without the laptop executing code. Confirm no PR workflow can target the group.

- [ ] **Step 4: Enable once, deploy the reviewed SHA, then inspect**

After user approval set the variable true, dispatch one main workflow, and require stable staging, exact SHA, local smoke, no host port/token metadata, model volume preservation, and service health. Set the variable false again while performing reboot/rollback tests.

- [ ] **Step 5: Reboot recovery**

After another user-approved reboot and availability confirmation, require Docker, runner, systemd LLM stack, model, GPU, Tunnel, and external Access smoke to recover without reinstall or model download.

### Task 10: Rehearse rollback and credential incidents before handoff

**Files:**
- Modify: `infra/llm/OWNER_CHECKLIST.md`
- Modify: `docs/operations/full-service-runbook.md`

**Interfaces:**
- Consumes: working current/previous release, Access and Tunnel admin ownership.
- Produces: tested normal rollback and separate compromise procedures, while backend LLM remains false.

- [ ] **Step 1: Rehearse normal rollback in safe order**

Set `LLM_DEPLOY_ENABLED=false`, cancel/recheck all queued/running laptop deploys, keep backend LLM false, atomically select the previous release, restart exact final stack without volumes deletion, and rerun local/external smoke.

- [ ] **Step 2: Rehearse Tunnel token compromise procedure**

Rotate the token, force-disconnect every existing connector, have the owner replace the protected raw file, recreate the legitimate cloudflared container, and prove only that connector plus both Access smoke directions. A simple token rotation without forced disconnect is failure.

- [ ] **Step 3: Rehearse Access token compromise procedure**

Immediately delete the suspected token, issue a new one, replace the policy's sole Include, have the owner replace both AWS SecureStrings and expiry String, restart API while LLM false, run authenticated/unauthenticated smoke, then consider re-enable. The old policy/token/secret identifiers must be absent.

- [ ] **Step 4: Finalize evidence and commit docs**

Record dates, commit SHA, image digests, package versions, runner group policy fields, GPU percentage, three latency values, reboot result, fixed smoke results, and rotation/rollback PASS. Record no credentials, response bodies, prompts, questions, or user data.

```bash
git add infra/llm/OWNER_CHECKLIST.md docs/operations/full-service-runbook.md
git commit -m "docs: LLM 노트북 운영 인수 결과 기록"
```
