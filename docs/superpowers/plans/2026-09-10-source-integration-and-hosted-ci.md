# Source Integration, Browser Boundary, and Hosted CI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge the completed React PWA into the BE/LLM source of truth, close the browser token and CORS boundaries, and make one hosted CI gate prove the LLM-disabled product flow.

**Architecture:** Preserve both branch histories in `codex/full-service-integration`, then add small repository-owned security helpers around the existing React/Spring applications. A loopback PostgreSQL/API/Vite stack and Playwright exercise the real cross-origin contract; GitHub-hosted jobs run the same checks and never send PR code to the laptop.

**Tech Stack:** Git, Node 22.22.2, React 19, TypeScript 5, Vite 7, Vitest 3, Playwright 1.63.0, Java 21, Spring Boot 3.5.5, PostgreSQL 16, Docker Compose v2, POSIX shell, ShellCheck, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-10-full-service-deployment-design.md`

## Global Constraints

- Work only in `/Users/youngmin/FullStack/wanted_Hackaton/.worktrees/full-service-integration` on `codex/full-service-integration`; do not edit the `feat/frontend` or `main` worktrees.
- Preserve the root worktree's untracked `docs/service-reference.html` byte-for-byte and keep it out of every commit.
- Merge `feat/frontend` history rather than copying `frontend/`; preserve main's LLM/Flyway/generation-CAS work and the FE branch's recovery-code, catalog-label, sleep, and density work together.
- Remove `.context/codex-session-id` from the integrated tree and ignore `.context/`.
- Use Node exactly `22.22.2` and Java 21. Local Gradle uses `/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home`.
- Keep `NEXTVISIT_LLM_ENABLED=false` throughout this plan. Do not contact `ssh llm`, Cloudflare, AWS, or a real model.
- Preserve the deterministic rules/template path and all 250 Java tests and 205 FE tests before adding new tests.
- The browser may call only its configured API origin. Production CORS allows only the exact app origin, `Content-Type`, `X-Guardian-Token`, and GET/POST/PUT/PATCH/OPTIONS; credentials remain disabled.
- Therapist shares use `https://app-domain/t#token`; the fragment is scrubbed before network activity, held only in tab-scoped `sessionStorage`, and never cached by the service worker.
- Do not remove or change existing API fields or endpoint meanings through 2026-10-20. New response fields remain additive.
- Pin every third-party GitHub Action by full commit SHA. All PR code runs on GitHub-hosted runners with `contents: read`; never use `pull_request_target`.
- The private repository baseline does not assume a GitHub Code Security license. A pinned OSV inventory scan, a full-history TruffleHog diff scan, and explicit manifest/lockfile consistency checks are mandatory; GitHub's native Dependency Review may be added only after the owner approves the required Organization plan/license.
- Commit after each independently reviewable task and run `git diff --check` before every commit.

---

## File and Interface Map

| Area | File | Responsibility |
| --- | --- | --- |
| Merge safety | `scripts/ci/repository-contract-test.sh` | Assert branch artifacts, source-of-truth files, and ignored local state |
| Product docs | `README.md`, `backend/README.md`, `frontend/README.md` | Current integrated state and module entry points |
| Runtime pin | `frontend/.nvmrc`, `frontend/package.json` | Exact local/CI/Pages Node version |
| Therapist secret | `frontend/src/lib/therapistToken.ts` | Capture, validate, scrub, store, and clear one tab's UUID token |
| Therapist UI | `frontend/src/screens/Therapist.tsx`, `frontend/src/ui/TherapistLinkPanel.tsx`, `frontend/src/screens/Demo.tsx`, `frontend/src/routes.tsx` | Fragment share URL and token-free Pages route |
| Browser edge assets | `frontend/cloudflare/_headers.template`, `frontend/cloudflare/_redirects`, `frontend/scripts/cloudflare-assets.mjs` | Render CSP, SPA fallback, and build metadata from validated inputs |
| CORS | `backend/api/src/main/java/nextvisit/api/common/CorsConfig.java` | Exact cross-origin methods and headers |
| Local stack | `infra/local/compose.integration.yml`, `scripts/ci/integration-e2e.sh` | Ephemeral PostgreSQL plus host API/FE lifecycle |
| Browser E2E | `frontend/playwright.config.ts`, `frontend/e2e/critical-flow.spec.ts` | Real LLM-disabled cross-origin and fragment-token journey |
| Hosted gate | `.github/workflows/ci.yml`, `scripts/ci/changed-areas.sh` | Path-aware hosted checks with one stable required result |
| Workflow policy | `infra/llm/tests/workflows-test.sh`, `infra/llm/tests/workflows-runner-group-test.sh` | Reject PR/self-hosted and workflow trust regressions |

### Task 1: Merge both histories without discarding either backend contract

**Files:**
- Merge: `feat/frontend` into `codex/full-service-integration`
- Delete: `.context/codex-session-id`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: approved design commit `36c007292d7d4d8ec86c4689b5d9b9bc9cacea4b`, main baseline `e131790646ace3be02202eff6f74965bfbcfc788`, FE head `c471a7b16001c1ce1ae2bcdc1bb0f4fa8ea20e90`.
- Produces: one merge commit containing `frontend/`, FE API additions, main's `infra/llm/`, and both sets of tests.

- [ ] **Step 1: Prove the refs and worktree are the reviewed inputs**

Run:

```bash
test "$(git branch --show-current)" = codex/full-service-integration
git merge-base --is-ancestor 36c007292d7d4d8ec86c4689b5d9b9bc9cacea4b HEAD
test -z "$(git diff --name-only 36c007292d7d4d8ec86c4689b5d9b9bc9cacea4b..HEAD | rg -v '^(docs/superpowers/specs/2026-09-10-full-service-deployment-design\.md|docs/superpowers/plans/2026-09-10-(source-integration-and-hosted-ci|aws-backend-production|ubuntu-llm-and-cloudflare-access|full-service-control-plane-and-activation)\.md)$' || true)"
test "$(git rev-parse main)" = e131790646ace3be02202eff6f74965bfbcfc788
test "$(git rev-parse feat/frontend)" = c471a7b16001c1ce1ae2bcdc1bb0f4fa8ea20e90
test "$(git merge-base main feat/frontend)" = 848df38ed3a02d631afa0cdfdc57dd75f1206532
test -z "$(git status --porcelain)"
git merge-tree --write-tree HEAD feat/frontend
```

Expected: every assertion succeeds and merge-tree prints a tree ID without conflict records. If a ref moved, stop and re-review the new diff instead of weakening the assertions.

- [ ] **Step 2: Start the real merge without committing it**

Run:

```bash
git merge --no-ff --no-commit feat/frontend
git diff --name-only --diff-filter=U
```

Expected: the second command is empty. `MERGE_HEAD` points to the reviewed FE commit.

- [ ] **Step 3: Remove the local session artifact and preserve both ignore rules**

Run `git rm .context/codex-session-id`, then edit `.gitignore` so it contains these active lines exactly once in addition to the existing Gradle/editor rules:

```gitignore
.context/
*.tsbuildinfo
frontend/node_modules/
frontend/dist/
frontend/.env
frontend/.env.*
!frontend/.env.example
infra/llm/.env
```

Run:

```bash
git add .gitignore
test -z "$(git ls-files '.context/*')"
test "$(rg -n '^\.context/$' .gitignore | wc -l | tr -d ' ')" -eq 1
test "$(rg -n '^\*\.tsbuildinfo$' .gitignore | wc -l | tr -d ' ')" -eq 1
test "$(rg -n '^infra/llm/\.env$' .gitignore | wc -l | tr -d ' ')" -eq 1
```

Expected: all assertions pass.

- [ ] **Step 4: Verify both sides before recording the merge**

Run:

```bash
cd frontend
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 npm ci
nvm exec 22.22.2 npm test
nvm exec 22.22.2 npm run typecheck
nvm exec 22.22.2 npm run build
cd ../backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home clean test
```

Expected: FE 205 tests pass, typecheck/build pass, and Java reports 250 tests with zero failures/errors.

- [ ] **Step 5: Inspect and commit the merge**

Run:

```bash
git status --short
git diff --check --cached
git diff --cached --name-status
git commit -m "merge: frontend 구현을 통합 브랜치에 결합"
git show --first-parent --stat --oneline HEAD
```

Expected: the commit has two parents and no untracked/session file is included.

### Task 2: Make the integrated documentation truthful and testable

**Files:**
- Create: `scripts/ci/repository-contract-test.sh`
- Create: `frontend/README.md`
- Modify: `README.md`
- Modify: `backend/README.md`
- Modify: `docs/superpowers/specs/2026-09-06-frontend-design.md`

**Interfaces:**
- Consumes: the merged file tree and the document priority in approved spec §4.2.
- Produces: `repository-contract-test.sh`, a POSIX command returning zero only when all three modules and their authoritative docs coexist without stale parallel-branch claims.

- [ ] **Step 1: Write the failing repository contract test**

Create a POSIX script that resolves the repository root from its own location and asserts:

```sh
test -d "$repo_root/frontend/src"
test -d "$repo_root/backend/api/src"
test -d "$repo_root/infra/llm"
test -f "$repo_root/frontend/README.md"
test -z "$(git -C "$repo_root" ls-files '.context/*')"
if rg -n 'frontend.*별도.*branch|프론트엔드.*미구현|LLM.*미착수|systemd로 Ollama' \
  "$repo_root/README.md" "$repo_root/backend/README.md"; then
  exit 1
fi
```

The runbook assertion is deliberately non-blocking in this task because the AWS plan creates it; all other assertions are blocking. Add a final fixed line `repository contract tests passed`.

- [ ] **Step 2: Run it and observe the missing/stale documentation failure**

Run:

```bash
sh scripts/ci/repository-contract-test.sh
```

Expected: non-zero because `frontend/README.md` is absent or a merged README still claims FE is separate.

- [ ] **Step 3: Rewrite module entry points from the actual merged tree**

Update `README.md` so its implementation status names all three implemented modules, its UX description matches the React screens, and deployment remains “not externally applied.” Update `backend/README.md` to remove the parallel-FE statement while retaining the Java 21, LLM-disabled default, 250-test, and H2/no-Docker local test facts.

Create `frontend/README.md` with these exact operational facts:

```text
Runtime: Node 22.22.2
Install: npm ci
Unit: npm test
Types: npm run typecheck
Build: npm run build
Local API: VITE_API_BASE=http://localhost:8080
Production API: VITE_API_BASE is the owner-approved https://api subdomain
```

Clarify that the production hostname is supplied as a protected deployment setting and is not a secret. Link the root README, frontend design, API design, and full-service deployment design.

- [ ] **Step 4: Add the therapist-fragment security addendum to the owning FE design**

Add an approved addendum dated 2026-09-10 that replaces public `/t/:token` share URLs with `/t#token`, requires synchronous fragment capture/scrubbing, tab-scoped storage, invalid-token clearing, no third-party therapist resources, and no API service-worker caching. Do not rewrite the historical implementation record; mark the new rule as a production security amendment.

- [ ] **Step 5: Run documentation truth checks**

Run:

```bash
sh scripts/ci/repository-contract-test.sh
rg -n 'Node 22\.22\.2|NEXTVISIT_LLM_ENABLED=false' frontend/README.md backend/README.md
git diff --check
```

Expected: contract script and searches pass; no stale-state pattern or whitespace error remains.

- [ ] **Step 6: Commit the source-of-truth docs**

```bash
git add README.md backend/README.md frontend/README.md docs/superpowers/specs/2026-09-06-frontend-design.md scripts/ci/repository-contract-test.sh
git commit -m "docs: 통합 저장소 기준 문서 정리"
```

### Task 3: Pin the FE runtime exactly

**Files:**
- Modify: `frontend/.nvmrc`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Create: `scripts/ci/toolchain-test.sh`

**Interfaces:**
- Consumes: installed Node 22.22.2 and Java toolchain 21.
- Produces: machine-readable exact Node contract for local, CI, and Pages.

- [ ] **Step 1: Write a failing toolchain contract**

Create `scripts/ci/toolchain-test.sh` with assertions equivalent to:

```sh
test "$(tr -d '[:space:]' < frontend/.nvmrc)" = 22.22.2
node_engine="$(node -e 'process.stdout.write(require("./frontend/package.json").engines.node)')"
test "$node_engine" = 22.22.2
test "$(node --version)" = v22.22.2
```

- [ ] **Step 2: Run it under Node 22.22.2 and observe the metadata failure**

Run:

```bash
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 sh scripts/ci/toolchain-test.sh
```

Expected: failure because `.nvmrc` says only `22` and `package.json` has no exact engine.

- [ ] **Step 3: Set both exact declarations**

Set `.nvmrc` to one line:

```text
22.22.2
```

Add this top-level `package.json` member and refresh only lock metadata with Node 22.22.2:

```json
"engines": {
  "node": "22.22.2"
}
```

Run `npm install --package-lock-only --ignore-scripts` from `frontend/`.

- [ ] **Step 4: Verify and commit the pin**

```bash
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 sh scripts/ci/toolchain-test.sh
nvm exec 22.22.2 npm --prefix frontend ci
nvm exec 22.22.2 npm --prefix frontend test
git add frontend/.nvmrc frontend/package.json frontend/package-lock.json scripts/ci/toolchain-test.sh
git commit -m "build: 프론트엔드 Node 버전 고정"
```

Expected: toolchain contract and all 205 existing FE tests pass.

### Task 4: Move therapist bearer tokens out of HTTP URLs

**Files:**
- Create: `frontend/src/lib/therapistToken.ts`
- Create: `frontend/src/lib/therapistToken.test.ts`
- Modify: `frontend/src/routes.tsx`
- Modify: `frontend/src/screens/Therapist.tsx`
- Modify: `frontend/src/screens/Therapist.test.tsx`
- Modify: `frontend/src/ui/TherapistLinkPanel.tsx`
- Modify: `frontend/src/ui/TherapistLinkPanel.test.tsx`
- Modify: `frontend/src/screens/Demo.tsx`
- Modify: `frontend/src/screens/Demo.test.tsx`
- Modify: `frontend/src/lib/types.ts`
- Modify: `frontend/src/test/setup.ts`
- Modify: `backend/api/src/main/java/nextvisit/api/demo/DemoResponse.java`
- Modify: `backend/api/src/main/java/nextvisit/api/demo/DemoService.java`
- Modify: `backend/api/src/test/java/nextvisit/api/demo/DemoFlowTest.java`

**Interfaces:**
- Consumes: backend UUID tokens and existing API `GET /t/{token}`.
- Produces: additive `DemoResponse.therapistToken`, `captureTherapistToken(): string | null`, `clearTherapistToken(): void`, `therapistShareUrl(token: string): string`; public FE route `/t` only.

- [ ] **Step 1: Add failing unit tests for capture, scrub, and isolation**

Cover these exact cases in `therapistToken.test.ts`:

```ts
expect(therapistShareUrl('123e4567-e89b-12d3-a456-426614174000'))
  .toBe(`${window.location.origin}/t#123e4567-e89b-12d3-a456-426614174000`);

window.history.replaceState(null, '', '/t#123e4567-e89b-12d3-a456-426614174000');
expect(captureTherapistToken()).toBe('123e4567-e89b-12d3-a456-426614174000');
expect(window.location.pathname + window.location.hash).toBe('/t');
expect(sessionStorage.getItem('nextvisit.therapist-token'))
  .toBe('123e4567-e89b-12d3-a456-426614174000');

window.history.replaceState(null, '', '/t#not-a-uuid');
expect(captureTherapistToken()).toBeNull();
expect(window.location.hash).toBe('');
expect(sessionStorage.getItem('nextvisit.therapist-token')).toBeNull();
```

Also clear `sessionStorage` in the test setup.

- [ ] **Step 2: Run the focused tests and observe the missing module failure**

```bash
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 npm --prefix frontend test -- src/lib/therapistToken.test.ts
```

Expected: module-not-found failure.

- [ ] **Step 3: Implement the finite UUID token store**

Use the exact storage key above and the lowercase UUID shape produced by `UUID.randomUUID().toString()`. `captureTherapistToken` must scrub `pathname + search` with `history.replaceState` before returning or starting a query; a present invalid fragment clears old storage. A fragment-free `/t` reuses only the current tab's stored valid UUID. `therapistShareUrl` throws on invalid input rather than constructing a URL.

- [ ] **Step 4: Write failing component tests for all URL producers and consumers**

Change assertions so `TherapistLinkPanel` and `Demo` show `/t#` followed by the fixture UUID. Add a backend assertion that `POST /demo` returns `therapistToken` while retaining the existing `therapistUrl` field unchanged for compatibility. In `Therapist.test.tsx`, navigate to `/t#` plus the UUID, assert the MSW API sees `/t/` plus that UUID, and assert `window.location.href` contains neither `#` nor the UUID before the response resolves. Add a 404 test that clears session storage and a network-failure test that keeps it for retry.

- [ ] **Step 5: Wire the token-free route and synchronous capture**

Change the route to:

```tsx
{ path: '/t', element: <Therapist /> }
```

Replace `useParams` with a lazy state initializer calling `captureTherapistToken`. Set the query `enabled` flag to `token !== null`, encode the UUID in the API path, and call `clearTherapistToken` only for an `ApiError` with status 404. Use `therapistShareUrl` in both UI producers. Extend the backend record and service with `link.token()` and mirror it in `frontend/src/lib/types.ts`; retain the old relative URL field but do not use it to build the public share link.

- [ ] **Step 6: Run tests and mutation checks**

```bash
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 npm --prefix frontend test -- src/lib/therapistToken.test.ts src/screens/Therapist.test.tsx src/ui/TherapistLinkPanel.test.tsx src/screens/Demo.test.tsx
nvm exec 22.22.2 npm --prefix frontend run typecheck
```

Temporarily remove the `replaceState` call and verify the focused test fails because the UUID remains in the location, then restore it and rerun GREEN.

- [ ] **Step 7: Commit the fragment boundary**

```bash
git add frontend/src/lib/therapistToken.ts frontend/src/lib/therapistToken.test.ts frontend/src/routes.tsx frontend/src/screens/Therapist.tsx frontend/src/screens/Therapist.test.tsx frontend/src/ui/TherapistLinkPanel.tsx frontend/src/ui/TherapistLinkPanel.test.tsx frontend/src/screens/Demo.tsx frontend/src/screens/Demo.test.tsx frontend/src/lib/types.ts frontend/src/test/setup.ts backend/api/src/main/java/nextvisit/api/demo/DemoResponse.java backend/api/src/main/java/nextvisit/api/demo/DemoService.java backend/api/src/test/java/nextvisit/api/demo/DemoFlowTest.java
git commit -m "fix: 치료사 토큰을 URL fragment로 격리"
```

### Task 5: Enforce the exact production CORS surface

**Files:**
- Create: `backend/api/src/test/java/nextvisit/api/common/CorsConfigTest.java`
- Modify: `backend/api/src/main/java/nextvisit/api/common/CorsConfig.java`

**Interfaces:**
- Consumes: property `nextvisit.cors.allowed-origins`.
- Produces: exact origin matching and the fixed header/method allowlist, with credentials disabled.

- [ ] **Step 1: Write preflight tests against real MockMvc OPTIONS requests**

Start a Spring test with `nextvisit.cors.allowed-origins=https://app.nextvisit.test`. Assert that a preflight from this exact origin requesting PATCH and `X-Guardian-Token` succeeds and returns that one origin. Assert `https://preview.nextvisit.test`, `https://app.nextvisit.test.evil.invalid`, `DELETE`, and `Authorization` receive no permissive CORS response.

- [ ] **Step 2: Run the focused class and observe wildcard/DELETE failures**

```bash
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home :api:test --tests nextvisit.api.common.CorsConfigTest
```

Expected: the current `setAllowedOriginPatterns`, `DELETE`, and `*` header configuration violate at least the negative assertions.

- [ ] **Step 3: Replace patterns with exact lists**

Set these values in `CorsConfig`:

```java
config.setAllowedOrigins(allowedOrigins);
config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
config.setAllowedHeaders(List.of("Content-Type", "X-Guardian-Token"));
config.setAllowCredentials(false);
config.setMaxAge(3600L);
```

Reject blank entries and any configured origin whose parsed URI is not an origin-only `http`/`https` URI; production validation later requires HTTPS.

- [ ] **Step 4: Verify and commit CORS**

```bash
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home :api:test --tests nextvisit.api.common.CorsConfigTest
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home test
cd ..
git add backend/api/src/main/java/nextvisit/api/common/CorsConfig.java backend/api/src/test/java/nextvisit/api/common/CorsConfigTest.java
git commit -m "fix: 운영 CORS 허용 범위 제한"
```

### Task 6: Generate versioned Cloudflare Pages security assets

**Files:**
- Create: `frontend/cloudflare/_headers.template`
- Create: `frontend/cloudflare/_redirects`
- Create: `frontend/scripts/cloudflare-assets.mjs`
- Create: `frontend/scripts/cloudflare-assets.test.mjs`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`

**Interfaces:**
- Consumes: `VITE_API_BASE` as one HTTPS origin and either local `VITE_BUILD_SHA` or Pages-provided `CF_PAGES_COMMIT_SHA` as forty lowercase hex characters; if both exist they must match.
- Produces: `dist/_headers`, `dist/_redirects`, and `dist/build.json`; `npm run build:pages` fails closed on invalid inputs.

- [ ] **Step 1: Write failing Node tests for the renderer**

Using `node:test` and a temporary directory, assert the rendered headers contain exactly one API origin in `connect-src`, `frame-ancestors 'none'`, `Referrer-Policy: no-referrer`, HSTS, `nosniff`, and no wildcard source. Assert invalid protocols, credentials, paths, query strings, non-40-character SHAs, and disagreeing Vite/Pages SHAs reject. Assert `build.json` contains only `commit` and `apiOrigin`.

- [ ] **Step 2: Run the test and observe the missing module failure**

```bash
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 node --test frontend/scripts/cloudflare-assets.test.mjs
```

Expected: module-not-found failure.

- [ ] **Step 3: Add fixed templates and a validating renderer**

The CSP template must be equivalent to:

```text
/*
  Content-Security-Policy: default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self' __API_ORIGIN__; manifest-src 'self'; worker-src 'self'
  Strict-Transport-Security: max-age=31536000; includeSubDomains
  X-Content-Type-Options: nosniff
  Referrer-Policy: no-referrer
  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()
```

The redirect file is exactly:

```text
/* /index.html 200
```

Export a pure `renderCloudflareAssets({ apiBase, buildSha, outputDir })` function and call it only from the CLI guard `import.meta.url === pathToFileURL(process.argv[1]).href`.

- [ ] **Step 4: Add the Pages build command and run it with fixed test inputs**

Add:

```json
"build:pages": "npm run build && node scripts/cloudflare-assets.mjs"
```

Run:

```bash
cd frontend
VITE_API_BASE=https://api.nextvisit.test VITE_BUILD_SHA=0123456789abcdef0123456789abcdef01234567 npm run build:pages
test "$(jq -r '.commit' dist/build.json)" = 0123456789abcdef0123456789abcdef01234567
rg -F "connect-src 'self' https://api.nextvisit.test" dist/_headers
```

- [ ] **Step 5: Verify mutation and commit**

Temporarily change `frame-ancestors 'none'` to `frame-ancestors *`; confirm the Node test fails, restore, and rerun it plus FE test/typecheck/build. Then commit:

```bash
git add frontend/cloudflare frontend/scripts/cloudflare-assets.mjs frontend/scripts/cloudflare-assets.test.mjs frontend/package.json frontend/package-lock.json
git commit -m "feat: Pages 보안 헤더 빌드 추가"
```

### Task 7: Add a real LLM-disabled PostgreSQL/browser journey

**Files:**
- Create: `infra/local/compose.integration.yml`
- Create: `scripts/ci/integration-e2e.sh`
- Create: `frontend/playwright.config.ts`
- Create: `frontend/e2e/critical-flow.spec.ts`
- Modify: `frontend/package.json`
- Modify: `frontend/package-lock.json`
- Modify: `.gitignore`

**Interfaces:**
- Consumes: loopback PostgreSQL `127.0.0.1:55432`, API `127.0.0.1:18080`, FE `127.0.0.1:14173`, and `NEXTVISIT_LLM_ENABLED=false`.
- Produces: `npm run test:e2e` and `scripts/ci/integration-e2e.sh`, both returning non-zero on lifecycle, CORS, storage, route, or product-flow failure.

- [ ] **Step 1: Install the exact browser test dependency**

From `frontend/`, run:

```bash
npm install --save-dev --save-exact @playwright/test@1.63.0
```

Add scripts `test:e2e:install` = `playwright install chromium` and `test:e2e` = `playwright test`. The hosted Ubuntu job invokes `playwright install --with-deps chromium` explicitly before the E2E lifecycle.

- [ ] **Step 2: Write the failing end-to-end journey**

Configure Chromium with base URL `http://127.0.0.1:14173`, one worker, no retries locally, trace on first retry, and output under ignored `frontend/test-results/`.

The test must:

1. open `/demo`, create the six-week demo, and observe the guardian home using the real API;
2. issue a therapist link and assert the displayed link contains `/t#`;
3. open it in a new page, assert the first FE request path is `/t` with no UUID, then assert the address bar is scrubbed and the summary loads;
4. refresh the scrubbed `/t` in the same tab and confirm it still loads from `sessionStorage`;
5. open plain `/t` in a distinct browser context and confirm it does not inherit the token;
6. inspect Cache Storage and assert no request whose origin is `http://127.0.0.1:18080` exists;
7. make a disallowed-origin preflight and assert it lacks `Access-Control-Allow-Origin`;
8. assert API `/health` remains OK while LLM is disabled.

- [ ] **Step 3: Run Playwright without the lifecycle and observe connection failure**

```bash
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 npm --prefix frontend run test:e2e:install
nvm exec 22.22.2 npm --prefix frontend run test:e2e
```

Expected: connection refused at the configured FE URL.

- [ ] **Step 4: Implement the bounded integration lifecycle**

The shell script must use `set -eu`, create a temporary log directory, and install an EXIT/HUP/INT/TERM trap before starting anything. It must:

```text
docker compose --project-name nextvisit-integration -f infra/local/compose.integration.yml up --detach --wait
start Gradle :api:bootRun with SERVER_PORT=18080, PostgreSQL URL, exact local CORS origin, demo=true, LLM=false
wait for /health with a bounded 60-second loop without printing response bodies
build FE with VITE_API_BASE=http://127.0.0.1:18080
start vite preview on 127.0.0.1:14173
wait for FE with a bounded 30-second loop
run Playwright Chromium
```

The trap kills only recorded child PIDs and runs `docker compose --project-name nextvisit-integration -f infra/local/compose.integration.yml down --volumes`; it never targets another project. On failure it prints fixed component/status diagnostics, not API/model bodies.

- [ ] **Step 5: Run GREEN twice and verify cleanup**

```bash
sh scripts/ci/integration-e2e.sh
sh scripts/ci/integration-e2e.sh
test -z "$(docker ps -aq --filter label=com.docker.compose.project=nextvisit-integration)"
```

Expected: both journeys pass independently and no integration container remains.

- [ ] **Step 6: Commit the local integration gate**

```bash
git add infra/local/compose.integration.yml scripts/ci/integration-e2e.sh frontend/playwright.config.ts frontend/e2e/critical-flow.spec.ts frontend/package.json frontend/package-lock.json .gitignore
git commit -m "test: LLM 비활성 전체 흐름 검증 추가"
```

### Task 8: Replace the path-filtered LLM workflow with one stable hosted CI gate

**Files:**
- Create: `.github/workflows/ci.yml`
- Create: `scripts/ci/changed-areas.sh`
- Create: `scripts/ci/changed-areas-test.sh`
- Create: `scripts/ci/dependency-contract-test.sh`
- Delete: `.github/workflows/llm-ci.yml`
- Modify: `infra/llm/tests/workflows-test.sh`
- Modify: `infra/llm/tests/workflows-runner-group-test.sh`
- Modify: `backend/api/build.gradle.kts`
- Modify: `backend/engine/build.gradle.kts`
- Create: `backend/api/gradle.lockfile`
- Create: `backend/engine/gradle.lockfile`
- Create: `backend/gradle/verification-metadata.xml`

**Interfaces:**
- Consumes: PR base/head SHAs and the Task 1-7 commands.
- Produces: jobs `changes`, `frontend`, `backend`, `infra`, `integration`, `security`, and stable required job `ci-gate`; all use hosted Ubuntu runners.

- [ ] **Step 1: Test path classification before writing the workflow**

Create fixture lists and assert `changed-areas.sh` emits GitHub-output booleans with these rules:

```text
frontend/**                         -> frontend=true, integration=true
backend/**                          -> backend=true, integration=true
infra/llm/** or workflow policy     -> infra=true
infra/local/** or scripts/ci/**     -> integration=true
package-lock/Gradle dependency file -> owning module=true
README/docs only                    -> all build areas false
every recognized or unknown change  -> security=true
```

Any unrecognized path sets all test areas true. This is the fail-closed default.

- [ ] **Step 2: Run the classifier test and observe the missing script failure**

```bash
sh scripts/ci/changed-areas-test.sh
```

Expected: failure because the classifier is absent.

- [ ] **Step 3: Implement the POSIX classifier and mutation-test its fallback**

Read newline-delimited paths from stdin and write only `key=true|false` lines to the path in `$GITHUB_OUTPUT`, or stdout when that variable is unset. Add a fixture with `unknown/security-sensitive.file` and prove every area becomes true.

- [ ] **Step 4: Write a failing dependency reproducibility contract**

Create `dependency-contract-test.sh` to require exact `package-lock.json` ownership for the FE package, `lockAllConfigurations()` in both Gradle projects, non-empty project-local `gradle.lockfile` files with no changing/dynamic version, and strict SHA-256 artifact entries in `backend/gradle/verification-metadata.xml`. Its diff-fixture mode rejects a changed `frontend/package.json` without `frontend/package-lock.json`, either changed Gradle build file without both owning lock and verification metadata, and any lock/verification file changed without its manifest/build file.

- [ ] **Step 5: Run RED, then generate reviewed Gradle locks and checksums**

```bash
sh scripts/ci/dependency-contract-test.sh
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home :api:dependencies :engine:dependencies --write-locks --write-verification-metadata sha256
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home clean test
cd ..
sh scripts/ci/dependency-contract-test.sh
```

Expected first: missing Gradle lock/verification failure. Add `dependencyLocking { lockAllConfigurations() }` to both project build files before the generation commands. Review every generated component/source repository and checksum diff; do not add ignored artifacts or a relaxed verification mode.

- [ ] **Step 6: Write one hosted workflow with immutable actions and scanner images**

Use only these audited action references in the initial workflow:

```yaml
actions/checkout@11d5960a326750d5838078e36cf38b85af677262
actions/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020
actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3
trufflesecurity/trufflehog@363923b901c911a9164f50b6c423f47c15372b1c
```

Set top-level `permissions: contents: read`; checkout `github.sha` with `persist-credentials: false` and `fetch-depth: 0` so the reviewed security range exists locally. Every job uses `ubuntu-latest`. `frontend` runs exact Node tests/types/build, `backend` runs Java clean test, `infra` runs ShellCheck/fake tests/Docker build/three Compose renders, and `integration` runs Task 7. The always-selected `security` job runs the dependency contract and OSV Scanner directly as the exact Linux/amd64 image `ghcr.io/google/osv-scanner:v2.5.1@sha256:1547b7c2783d4f266b24fe86ab4dfc18d058588244c58384ac9f56dddb304511`, with a read-only checkout mount, dropped capabilities, `no-new-privileges`, a bounded tmpfs cache, and `scan source --recursive /src`. TruffleHog uses the pinned action above with literal input `version: 3.97.4@sha256:d366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25`, explicit PR base/head SHAs or push before/after SHAs, and `--fail --no-update`; an all-zero push-before scans the reachable head rather than silently succeeding. It must not print discovered secret values.

`ci-gate` uses `if: always()` and fails unless `changes` and `security` succeeded, every other selected job is `success`, and every unselected job is `skipped`; it must not accept `cancelled` or `failure`. The workflow triggers on every pull request and main push without a top-level paths filter.

- [ ] **Step 7: Rewrite workflow policy tests before deleting the old file**

The policy tests must require exactly `ci.yml` and `llm-deploy.yml`, reject `pull_request_target`, reject any self-hosted runner in `ci.yml`, require full-SHA `uses`, exact top-level permissions, `persist-credentials: false`, full history for the security checkout, explicit non-empty scan-range handling, the two literal scanner version/digest values above, a stable `ci-gate`, and the existing sole `llm-production` deploy job. Mutations replace each scanner digest with a tag-only or different value and must fail. Update runner-group fixtures to copy/mutate `ci.yml`.

- [ ] **Step 8: Run RED against the old workflow, then switch files**

```bash
sh infra/llm/tests/workflows-test.sh
sh infra/llm/tests/workflows-runner-group-test.sh
```

Expected before the switch: failure because `ci.yml` is absent. Add `ci.yml`, remove `llm-ci.yml`, then rerun both plus `actionlint -ignore 'label "llm" is unknown' .github/workflows/ci.yml .github/workflows/llm-deploy.yml`.

- [ ] **Step 9: Run the complete hosted-equivalent gate locally**

```bash
sh scripts/ci/repository-contract-test.sh
source "$HOME/.nvm/nvm.sh"
nvm exec 22.22.2 sh scripts/ci/toolchain-test.sh
nvm exec 22.22.2 sh scripts/ci/dependency-contract-test.sh
nvm exec 22.22.2 npm --prefix frontend test
nvm exec 22.22.2 npm --prefix frontend run typecheck
nvm exec 22.22.2 npm --prefix frontend run build
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home clean test
cd ..
sh infra/llm/tests/ensure-model-test.sh
sh infra/llm/tests/verify-host-test.sh
sh infra/llm/tests/workflows-test.sh
sh infra/llm/tests/workflows-runner-group-test.sh
sh scripts/ci/integration-e2e.sh
git diff --check
```

Expected: every command exits zero; no laptop, cloud account, or real LLM is contacted.

- [ ] **Step 10: Commit unified CI**

```bash
git add .github/workflows/ci.yml .github/workflows/llm-ci.yml scripts/ci/changed-areas.sh scripts/ci/changed-areas-test.sh scripts/ci/dependency-contract-test.sh backend/api/build.gradle.kts backend/engine/build.gradle.kts backend/api/gradle.lockfile backend/engine/gradle.lockfile backend/gradle/verification-metadata.xml infra/llm/tests/workflows-test.sh infra/llm/tests/workflows-runner-group-test.sh
git commit -m "ci: 전체 서비스 hosted 검증 통합"
```

### Task 9: Audit the integrated source milestone

**Files:**
- Verify only; no production code changes expected.

**Interfaces:**
- Consumes: Tasks 1-8.
- Produces: a clean, reviewable milestone ready for the AWS and laptop plans.

- [ ] **Step 1: Run source, secret-marker, and compatibility scans**

```bash
test -z "$(git status --porcelain)"
test -z "$(git ls-files '.context/*')"
test -z "$(rg -n 'pull_request_target|runs-on:.*self-hosted' .github/workflows/ci.yml || true)"
test -z "$(rg -n 'NEXTVISIT_LLM_ENABLED=true' infra/local scripts/ci frontend backend/api/src/main || true)"
git log --oneline --decorate -10
git diff --check 36c007292d7d4d8ec86c4689b5d9b9bc9cacea4b..HEAD
```

Expected: clean tree, no forbidden CI trigger/self-hosted job, and no local LLM enable.

- [ ] **Step 2: Request code review and rerun affected gates after fixes**

Review merge semantics, therapist token lifetime, CORS negative cases, cleanup traps, workflow trust, and both legacy suites. Fix every Critical/Important finding in a focused commit and rerun the exact gate that would have caught it plus the full suite.

- [ ] **Step 3: Record the milestone SHA without pushing or deploying**

```bash
git rev-parse HEAD
git status --short --branch
```

Expected: one local milestone SHA and a clean `codex/full-service-integration`; pushing and external deployment belong to later approved plans.
