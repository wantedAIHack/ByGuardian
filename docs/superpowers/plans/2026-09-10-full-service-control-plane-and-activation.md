# Full Service Control Plane and Activation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the reviewed monorepo into the owner's private GitHub Organization, create the AWS/Cloudflare control planes in safe order, deploy FE/BE/LLM, and hand off a rehearsed service.

**Architecture:** This is the orchestration plan, not a duplicate implementation plan. It consumes the source-complete, AWS-source-complete, and laptop-offline-complete milestones, then crosses one external gate at a time: GitHub ownership, domain/Cloudflare, LLM-off AWS, Pages, Access/Tunnel, LLM enable, compatibility baseline, and rollback evidence.

**Tech Stack:** GitHub Organization/private repository/Actions, Cloudflare Pages/DNS/Zero Trust Access/Tunnel, AWS CloudFormation/EC2/ECR/S3/SSM/KMS/OIDC, React PWA, Spring/PostgreSQL/Caddy, Ubuntu/NVIDIA/Ollama, Playwright, shell verification.

**Spec:** `docs/superpowers/specs/2026-09-10-full-service-deployment-design.md`

## Global Constraints

- Complete all tasks in `2026-09-10-source-integration-and-hosted-ci.md`, AWS Tasks 1-12, and Ubuntu/LLM Tasks 1-6 before starting external changes.
- The laptop remains offline until the owner explicitly reports it powered on. GitHub/AWS/Pages source work does not authorize an SSH attempt.
- Never request or accept passwords, Tunnel tokens, Access secrets, GitHub registration tokens, AWS secret values, payment details, or MFA codes in chat.
- The owner alone accepts terms, enters billing/MFA, purchases a domain, enters secret values in protected UI/host input, supplies sudo, and approves reboot and billable change sets.
- Use one private GitHub Organization monorepo and one `main`. All PR tests are hosted; the laptop receives only the selected main deployment workflow.
- Hostnames are `app.${NEXTVISIT_BASE_DOMAIN}`, `api.${NEXTVISIT_BASE_DOMAIN}`, and `llm.${NEXTVISIT_BASE_DOMAIN}` after the owner sets the one validated base domain.
- Deployment order is BE with LLM/demo false, FE, laptop GPU, Access policy, Tunnel route, restricted runner, then backend LLM true. Do not skip forward.
- No public API/DB/Ollama port other than Caddy 80/443. `api` is DNS-only to EC2; `app` is Pages; `llm` is proxied Access/Tunnel.
- No external step is complete until read-back verification and a secret-free evidence record exist.
- A failed gate closes the associated deployment admission variable before rollback and leaves deterministic template behavior available.
- Credential compromise overrides normal rollback: revoke/rotate the suspected credential first.
- Public test data is synthetic. Actual user data requires a separately approved privacy/retention/deletion decision.
- Breaking API changes remain prohibited through 2026-10-20; after that they require a versioned endpoint.

---

## Execution Order

```text
Source integration plan (all)
        |
        +--> AWS plan Tasks 1-7 (source only) ---------+
        |                                               |
        `--> Ubuntu/LLM plan Tasks 1-4 (offline only) --+--> AWS Task 8
                                                        |
                                             Ubuntu Tasks 5-6
                                                        |
                                               AWS Tasks 9-12
                                                        |
                                                        v
This plan Task 1 owner inputs
        -> Task 2 GitHub Organization/main
        -> Task 3 domain/Cloudflare account
        -> Task 4 AWS plan Task 13 (LLM off)
        -> Task 5 Pages (LLM off)
        -> Task 6 Ubuntu/LLM plan Tasks 7-10
        -> Task 7 LLM enable/fallback
        -> Task 8 N/N-1 compatibility
        -> Task 9 rollback/incident rehearsal
        -> Task 10 owner handoff
```

## File and Interface Map

| Area | File | Responsibility |
| --- | --- | --- |
| Non-secret inputs | `scripts/operations/validate-owner-inputs.sh` | Validate Organization/repo/domain/account/region/budget names without values from secret stores |
| Full owner gate | `docs/operations/OWNER_CHECKLIST.md` | Human approvals and read-back evidence in exact order |
| Detailed commands | `docs/operations/full-service-runbook.md` | Self-contained GitHub/AWS/Cloudflare/laptop commands |
| Live canary | `infra/backend/scripts/canary-full-service.sh` | Internal marked fixture plus public health/question checks with bodies held in private temporary files |
| Compatibility | `scripts/ci/compatibility-e2e.sh`, `.github/workflows/ci.yml` | `{new FE, previous BE}` and `{previous FE, new BE}` browser/API proof |
| Service state | `README.md`, `frontend/README.md`, `backend/README.md`, `infra/llm/README.md` | What is live, what is disabled, and where operators start |

Required non-secret operator inputs:

```text
NEXTVISIT_GITHUB_ORG
NEXTVISIT_GITHUB_REPO
NEXTVISIT_BASE_DOMAIN
NEXTVISIT_AWS_ACCOUNT_ID
AWS_REGION=ap-northeast-2
NEXTVISIT_MONTHLY_BUDGET_USD
NEXTVISIT_ALERT_EMAIL
```

### Task 1: Create a fail-closed owner input and approval gate

**Files:**
- Create: `scripts/operations/validate-owner-inputs.sh`
- Create: `scripts/operations/validate-owner-inputs-test.sh`
- Create: `docs/operations/OWNER_CHECKLIST.md`
- Modify: `docs/operations/full-service-runbook.md`

**Interfaces:**
- Consumes: the seven non-secret inputs above.
- Produces: normalized account/domain/repository identifiers and a checklist that never stores a credential.

- [ ] **Step 1: Write failing validation fixtures**

Reject unset/blank inputs, GitHub names with slashes or shell metacharacters, internationalized or wildcard domains, subdomain values passed as the base domain, non-12-digit AWS IDs, any region except `ap-northeast-2`, nonpositive/non-integer budget, and malformed alert email. Accept one fixed synthetic fixture.

- [ ] **Step 2: Run RED**

```bash
sh scripts/operations/validate-owner-inputs-test.sh
```

Expected: missing validator.

- [ ] **Step 3: Implement validation without exporting or logging secrets**

Use POSIX shell and fixed diagnostics. Print only normalized non-secret names after success. Explicitly reject environment names containing `PASSWORD`, `TOKEN`, `SECRET`, `PRIVATE_KEY`, or `MFA` so callers do not accidentally route secrets through this interface.

- [ ] **Step 4: Write the owner checklist in gate order**

Each section has `planned`, `approved`, `applied`, `verified`, and `rollback-tested` boxes where relevant. Include account/MFA/billing, password rotation, Organization transfer, domain/zone, budget/change set, DB secret entry, API, Pages, GPU/reboot, Access-before-Tunnel, runner policy, LLM enable, backup/restore, rollback, credential rotation, privacy decision, and final sign-off.

- [ ] **Step 5: Verify and commit**

```bash
sh scripts/operations/validate-owner-inputs-test.sh
shellcheck scripts/operations/validate-owner-inputs.sh scripts/operations/validate-owner-inputs-test.sh
git diff --check
git add scripts/operations/validate-owner-inputs.sh scripts/operations/validate-owner-inputs-test.sh docs/operations/OWNER_CHECKLIST.md docs/operations/full-service-runbook.md
git commit -m "docs: 전체 서비스 소유자 승인 gate 추가"
```

### Task 2: Land reviewed source on main and transfer the private repository

**Files:**
- Git refs/remotes and GitHub settings; append non-secret evidence to `docs/operations/OWNER_CHECKLIST.md`.

**Interfaces:**
- Consumes: clean reviewed integration SHA, existing private origin `git@github.com:y-minion/wanted_Hackaton.git`, owner-selected Organization/repository.
- Produces: private Organization repository whose `main` is the exact reviewed SHA and whose stable required check is `ci-gate`.

- [ ] **Step 1: Re-run all local milestone gates and review the commit graph**

Run the complete source, AWS-source, and LLM-offline verification blocks from the three detailed plans. Require clean worktree, no unexpected merge commits, and no secret marker. Show the exact integration SHA to the owner.

- [ ] **Step 2: Obtain explicit main-update and remote-write approval**

Present that the current remote main is behind local work, the push is a fast-forward, the private repository will transfer ownership, and existing clone URLs/Apps/settings need revalidation. Do not push or transfer on a generic “continue” message.

- [ ] **Step 3: Fast-forward local main in its own worktree**

```bash
git -C /Users/youngmin/FullStack/wanted_Hackaton/.worktrees/main-llm-merge status --porcelain
git -C /Users/youngmin/FullStack/wanted_Hackaton/.worktrees/main-llm-merge merge --ff-only codex/full-service-integration
test "$(git -C /Users/youngmin/FullStack/wanted_Hackaton/.worktrees/main-llm-merge rev-parse HEAD)" = "$(git rev-parse codex/full-service-integration)"
```

Expected: clean fast-forward only; no force operation.

- [ ] **Step 4: Push main, verify hosted CI, then have the owner transfer**

With explicit approval, push main normally to the existing private origin. Require `ci-gate` success. The owner creates the Organization with MFA/billing choices and transfers the repository through GitHub's protected UI. Do not create a new disconnected repository or squash history.

- [ ] **Step 5: Update and verify the canonical remote**

Form `git@github.com:${NEXTVISIT_GITHUB_ORG}/${NEXTVISIT_GITHUB_REPO}.git`, set origin in all three worktrees, fetch, and assert remote `main` equals local. Verify repository visibility private, default branch main, Actions default permissions read-only, and Cloudflare GitHub App not yet installed.

- [ ] **Step 6: Configure main rules without relying on unavailable paid features**

Require PR before merge, `ci-gate`, conversation resolution, stale-approval dismissal where available, no force push/deletion, and administrator application. Add CODEOWNERS enforcement for workflows/infra only if the owner identifies a second trusted reviewer; never invent a team slug. Record whether private dependency review/runner workflow restriction requires an upgraded plan. Keep laptop runner unregistered.

- [ ] **Step 7: Observe and retire the one-time OIDC claim probe**

With a separate approval, set `OIDC_CLAIM_PROBE_ENABLED=true`, manually run the reviewed probe on exact main once, and record only `sub`, `aud`, repository owner ID, and repository ID. Immediately set the variable false. Delete `.github/workflows/oidc-claim-probe.yml` on a normal reviewed branch, merge only after `ci-gate`, and assert the file is absent from remote main before creating any AWS OIDC trust or setting `BACKEND_DEPLOY_ENABLED=true`.

### Task 3: Establish the domain and Cloudflare account without exposing an origin

**Files:**
- Cloudflare account/zone state; checklist evidence only.

**Interfaces:**
- Consumes: owner-controlled account, MFA, payment/terms, and validated base domain.
- Produces: active Cloudflare zone with no LLM public hostname and no Pages project yet.

- [ ] **Step 1: Present account/domain costs and ownership choices**

The owner chooses or purchases the base domain, creates the Cloudflare account, accepts terms/payment, enables MFA, and adds the zone. The agent may guide visible settings but never enters payment/MFA or claims ownership.

- [ ] **Step 2: Verify zone authority and DNSSEC plan read-only**

Read back zone ID/status/nameservers, registrar delegation, DNSSEC availability, and whether conflicting `app`, `api`, or `llm` records exist. Record IDs/names only. Do not create the LLM hostname.

- [ ] **Step 3: Approve the exact future record set**

```text
app: Cloudflare Pages custom domain
api: DNS-only A -> CloudFormation ElasticIp
llm: Access-protected proxied Tunnel route, created last
```

Require the owner to approve this record plan before Task 4.

### Task 4: Execute the AWS plan's LLM-disabled live gate

**Files:**
- AWS state and runbook evidence.

**Interfaces:**
- Consumes: AWS plan Tasks 1-12, Organization OIDC claim, base domain, account/budget/alert inputs.
- Produces: `https://api.${NEXTVISIT_BASE_DOMAIN}` with DB-backed health, backup/restore/rollback, demo false, LLM false.

- [ ] **Step 1: Execute AWS Task 13 Steps 1-4 with its separate approvals**

Apply budget first; create/review the production change set; verify retained resources and monthly estimate; apply only after approval; validate SSM/EBS/IMDS/container metadata boundary; then have the owner enter only the DB SecureString.

- [ ] **Step 2: Create the DNS-only API record before Caddy certificate issuance**

Point `api.${NEXTVISIT_BASE_DOMAIN}` directly to the exact CloudFormation EIP with Cloudflare proxy disabled. Read back public DNS from two resolvers and verify only ports 80/443 are reachable.

- [ ] **Step 3: Deploy the exact main SHA with both optional features false**

Require successful hosted CI/workflow_run, immutable ECR digest, S3 bundle checksum, one tagged SSM target, Compose wait, external HTTPS health, exact CORS, and no API/DB host port.

- [ ] **Step 4: Complete AWS Task 13 Steps 5-7**

Prove persistence across deploy, template behavior with unreachable LLM, remote backup checksum, measured RPO under 300 seconds, isolated restore under 2 hours, safe rollback, and secret-free evidence. Set `BACKEND_DEPLOY_ENABLED=false` after acceptance.

### Task 5: Deploy the PWA to Pages and verify the real browser boundary

**Files:**
- Cloudflare Pages/GitHub App state; evidence in owner checklist.

**Interfaces:**
- Consumes: private Organization repo main, `frontend/`, live API HTTPS, generated Pages assets.
- Produces: `https://app.${NEXTVISIT_BASE_DOMAIN}`, exact production SHA, secure headers, deep links, and LLM-off core journey.

- [ ] **Step 1: Approve least-privilege GitHub App and Pages settings**

Install the Cloudflare GitHub App for the single repository. Set production branch `main`, root `frontend`, build `npm run build:pages`, output `dist`, `NODE_VERSION=22.22.2`, `VITE_API_BASE=https://api.${NEXTVISIT_BASE_DOMAIN}`; the renderer consumes `CF_PAGES_COMMIT_SHA`. Disable preview deployments or protect them with Access. Keep Web Analytics and Logpush off.

- [ ] **Step 2: Build and inspect the candidate before custom domain**

Require Pages build SHA equals main, `build.json` equals that SHA/API origin, `_headers` has exact CSP/HSTS/nosniff/no-referrer/permissions, and `_redirects` has only the SPA fallback. No wildcard/local API/third-party source may appear.

- [ ] **Step 3: Attach the custom domain through Pages, then add redirect policy**

Use the Pages custom-domain flow rather than manually pre-creating its CNAME. After active TLS, add a Cloudflare redirect rule from the project `pages.dev` hostname to `app.${NEXTVISIT_BASE_DOMAIN}` preserving path/query. Do not redirect API or LLM hosts.

- [ ] **Step 4: Run live browser canary with LLM and demo off**

Verify `/`, `/onboarding`, `/recover`, `/demo`, and `/t` direct entry/refresh; expected disabled demo response; and one browser-created case marked `NEXTVISIT_CANARY_V1_BROWSER` in relation and baseline free note. Because a new case is week 1, require the truthful empty prep-card message rather than fabricating later weeks through the public API. The internal Task 4 `template-ready` canary separately proves the six-week all-template API path, while the local Playwright journey proves the complete weekly UI. Also require exact CORS positive and preview/evil-origin negatives, PWA update, and no API response in Cache Storage. Record only the browser case ID for the deliberate synthetic retention list.

- [ ] **Step 5: Prove therapist tokens never reach Pages**

Issue a synthetic therapist link, confirm share format `/t#UUID`, first Pages request path `/t`, immediate address scrub, same-tab refresh recovery, separate-tab isolation, no third-party request/referrer, and token absence from Pages logs/history/build output. Keep analytics/log export disabled.

### Task 6: Execute the Ubuntu/LLM live gates only after the laptop returns online

**Files:**
- Laptop, Cloudflare Access/Tunnel, optional GitHub runner; secret-free checklist evidence.

**Interfaces:**
- Consumes: explicit availability message and Ubuntu/LLM plan Tasks 1-6.
- Produces: GPU/reboot-safe LLM, Access-protected external route, and either restricted automated deployment or audited manual deployment.

- [ ] **Step 1: Execute Ubuntu/LLM Task 7 exactly**

Read-only SSH identity check only after the owner says online; owner rotates the exposed password; present bootstrap changes; obtain sudo/reboot approval; wait for explicit post-reboot availability; prove Docker/NVIDIA, model persistence, three sub-45-second smokes, and 100% GPU.

- [ ] **Step 2: Execute Ubuntu/LLM Task 8 exactly**

Create/read back Access application and sole Service Auth policy before Tunnel public hostname; owner stores Access values only in AWS and Tunnel token only in the protected laptop file; then create route and prove unauth denial/auth success without response bodies.

- [ ] **Step 3: Execute Ubuntu/LLM Task 9 conditionally**

If selected repository/workflow restriction is proven, register and test one runner with admission false first, then one approved deployment. Otherwise record manual deployment, do not register a runner, and leave `LLM_DEPLOY_ENABLED=false`.

- [ ] **Step 4: Execute Ubuntu/LLM Task 10 incident rehearsals**

Prove normal rollback, full Tunnel connector disconnect on token compromise, and Access token/policy/AWS secret replacement while backend LLM remains false.

### Task 7: Enable backend LLM last and prove both success and fallback

**Files:**
- External runtime state and the already-reviewed `infra/backend/scripts/canary-full-service.sh`
- Modify: `docs/operations/OWNER_CHECKLIST.md`
- Modify: `docs/operations/full-service-runbook.md`

**Interfaces:**
- Consumes: `canary-full-service.sh MODE APP_BASE_URL API_BASE_URL`, healthy FE/API/DB, Access/Tunnel, Access file secrets on EC2, and exact `qwen3:4b-q8_0`; mode is exactly `llm-success|template-fallback` here.
- Produces: verified `LLM_PENDING -> LLM_DONE`/`source=LLM` and outage `LLM_FAILED`/whole-batch template fallback.

- [ ] **Step 1: Re-run the fake-response and fixture tests before live use**

```bash
sh infra/backend/tests/canary-full-service-test.sh
```

Require the exact current release, private one-shot manifest, fixed `NEXTVISIT_CANARY_V1` relation/baseline marker, backdated six-week STROKE/RIGHT/OFTEN seed, public demo disabled, bounded requests, and no provider/body/token/free-note sentinel on either output stream. No live request runs until this remains GREEN.

- [ ] **Step 2: Materialize the Access pair and preview the LLM overlay**

Run metadata preflight, have the owner confirm the existing SecureStrings, materialize both files, render Compose, and prove only the API service changes, all three secrets are file mounts, and no actual value appears in Env/Cmd/Labels. Present the backend change and get explicit LLM-enable approval.

- [ ] **Step 3: Enable and prove the success path without production demo**

Deploy the LLM overlay by immutable backend release while `NEXTVISIT_DEMO_ENABLED=false`. From an SSM session on EC2 run the exact current release's `canary-full-service.sh llm-success APP_BASE_URL API_BASE_URL`. Its non-web, no-port, LLM-off fixture creates six synthetic weeks and a private guardian manifest; only the normal public API's week-6 update may trigger generation. Observe PENDING before DONE, every question `source=LLM`, the exact finite-surface guard, and latency under the configured timeout. The trap must remove the fixture container and every bearer/body file. Record only the returned non-secret case ID.

- [ ] **Step 4: Prove whole-batch fallback without risking user data**

Before real users, close LLM deployment admission, present the bounded outage window and obtain explicit approval, temporarily stop the legitimate Tunnel connector, then run `canary-full-service.sh template-fallback APP_BASE_URL API_BASE_URL`. Require API save success plus `LLM_FAILED` with every question still `source=TEMPLATE`; no partial LLM batch. Restart Tunnel, rerun Access smoke, and rerun `llm-success` with a fresh fixture to require a later generation DONE. Record only the two new non-secret case IDs; never retain bearer or recovery values.

- [ ] **Step 5: Verify health isolation and retain marked rows deliberately**

During Tunnel outage `/health` must remain `status=ok, db=up`, FE must remain usable, and no availability alert may treat LLM as API failure. This activation introduces no public/hidden delete endpoint and runs no ad-hoc SQL. The checklist must state that the recorded `NEXTVISIT_CANARY_V1` rows are synthetic, excluded from product/user metrics, and intentionally retained pending the separately approved data-lifecycle implementation.

- [ ] **Step 6: Close admission and commit secret-free evidence**

Set `BACKEND_DEPLOY_ENABLED=false`, verify no queued/running backend deployment remains, and keep the accepted runtime LLM setting unchanged. Commit only fixed results and case IDs:

```bash
git add docs/operations/OWNER_CHECKLIST.md docs/operations/full-service-runbook.md
git commit -m "docs: 전체 서비스 LLM 활성화 검증 기록"
```

### Task 8: Establish the first production compatibility baseline

**Files:**
- Create: `scripts/ci/compatibility-e2e.sh`
- Create: `scripts/ci/compatibility-e2e-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `frontend/e2e/critical-flow.spec.ts`
- Modify: `docs/operations/full-service-runbook.md`

**Interfaces:**
- Consumes: first successful integrated production full SHA as `COMPAT_BASE_SHA`.
- Produces: required matrix `{current FE, baseline BE}` and `{baseline FE, current BE}` using isolated ports/projects.

- [ ] **Step 1: Write failing ref/path/matrix fixtures**

Reject missing/non-40-hex/non-ancestor baseline, same directory used for both refs, dirty checkouts, missing FE/BE, port/project collision, and either skipped matrix leg. The harness accepts exactly `CURRENT_ROOT BASELINE_ROOT MATRIX_LEG` where leg is `new-fe-old-be|old-fe-new-be`.

- [ ] **Step 2: Run RED**

```bash
sh scripts/ci/compatibility-e2e-test.sh
```

- [ ] **Step 3: Implement isolated checkouts and the two real journeys**

CI checks out current and the exact baseline SHA into separate directories with credentials disabled. Each leg starts its own PostgreSQL project and ports, runs the selected backend and built FE, exercises the core Playwright contract, then cleans only its recorded project/PIDs. Both legs use LLM false and synthetic data.

- [ ] **Step 4: Make the baseline mandatory after first release**

Set repository variable `COMPAT_BASE_SHA` to the exact accepted production SHA and protect a corresponding non-moving tag. Change `ci-gate` so missing/failed compatibility is rejection on every subsequent PR. Do not silently advance the variable; a new baseline requires completed N/N-1 evidence and owner approval.

- [ ] **Step 5: Verify mutations and commit**

```bash
sh scripts/ci/compatibility-e2e-test.sh
sh infra/llm/tests/workflows-test.sh
actionlint -ignore 'label "llm" is unknown' .github/workflows/*.yml
git add scripts/ci/compatibility-e2e.sh scripts/ci/compatibility-e2e-test.sh .github/workflows/ci.yml frontend/e2e/critical-flow.spec.ts docs/operations/full-service-runbook.md
git commit -m "ci: FE BE 이전 release 호환성 고정"
```

### Task 9: Rehearse release rollback and credential emergencies end to end

**Files:**
- Modify: `docs/operations/OWNER_CHECKLIST.md`
- Modify: `docs/operations/full-service-runbook.md`

**Interfaces:**
- Consumes: previous Pages deployment, previous backend digest/release, previous laptop release, backup/restore, credential admin access.
- Produces: dated evidence for FE/BE/LLM rollback and separate Access/Tunnel compromise branches.

- [ ] **Step 1: FE rollback**

Promote the previous successful Pages deployment, verify build SHA/security headers/core route, then re-promote current. New BE must accept both FE builds.

- [ ] **Step 2: BE rollback**

Set `BACKEND_DEPLOY_ENABLED=false`, cancel/recheck queued/running backend deploys, keep LLM false during the operation, select previous digest/release, verify health/data, then redeploy current. If migration manifest says unsafe, close writes and use forward-fix/restore instead of automatic rollback.

- [ ] **Step 3: LLM rollback/offline behavior**

Set `LLM_DEPLOY_ENABLED=false`, cancel/recheck laptop deploys, set backend LLM false, select previous laptop release without deleting volume, and verify API/template behavior. Restore current only after local/external smoke.

- [ ] **Step 4: Credential incidents**

For suspected Tunnel token, rotate, force-disconnect all connectors, replace the laptop file, recreate legitimate cloudflared, and smoke. For suspected Access token, revoke first, issue new, replace sole policy Include and AWS secret pair/expiry, restart API with LLM false, smoke, then re-enable. Never wait for normal rollback before revocation.

- [ ] **Step 5: Data recovery**

Create the temporary restore stack using the MFA operator role, recover the selected base+WAL cutoff, prove the synthetic marker and RPO/RTO, then obtain explicit cleanup approval. Never attach production EBS to the drill.

### Task 10: Produce the owner's final brain-state handoff

**Files:**
- Modify: `README.md`
- Modify: `frontend/README.md`
- Modify: `backend/README.md`
- Modify: `infra/llm/README.md`
- Modify: `infra/llm/OWNER_CHECKLIST.md`
- Modify: `docs/operations/OWNER_CHECKLIST.md`
- Modify: `docs/operations/full-service-runbook.md`

**Interfaces:**
- Consumes: actual resource names, final SHA/digests, live gate results, rollback/rotation results.
- Produces: one truthful entry point and a complete owner checklist without secrets.

- [ ] **Step 1: Update status from observed reality only**

Root README states live URLs by hostname, module status, LLM enabled/disabled state, current production SHA, and links to module docs/runbook/checklist. Module READMEs own only their local/runtime specifics. Remove every “parallel branch,” “not implemented,” or “pending” claim that the evidence closed; retain unchecked gates exactly.

- [ ] **Step 2: Record the operating inventory**

Record GitHub Organization/repository, AWS region/stack names/resource IDs, Cloudflare project/zone/application/tunnel names, runner group/name, image digests, model/version, backup prefixes, retention, alarms, token expiry dates, owner, last verification timestamp, and the non-secret case IDs carrying the two approved canary markers. Label those rows synthetic and pending the separate data-lifecycle decision. Do not record IP-bound personal data, secret values, response bodies, guardian/therapist tokens, or laptop password.

- [ ] **Step 3: Add a 30-minute owner reading order**

The checklist starts with: root README (product), approved full-service design (why), module READMEs (what), runbook (how), owner checklist (current external state), then dashboards in GitHub → AWS → Cloudflare → laptop order. Include “normal today,” “deploy,” “laptop offline,” “API incident,” “credential incident,” and “restore” entry points.

- [ ] **Step 4: Fresh final verification**

Run the entire hosted CI locally where possible, live public canary, AWS health/backup metrics, Access two-direction smoke, laptop GPU/model/port checks, compatibility matrix, and `git diff --check`. Re-read every document link and status statement against the current commit and dashboards.

- [ ] **Step 5: Commit docs and close deployment admissions by default**

```bash
git add README.md frontend/README.md backend/README.md infra/llm/README.md infra/llm/OWNER_CHECKLIST.md docs/operations/OWNER_CHECKLIST.md docs/operations/full-service-runbook.md
git commit -m "docs: 전체 서비스 운영 인수 문서 완료"
```

Leave `BACKEND_DEPLOY_ENABLED=false` and `LLM_DEPLOY_ENABLED=false` after handoff. `NEXTVISIT_LLM_ENABLED` reflects the accepted runtime state; any future change uses the runbook and a new reviewed release.
