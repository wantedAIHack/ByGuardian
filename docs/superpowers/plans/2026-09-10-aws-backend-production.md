# AWS Backend Production Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the Spring API and PostgreSQL on one recoverable Seoul EC2 host, deployed without long-lived AWS or SSH keys and proven healthy with LLM disabled.

**Architecture:** CloudFormation owns the retained data/backup boundary, immutable registry, KMS keys, EC2/SSM path, OIDC trust, and alarms. A digest-pinned Caddy/API/PostgreSQL Compose stack reads host-materialized file secrets; release scripts enforce additive migrations, backup-before-change, atomic release links, and isolated restore drills.

**Tech Stack:** AWS CloudFormation, EC2 Ubuntu 24.04 x86_64, EBS gp3, ECR, S3, KMS, Systems Manager, IAM/OIDC, CloudWatch/SNS/Budgets, Docker Compose v2, Caddy 2, Java 21/Spring Boot 3.5.5, PostgreSQL 16, POSIX shell, ShellCheck, cfn-lint, GitHub Actions.

**Spec:** `docs/superpowers/specs/2026-09-10-full-service-deployment-design.md`

## Global Constraints

- Execute only after `2026-09-10-source-integration-and-hosted-ci.md` is complete and reviewed.
- Tasks 1-7 may run alongside Ubuntu/LLM offline source work, but Task 8's release bundle waits for Ubuntu/LLM Task 4 so the one reviewed Access smoke script is present and allowlisted for EC2 execution.
- Source-only tasks may run without AWS. Do not create a stack, billable resource, DNS record, parameter value, or deployment variable without a fresh itemized change set and explicit user approval.
- Region is `ap-northeast-2`; initial host is Ubuntu 24.04 x86_64 `t3.medium`, encrypted 20 GiB delete-on-termination root plus encrypted 30 GiB retained data EBS mounted at `/var/lib/nextvisit`.
- Every backend Compose invocation uses exact project name `nextvisit-backend`; scripts reject a conflicting `COMPOSE_PROJECT_NAME`.
- Open only TCP 80/443. Do not create an EC2 key pair or port 22 rule; administration and deployment use SSM.
- Require IMDSv2, hop limit 1, no public IPv6, and a boot-persistent `DOCKER-USER` rejection for container traffic to `169.254.169.254`.
- Pin runtime images and third-party actions by immutable digest/SHA before GREEN. Never use `latest`.
- Keep API, DB, and backup data on the separate retained EBS boundary. Abort rather than falling back to root storage.
- DB password is always mandatory. Cloudflare Access ID/secret are absent in the base LLM-disabled stack and become a required pair only in the LLM overlay.
- Never put DB/Access values in Compose environment, image Env/Cmd/Labels, GitHub, SSM command parameters/output, logs, or shell history.
- `NEXTVISIT_DEMO_ENABLED=false` and `NEXTVISIT_LLM_ENABLED=false` remain fixed through this plan's live acceptance.
- Stock Caddy handles TLS/proxy/body-size/security headers. A Spring production filter owns the approved IP limits: `/demo` 5/hour; `/cases` plus `/guardians/recover` 20/hour.
- Request bodies above 1 MiB are rejected at Caddy before reaching Spring.
- Only additive Flyway migrations auto-deploy. Any destructive or ambiguous SQL stops before migration.
- Backup target is measured RPO at most 5 minutes and RTO at most 2 hours: `archive_timeout=180s`, 30-second uploader, daily base backup, 14-day versioned SSE-KMS retention, isolated restore.
- The EC2 instance role can write/encrypt backups but cannot read/decrypt them. A temporary MFA-approved recovery stack owns restore reads.
- The protected `main` CI result and a successful `workflow_run` event are the CI evidence boundary; do not add broad `checks: read` merely to re-query it.
- Preserve the source plan's Gradle locks and strict verification metadata. Any later dependency/build-plugin change must regenerate and review its owning lock/checksum files and pass `scripts/ci/dependency-contract-test.sh`.
- Run `git diff --check` and focused mutation tests before every commit.

---

## File and Interface Map

| Area | File | Responsibility |
| --- | --- | --- |
| Production app | `backend/api/src/main/resources/application-production.yml` | Mandatory configtree DB secret and production-only flags |
| Request limits | `backend/api/src/main/java/nextvisit/api/common/ProductionRateLimitFilter.java` | Fixed-window client-IP admission behind the one trusted Caddy address |
| API image | `backend/api/Dockerfile`, `backend/api/docker/entrypoint.sh`, `backend/.dockerignore` | Reproducible non-root bootJar runtime and secret preflight |
| Image lock | `infra/backend/images.sources`, `infra/backend/images.lock`, `infra/backend/scripts/resolve-image-lock.sh` | Resolve reviewed tags once and commit immutable digests |
| Reverse proxy | `infra/backend/Caddyfile` | TLS, 1 MiB body cap, header replacement, API-only proxy |
| Runtime | `infra/backend/compose.production.yml`, `infra/backend/compose.llm.yml` | Base LLM-off stack and later Access-secret overlay |
| Host safety | `infra/backend/scripts/preflight-host.sh`, `infra/backend/systemd/nextvisit-metadata-block.service` | EBS identity, package/runtime, and metadata rejection |
| Secrets | `infra/backend/scripts/preflight-parameters.sh`, `infra/backend/scripts/materialize-secrets.sh` | Metadata-only Parameter validation and silent file materialization |
| Migration | `infra/backend/scripts/check-additive-migrations.sh`, `infra/backend/scripts/pre-migration-backup.sh` | Immutable migration manifest and dump-before-Flyway gate |
| Backup | `infra/backend/postgres/archive-wal.sh`, `infra/backend/scripts/upload-wal.sh`, `infra/backend/scripts/base-backup.sh` | Atomic WAL spool, verified uploads, daily base backup |
| Release | `infra/backend/scripts/build-release-bundle.sh`, `infra/backend/scripts/deploy-release.sh`, `infra/backend/scripts/rollback-release.sh` | Digest/checksum verified atomic deployment |
| Production canary | `infra/backend/scripts/canary-full-service.sh` | Create an internal synthetic fixture, exercise the public API, and discard bearer material |
| Restore | `infra/backend/scripts/restore-backup.sh`, `infra/aws/backend-restore-drill.yml` | MFA-approved isolated recovery only |
| Monitoring | `infra/backend/scripts/publish-metrics.sh`, `infra/backend/systemd/nextvisit-monitor.*` | Fixed metadata-only health/backup/token-expiry metrics |
| AWS | `infra/aws/account-budget.yml`, `infra/aws/backend-production.yml` | Budget and production resource ownership |
| AWS lint pin | `infra/aws/requirements.txt`, `infra/aws/scripts/cfn-lint.sh` | Run exact `cfn-lint==1.56.1` in an ephemeral environment |
| Deploy CI | `.github/workflows/backend-deploy.yml` | Hosted CI completion to OIDC/ECR/S3/SSM |
| Operations | `docs/operations/full-service-runbook.md` | Exact owner/apply/rollback/recovery commands |

Locked Parameter Store paths:

```text
/nextvisit/prod/db/password
/nextvisit/prod/llm/cf-access-client-id
/nextvisit/prod/llm/cf-access-client-secret
/nextvisit/prod/llm/cf-access-token-expires-at
```

Locked configtree targets:

```text
/run/secrets/spring.datasource.password
/run/secrets/nextvisit.llm.cf-access-client-id
/run/secrets/nextvisit.llm.cf-access-client-secret
```

Locked retained layout:

```text
/var/lib/nextvisit/postgres
/var/lib/nextvisit/caddy/data
/var/lib/nextvisit/caddy/config
/var/lib/nextvisit/backups/wal-spool
/var/lib/nextvisit/backups/base-staging
/var/lib/nextvisit/releases
/var/lib/nextvisit/secrets/api
/var/lib/nextvisit/secrets/postgres
```

### Task 1: Create immutable runtime image and identity locks

**Files:**
- Create: `infra/backend/images.sources`
- Create: `infra/backend/images.lock`
- Create: `infra/backend/scripts/resolve-image-lock.sh`
- Create: `infra/backend/tests/image-lock-test.sh`

**Interfaces:**
- Consumes: reviewed source tags for Temurin 21 JDK/JRE, PostgreSQL 16, and Caddy 2.
- Produces: four shell-readable `NAME=repository:tag@sha256:64hex` entries plus numeric `POSTGRES_UID` and `POSTGRES_GID`, used by Dockerfiles, Compose tests, host ownership checks, and deployment bundles.

- [ ] **Step 1: Write a failing lock contract**

Require exactly `API_BUILD_IMAGE`, `API_RUNTIME_IMAGE`, `POSTGRES_IMAGE`, `CADDY_IMAGE`, `POSTGRES_UID`, and `POSTGRES_GID`; reject duplicate keys, floating refs, uppercase digest text, non-64-hex digests, non-numeric IDs, root ID zero, and a digest that no longer resolves for `linux/amd64`.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/image-lock-test.sh
```

Expected: failure because the lock and resolver do not exist.

- [ ] **Step 3: Implement one-time digest and runtime-identity resolution**

The resolver reads source tags, calls `docker buildx imagetools inspect`, requires a Linux/amd64 manifest, and resolves every tag to its platform digest. After pinning PostgreSQL, it runs only `id -u postgres` and `id -g postgres` in that digest with `--platform linux/amd64 --network none --read-only --cap-drop ALL --security-opt no-new-privileges`; both outputs must be decimal nonzero IDs. It writes a sorted temporary lock before atomically replacing `images.lock` and prints only repository/tag/digest plus the two numeric IDs, never registry credentials. The source file starts with reviewed Temurin 21.0.10 JDK/JRE Jammy tags, PostgreSQL 16 Alpine, and Caddy 2 Alpine; the committed lock captures the exact values resolved during implementation.

- [ ] **Step 4: Verify digest stability twice**

```bash
sh infra/backend/scripts/resolve-image-lock.sh
cp infra/backend/images.lock /tmp/nextvisit-images-lock.first
sh infra/backend/scripts/resolve-image-lock.sh
cmp /tmp/nextvisit-images-lock.first infra/backend/images.lock
sh infra/backend/tests/image-lock-test.sh
```

Expected: byte-identical locks and all platform/ref assertions pass.

- [ ] **Step 5: Commit the supply-chain boundary**

```bash
git add infra/backend/images.sources infra/backend/images.lock infra/backend/scripts/resolve-image-lock.sh infra/backend/tests/image-lock-test.sh
git commit -m "build: backend 운영 이미지 digest 고정"
```

### Task 2: Add production configtree and request-admission rules

**Files:**
- Create: `backend/api/src/main/resources/application-production.yml`
- Create: `backend/api/src/main/java/nextvisit/api/common/ProductionRateLimitFilter.java`
- Create: `backend/api/src/main/java/nextvisit/api/common/RateLimitProperties.java`
- Modify: `backend/api/src/main/java/nextvisit/api/ApiApplication.java`
- Modify: `backend/api/src/main/java/nextvisit/api/demo/DemoSeedWriter.java`
- Create: `backend/api/src/main/java/nextvisit/api/operations/CanaryFixtureRunner.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/QuestionService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/PrepCardService.java`
- Modify: `backend/api/src/main/java/nextvisit/api/questions/PrepCardDto.java`
- Create: `backend/api/src/test/java/nextvisit/api/config/ProductionConfigurationTest.java`
- Create: `backend/api/src/test/java/nextvisit/api/common/ProductionRateLimitFilterTest.java`
- Create: `backend/api/src/test/java/nextvisit/api/operations/CanaryFixtureRunnerTest.java`
- Modify: `backend/api/src/test/java/nextvisit/api/questions/PrepCardControllerTest.java`
- Modify: `backend/api/src/test/java/nextvisit/api/llm/QuestionAsyncIntegrationTest.java`
- Modify: `docs/superpowers/specs/2026-09-05-api-design.md`

**Interfaces:**
- Consumes: exact CORS from source-integration Task 5, `X-Forwarded-For` overwritten by Caddy, and property `nextvisit.proxy.trusted-address=172.30.10.2`.
- Produces: mandatory `configtree:/run/secrets/`, fixed 429 `ApiError` shape, aggregate fixed-hour counters, a default-off non-web synthetic fixture runner, and additive prep-card `generationStatus` observability.

- [ ] **Step 1: Write failing production configuration tests**

Use a temporary configtree to prove the profile reads `spring.datasource.password`, refuses the development fallback, leaves Access properties blank while LLM is false, and requires both Access files when LLM is true. Assert production defaults set demo/LLM false and expose no secret value through an actuator/environment endpoint; do not add that endpoint.

- [ ] **Step 2: Write failing rate-limit tests with a fixed Clock**

Assert request 6 to POST `/demo` from one IP returns 429; request 21 across POST `/cases` and POST `/guardians/recover` returns 429; distinct IPs are isolated; the next UTC hour resets; GET routes are untouched; an untrusted direct peer cannot supply forwarding headers; comma-separated or syntactically invalid forwarded addresses are rejected; and the response contains only the fixed code/message. A barrier-started concurrency test must prove that exactly the admitted count reaches the downstream chain when more than 20 same-key requests race.

- [ ] **Step 3: Write failing one-shot fixture tests**

Prove no runner bean exists by default. With `nextvisit.canary-fixture.enabled=true`, require `spring.main.web-application-type=none`, demo=false, LLM=false, an absolute `/run/nextvisit-canary` output directory, and a newly created mode-0600 manifest. The runner creates exactly one `NEXTVISIT_CANARY_V1` guardian, a backdated six-week STROKE/RIGHT/OFTEN case, baseline free-note marker, deterministic DemoSeed trajectories, and a READY all-template question cache; it creates no therapist link. Reject an existing/symlink/world-readable output, wrong marker, any web mode, either feature true, or a second run into the same destination. Capture logs and assert case ID, guardian token, recovery value, free notes, and manifest body never appear.

Add controller/service tests that require `GET /me/prep-card` to return exactly one additive enum field `generationStatus` matching the cache's `READY`, `LLM_PENDING`, `LLM_DONE`, or `LLM_FAILED` state while retaining every existing field. Assert invalid/null states cannot serialize and that DONE still pairs only with LLM-source questions while FAILED pairs only with the preserved all-template batch in the async integration tests.

- [ ] **Step 4: Run RED**

```bash
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home :api:test --tests nextvisit.api.config.ProductionConfigurationTest --tests nextvisit.api.common.ProductionRateLimitFilterTest --tests nextvisit.api.operations.CanaryFixtureRunnerTest
```

Expected: missing profile/classes.

- [ ] **Step 5: Implement the minimal bounded filter and one-shot runner**

Use `OncePerRequestFilter`, injected `Clock`, immutable route limits, and atomic `ConcurrentHashMap.compute` transitions over `ClientRoute -> Window`, capped at 50,000 live keys. Remove expired entries before admitting a new key; when the cap cannot be reclaimed, fail the limited public endpoints closed with the same 429 rather than growing memory. Read exactly one parsed forwarded client address only when `request.getRemoteAddr()` exactly matches the configured Caddy IP.

Set the profile import and server limits:

```yaml
spring:
  config:
    import: configtree:/run/secrets/
nextvisit:
  demo:
    enabled: false
  llm:
    enabled: false
  rate-limit:
    enabled: true
  proxy:
    trusted-address: 172.30.10.2
```

`CanaryFixtureRunner` is a conditional `ApplicationRunner`, not a controller. Reuse the engine's committed `DemoSeed` through an additive `DemoSeedWriter` overload, but write the canary marker instead of demo prose in the baseline note and use relation `NEXTVISIT_CANARY_V1`. Generate a fresh guardian token, store only its hash in PostgreSQL, and write only `{caseId, guardianToken}` to an atomically created POSIX mode-0600 manifest. Refresh questions while LLM is forced false, so the fixture starts READY/template. `ApiApplication` closes the non-web context after the runner succeeds; an exception exits non-zero. Normal web startup and `/demo` behavior remain unchanged.

Return the current cache status from `QuestionService` together with its body and map it to additive `PrepCardDto.generationStatus`; do not infer status from question text/source. Document the field in the API design. Existing FE clients need no branch and continue ignoring the additive member.

- [ ] **Step 6: Verify and commit**

```bash
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home clean test
cd ..
git add backend/api/src/main/resources/application-production.yml backend/api/src/main/java/nextvisit/api/common/ProductionRateLimitFilter.java backend/api/src/main/java/nextvisit/api/common/RateLimitProperties.java backend/api/src/main/java/nextvisit/api/ApiApplication.java backend/api/src/main/java/nextvisit/api/demo/DemoSeedWriter.java backend/api/src/main/java/nextvisit/api/operations/CanaryFixtureRunner.java backend/api/src/main/java/nextvisit/api/questions/QuestionService.java backend/api/src/main/java/nextvisit/api/questions/PrepCardService.java backend/api/src/main/java/nextvisit/api/questions/PrepCardDto.java backend/api/src/test/java/nextvisit/api/config/ProductionConfigurationTest.java backend/api/src/test/java/nextvisit/api/common/ProductionRateLimitFilterTest.java backend/api/src/test/java/nextvisit/api/operations/CanaryFixtureRunnerTest.java backend/api/src/test/java/nextvisit/api/questions/PrepCardControllerTest.java backend/api/src/test/java/nextvisit/api/llm/QuestionAsyncIntegrationTest.java docs/superpowers/specs/2026-09-05-api-design.md
git commit -m "feat: backend production 설정과 요청 제한 추가"
```

### Task 3: Build a deterministic non-root API image

**Files:**
- Create: `backend/api/Dockerfile`
- Create: `backend/api/docker/entrypoint.sh`
- Create: `backend/.dockerignore`
- Create: `infra/backend/tests/api-image-test.sh`
- Modify: `backend/gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Consumes: Task 1 build/runtime image refs and Gradle modules under `backend/`.
- Produces: `nextvisit-api.jar`, UID/GID `10001:10001`, and an entrypoint that checks only file presence/readability and then `exec`s Java.

- [ ] **Step 1: Write failing Dockerfile/entrypoint tests**

Static fixtures require two stages, exact locked ARG defaults, official wrapper JAR SHA-256 `7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d`, Gradle 8.12 binary distribution SHA-256 `8d97a97984f6cbd2b85fe4c60a743440a347544bf18818048e611f5288d46c94`, `--no-daemon :api:bootJar`, one copied jar, OCI revision label, non-root USER, read-only-compatible `/tmp`, and no secret ARG/ENV. Fake entrypoint cases reject missing/empty DB secret, forbidden secret environment names, one-sided Access files when LLM is true, and lost command arguments needed by the one-shot fixture profile.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/api-image-test.sh
```

Expected: missing files.

- [ ] **Step 3: Implement and build by reviewed commit**

Add the distribution checksum as `distributionSha256Sum` in the wrapper properties, and make the build stage verify the tracked wrapper JAR against the fixed official checksum before invoking it. The entrypoint checks `/run/secrets/spring.datasource.password` always and the two Access files only when `NEXTVISIT_LLM_ENABLED=true`. It never reads a value into a shell variable or prints file metadata. End with:

```sh
exec java -XX:MaxRAMPercentage=75.0 -jar /app/nextvisit-api.jar "$@"
```

Build from the backend context:

```bash
docker build --file backend/api/Dockerfile --build-arg VCS_REF="$(git rev-parse HEAD)" --tag nextvisit-api:test backend
```

- [ ] **Step 4: Inspect only metadata and run a synthetic file-secret boot**

Assert image user, labels, entrypoint, architecture, and absence of sentinel values from `.Config.Env`, `.Config.Cmd`, and `.Config.Labels`. Start it on an isolated Docker network with a disposable PostgreSQL fixture and mode-0440 files; never print the inspect JSON or secret content.

- [ ] **Step 5: Commit**

```bash
git add backend/api/Dockerfile backend/api/docker/entrypoint.sh backend/.dockerignore backend/gradle/wrapper/gradle-wrapper.properties infra/backend/tests/api-image-test.sh
git commit -m "feat: non-root API 운영 이미지 추가"
```

### Task 4: Define the Caddy/API/PostgreSQL production stack

**Files:**
- Create: `infra/backend/Caddyfile`
- Create: `infra/backend/compose.production.yml`
- Create: `infra/backend/compose.llm.yml`
- Create: `infra/backend/compose.canary.yml`
- Create: `infra/backend/postgres/pg_hba.conf`
- Create: `infra/backend/postgres/pg_ident.conf`
- Create: `infra/backend/env.production.example`
- Create: `infra/backend/tests/compose-test.sh`
- Create: `infra/backend/tests/caddy-test.sh`

**Interfaces:**
- Consumes: locked image refs, host secret paths, retained layout, `NEXTVISIT_API_HOST`, `NEXTVISIT_API_IMAGE`, and exact app/LLM origins.
- Produces: base stack with three services, an optional Access-secret LLM overlay, and a separately invoked non-web canary-fixture profile.

- [ ] **Step 1: Write RED structural tests**

Render to a mode-0600 temporary JSON file and assert only Caddy publishes 80/443; API/PostgreSQL publish no host port; fixed `172.30.10.0/24` edge and `172.30.20.0/24` internal DB networks separate tiers; Caddy/API edge addresses are `.2/.3` and PostgreSQL/API DB addresses are `.2/.3`; no privileged/host PID/socket/root mount; resource/PID/log limits; healthchecks; DB-before-API; read-only roots and tmpfs; retained paths; `POSTGRES_PASSWORD_FILE`; and no secret value/environment. The canary overlay adds exactly one profile-gated non-web service on DB address `.4`, publishes no port, has no edge network, forces demo/LLM false, mounts an explicit private output directory at `/run/nextvisit-canary`, and cannot start in the normal production command. Require exact root-owned `pg_hba.conf`/`pg_ident.conf`: API `172.30.20.3/32` and the canary fixture `172.30.20.4/32` reach database/user `nextvisit` through separate exact `scram-sha-256` rules, while OS user `postgres` maps through `peer` to DB role `nextvisit` for local database/replication maintenance; all broader TCP and unmapped local access reject.

- [ ] **Step 2: Write RED Caddy contract tests**

Require automatic HTTPS for `NEXTVISIT_API_HOST`, disabled access logs, 1 MiB request body, overwritten `X-Forwarded-For`/`X-Real-IP`, removed inbound forwarding values, HSTS/nosniff/no-store API headers, and proxy only to `api:8080`.

- [ ] **Step 3: Implement base and LLM overlays**

The base mounts only DB secret copies. PostgreSQL runs with the locked `POSTGRES_UID:POSTGRES_GID`, and host preflight creates its retained data, WAL-spool, and base-staging directories plus DB-secret copy for exactly that identity. PostgreSQL also gets those two backup paths as narrow bind mounts; it does not get AWS credentials or the host root. It starts with `hba_file` and `ident_file` pointed at the two reviewed root-owned configs, so maintenance uses peer mapping inside the container and exact API/canary TCP addresses stay password-protected. The LLM overlay adds Access files at the two configtree targets and changes only `NEXTVISIT_LLM_ENABLED=true`; it must not introduce an API key, token environment, port, or new network. The canary overlay reuses the immutable API image and DB secret, sets `SPRING_MAIN_WEB_APPLICATION_TYPE=none`, `NEXTVISIT_CANARY_FIXTURE_ENABLED=true`, demo=false, and LLM=false, and requires a caller-created absolute output directory; it never mounts Access files. Caddy receives no secret and never routes to the fixture.

- [ ] **Step 4: Verify Compose/Caddy mutations**

```bash
sh infra/backend/tests/compose-test.sh
sh infra/backend/tests/caddy-test.sh
docker run --rm -v "$PWD/infra/backend/Caddyfile:/etc/caddy/Caddyfile:ro" "$(. infra/backend/images.lock; printf '%s' "$CADDY_IMAGE")" caddy validate --config /etc/caddy/Caddyfile
```

Temporarily add `8080:8080` to API and prove the test fails; restore and rerun.

- [ ] **Step 5: Commit**

```bash
git add infra/backend/Caddyfile infra/backend/compose.production.yml infra/backend/compose.llm.yml infra/backend/compose.canary.yml infra/backend/postgres/pg_hba.conf infra/backend/postgres/pg_ident.conf infra/backend/env.production.example infra/backend/tests/compose-test.sh infra/backend/tests/caddy-test.sh
git commit -m "feat: backend production Compose 경계 추가"
```

### Task 5: Fail closed on host, EBS, metadata, and secret drift

**Files:**
- Create: `infra/backend/scripts/preflight-host.sh`
- Create: `infra/backend/scripts/preflight-parameters.sh`
- Create: `infra/backend/scripts/materialize-secrets.sh`
- Create: `infra/backend/systemd/nextvisit-metadata-block.service`
- Create: `infra/backend/tests/host-preflight-test.sh`
- Create: `infra/backend/tests/secrets-test.sh`

**Interfaces:**
- Consumes: expected data volume ID, secret/backup KMS ARNs, and exact Parameter paths from `/etc/nextvisit/backend.conf`.
- Produces: verified directories and duplicated file secrets: API DB `root:10001 0440`, PostgreSQL DB `root:${POSTGRES_GID} 0440` from the committed image lock, optional Access pair `root:10001 0440`.

- [ ] **Step 1: Build fake `findmnt`, `lsblk`, `aws`, `stat`, `install`, and `iptables` tests**

Reject root-device reuse, wrong EBS volume, missing mount, wrong parameter type/path/KMS key, empty secure input, loose file mode, wrong numeric owner/group, legacy secret env, absent metadata REJECT, and any sentinel on stdout/stderr.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/host-preflight-test.sh
sh infra/backend/tests/secrets-test.sh
```

- [ ] **Step 3: Implement metadata-only preflight and silent materialization**

`preflight-parameters.sh` calls `aws ssm describe-parameters` with an exact name filter and validates name/type/key ID without `--with-decryption`. `materialize-secrets.sh` is root-only, creates a mode-0400 temporary file under the destination filesystem with `umask 077`, and runs one checked `aws ssm get-parameter --with-decryption --query Parameter.Value --output text` whose stdout is redirected directly to that file and whose stderr is suppressed behind a fixed diagnostic. This avoids a POSIX pipeline-status fail-open while keeping the value out of argv, environment, command substitution, terminal output, and logs. It checks non-empty size, changes the numeric group/mode, fsyncs, and atomically renames on the same filesystem.

The systemd unit installs an idempotent rule equivalent to:

```text
iptables -C DOCKER-USER -d 169.254.169.254/32 -j REJECT || iptables -I DOCKER-USER 1 -d 169.254.169.254/32 -j REJECT
```

It runs after Docker and before the backend service.

- [ ] **Step 4: Verify tests plus exact-scope mutations**

Run both tests. Temporarily broaden the SSM path filter to `/nextvisit/prod/`; prove the secret test rejects it, restore, rerun.

- [ ] **Step 5: Commit**

```bash
git add infra/backend/scripts/preflight-host.sh infra/backend/scripts/preflight-parameters.sh infra/backend/scripts/materialize-secrets.sh infra/backend/systemd/nextvisit-metadata-block.service infra/backend/tests/host-preflight-test.sh infra/backend/tests/secrets-test.sh
git commit -m "feat: EC2 데이터와 secret preflight 추가"
```

### Task 6: Gate Flyway with immutable additive manifests and a remote dump

**Files:**
- Create: `infra/backend/scripts/create-migration-manifest.sh`
- Create: `infra/backend/scripts/check-additive-migrations.sh`
- Create: `infra/backend/scripts/pre-migration-backup.sh`
- Create: `infra/backend/tests/migration-gate-test.sh`

**Interfaces:**
- Consumes: sorted PostgreSQL migration files in the current and previous release.
- Produces: `migrations.sha256`, result `NO_CHANGE|ADDITIVE|BLOCKED`, and a verified pre-migration dump key before `ADDITIVE` deployment.

- [ ] **Step 1: Write mutation fixtures**

Reject removed/modified historical migrations, duplicate or out-of-order versions, `DROP`, `TRUNCATE`, `DELETE`, `UPDATE`, `RENAME`, destructive `ALTER`, non-null column additions without safe default, and statements outside the approved grammar. Accept `CREATE TABLE`, `CREATE INDEX`, and nullable `ALTER TABLE ADD COLUMN` used by current migrations.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/migration-gate-test.sh
```

- [ ] **Step 3: Implement the manifest/parser and dump gate**

Normalize neither SQL nor checksums: historical files must be byte-identical. Parse only enough to recognize the finite automatic grammar; comments cannot hide forbidden verbs. If result is `ADDITIVE`, execute `pg_dump --format=custom` inside the running PostgreSQL container as OS user `postgres`, using the same peer map to DB role `nextvisit`, and write only to the narrow backup staging mount. SHA-256 the completed file, upload it with S3 checksum enabled, compare the returned remote checksum, then write the manifest record. Any failure occurs before Compose starts the candidate API, and no database password enters argv or environment.

- [ ] **Step 4: Run GREEN and commit**

```bash
sh infra/backend/tests/migration-gate-test.sh
git add infra/backend/scripts/create-migration-manifest.sh infra/backend/scripts/check-additive-migrations.sh infra/backend/scripts/pre-migration-backup.sh infra/backend/tests/migration-gate-test.sh
git commit -m "feat: additive Flyway 배포 gate 추가"
```

### Task 7: Archive WAL atomically and create verified base backups

**Files:**
- Modify: `infra/backend/compose.production.yml`
- Create: `infra/backend/postgres/archive-wal.sh`
- Create: `infra/backend/scripts/upload-wal.sh`
- Create: `infra/backend/scripts/base-backup.sh`
- Create: `infra/backend/systemd/nextvisit-wal-upload.service`
- Create: `infra/backend/systemd/nextvisit-wal-upload.timer`
- Create: `infra/backend/systemd/nextvisit-base-backup.service`
- Create: `infra/backend/systemd/nextvisit-base-backup.timer`
- Create: `infra/backend/tests/backup-test.sh`

**Interfaces:**
- Consumes: PostgreSQL `%p/%f`, final spool names, backup bucket/prefix/KMS key, host instance role.
- Produces: atomic final WAL files, S3 checksum-verified objects, daily compressed base backup plus manifest.

- [ ] **Step 1: Write fake filesystem/AWS failure tests**

Prove `.partial` is never uploaded, final names appear only after copy+file fsync+rename+directory fsync, a local WAL is never removed before a matching `PutObject` checksum response, retry leaves the file, and base backup staging is cleaned only after verified upload.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/backup-test.sh
```

- [ ] **Step 3: Implement the bounded pipeline**

Set PostgreSQL `archive_mode=on`, `archive_timeout=180s`, and the script as `archive_command`. The systemd timer uses `OnUnitActiveSec=30s`, `Persistent=true`, randomized delay at most 5 seconds, and a single-instance lock. Upload with S3 object checksum and SSE-KMS; compare the service response before local deletion. The root-owned base-backup service invokes `pg_basebackup` inside the exact running PostgreSQL container as OS user `postgres`, maps through peer authentication to DB role `nextvisit`, and writes into the narrow EBS staging bind mount. It uses PostgreSQL's SHA-256 backup manifest, runs `pg_verifybackup`, includes required WAL, archives deterministically, checksums, uploads, verifies, then cleans. No database password enters argv or environment.

- [ ] **Step 4: Mutation and GREEN**

Temporarily move local deletion before checksum comparison; prove RED, restore, then run ShellCheck, syntax, and the test twice.

- [ ] **Step 5: Commit**

```bash
git add infra/backend/compose.production.yml infra/backend/postgres/archive-wal.sh infra/backend/scripts/upload-wal.sh infra/backend/scripts/base-backup.sh infra/backend/systemd/nextvisit-wal-upload.service infra/backend/systemd/nextvisit-wal-upload.timer infra/backend/systemd/nextvisit-base-backup.service infra/backend/systemd/nextvisit-base-backup.timer infra/backend/tests/backup-test.sh
git commit -m "feat: PostgreSQL WAL과 base backup 추가"
```

### Task 8: Stage, deploy, and safely roll back immutable releases

**Files:**
- Create: `infra/backend/scripts/build-release-bundle.sh`
- Create: `infra/backend/scripts/deploy-release.sh`
- Create: `infra/backend/scripts/rollback-release.sh`
- Create: `infra/backend/scripts/smoke-health.sh`
- Create: `infra/backend/scripts/canary-full-service.sh`
- Create: `infra/backend/systemd/nextvisit-backend.service`
- Create: `infra/backend/tests/deploy-rollback-test.sh`
- Create: `infra/backend/tests/canary-full-service-test.sh`

**Interfaces:**
- Consumes: `ReleaseId`, `ImageRef`, `BundleKey`, `BundleSha256`, `MigrationManifestSha256`, and canary interface `canary-full-service.sh MODE APP_BASE_URL API_BASE_URL` where mode is exactly `template-ready|llm-success|template-fallback`.
- Produces: `/var/lib/nextvisit/releases/FULL_COMMIT_SHA`, atomic `current`/`previous` symlinks, content-free health/Access results, and an EC2-local canary that destroys all temporary bearer material. The secret-free bundle includes `infra/llm/scripts/smoke-access.sh`, the canary overlay/script, and no other laptop-only artifact.

- [ ] **Step 1: Write failure-path fixtures**

Reject non-40-hex release IDs, tag-only/mutable image refs, bad bundle or migration checksum, wrong EBS, missing metadata rule, secret preflight failure, failed pre-migration backup, mismatched pulled RepoDigest, health failure, and rollback across an unsafe migration. Verify a concurrent deploy loses a nonblocking `flock` and changes nothing. Canary fixtures reject invalid mode/URL, non-current release, wrong compose profile, published fixture port, missing or loose manifest, wrong marker, malformed/additive-type-invalid API data, absent all-template baseline, skipped PENDING in success mode, wrong terminal state/source, timeout, and any body/token/free-note sentinel on either output stream.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/deploy-rollback-test.sh
sh infra/backend/tests/canary-full-service-test.sh
```

- [ ] **Step 3: Implement exact deployment order**

Under one lock: validate parameters; verify host/metadata/secrets; download the bundle from the fixed S3 prefix; verify checksums and the fixed allowlist (including only the EC2-run Access smoke from `infra/llm` plus the reviewed canary profile/script); compare migrations and finish any dump gate; stage a new release directory; pull by digest and verify RepoDigest; move `current` to `previous`; atomically link `current`; run `docker compose up --detach --wait`; then check internal and external `/health` for exact `status=ok` and `db=up` without printing bodies. Safe failures restore the previous link/digest. Unsafe migration states stop write traffic and require recovery.

The canary requires root/SSM execution from the exact current release. It creates a mode-0700 temporary output directory under `/run` owned by the locked API identity `10001:10001`, then starts the named fixture with project `nextvisit-backend`, the production+canary files, the explicit canary profile, and detached `up --no-deps canary-fixture`. Do not use `compose run`: the named service must retain fixed DB source address `.4` required by `pg_hba.conf`. Resolve exactly one container through its project/service labels, bound `docker wait`, and require process exit code zero without reading its logs. This avoids `--abort-on-container-exit`, which could stop the already-running production services. Validate the mode-0600 `{caseId,guardianToken}` manifest without printing it and call the public API with responses held in separate root-only files. `template-ready` proves the prebuilt cache is non-empty, reports `generationStatus=READY`, and is entirely `source=TEMPLATE`. The two LLM modes overwrite the valid current week 6 through the normal API, require a visible `generationStatus=LLM_PENDING`, then require respectively `LLM_DONE` with all `source=LLM` or `LLM_FAILED` with all `source=TEMPLATE`; mixed/partial results fail. An EXIT/HUP/INT/TERM trap removes only that fixture service/container, manifest, guardian token, recovery/body files, and exact temporary directory; it never runs project `down`, deletes a database row, or touches a volume. Output is limited to fixed phases/latencies and the non-secret marked case ID for later lifecycle handling.

- [ ] **Step 4: Run GREEN twice and commit**

```bash
sh infra/backend/tests/deploy-rollback-test.sh
sh infra/backend/tests/deploy-rollback-test.sh
sh infra/backend/tests/canary-full-service-test.sh
sh infra/backend/tests/canary-full-service-test.sh
git add infra/backend/scripts/build-release-bundle.sh infra/backend/scripts/deploy-release.sh infra/backend/scripts/rollback-release.sh infra/backend/scripts/smoke-health.sh infra/backend/scripts/canary-full-service.sh infra/backend/systemd/nextvisit-backend.service infra/backend/tests/deploy-rollback-test.sh infra/backend/tests/canary-full-service-test.sh
git commit -m "feat: backend release 배포와 rollback 추가"
```

### Task 9: Declare the retained AWS production boundary

**Files:**
- Create: `infra/aws/requirements.txt`
- Create: `infra/aws/scripts/cfn-lint.sh`
- Create: `infra/aws/account-budget.yml`
- Create: `infra/aws/backend-production.yml`
- Create: `infra/aws/parameters/backend-production.example.json`
- Create: `infra/aws/tests/backend-production-template-test.sh`
- Create: `infra/aws/tests/backend-production-template-mutation-test.sh`

**Interfaces:**
- Consumes: owner-approved account ID, domain, GitHub Organization/repository, monthly USD budget, SNS email, explicit current Ubuntu 24.04 amd64 AMI, exact available Docker/Compose/containerd support-package versions, and an exact AWS CLI v2 version with its detached signature.
- Produces: stack outputs listed below and no SecureString values.

Required outputs:

```text
VpcId PublicSubnetId BackendSecurityGroupId InstanceId InstanceRoleArn
InstanceProfileName DataVolumeId ElasticIp ElasticIpAllocationId
ApiEcrRepositoryUri ApiEcrRepositoryArn ReleaseBucketName BackupBucketName
ReleasePrefix WalBackupPrefix BaseBackupPrefix PreMigrationBackupPrefix
SecretKmsKeyArn BackupKmsKeyArn GithubOidcProviderArn
GithubBackendDeployRoleArn RestoreOperatorRoleArn DeployDocumentName
DeployDocumentArn DbPasswordParameterName LlmAccessClientIdParameterName
LlmAccessClientSecretParameterName LlmAccessTokenExpiryParameterName
AlertTopicArn ApplicationLogGroupName
BootstrapContractSha256
```

- [ ] **Step 1: Write RED cfn-lint and policy assertions**

Set `infra/aws/requirements.txt` to the single exact direct dependency `cfn-lint==1.56.1`. The POSIX wrapper creates an `mktemp -d` virtual environment, installs only that file, asserts the exact version, invokes the linter with all received paths, preserves its exit code, and removes only its resolved temporary directory in a trap. Require Seoul, exact AMI/package/version parameters, t3.medium, encrypted volumes/delete flags, retained data EBS/bucket/KMS keys, VPC/EIP, only 80/443, no key pair/22/IPv6, IMDSv2/hop 1, immutable ECR with 20 retained tagged releases and 7-day untagged cleanup, versioned SSE-KMS backup bucket with 14-day lifecycle, separate KMS policies, exact IAM resources, no SecureString resource, custom SSM document, and 80%/100% budgets. Extract the EC2 UserData scalar to a temporary file and ShellCheck it as bash.

- [ ] **Step 2: Run RED**

```bash
sh infra/aws/scripts/cfn-lint.sh infra/aws/account-budget.yml infra/aws/backend-production.yml
sh infra/aws/tests/backend-production-template-test.sh
```

- [ ] **Step 3: Implement least-privilege resources and anchored SSM parameters**

The SSM Document accepts only the five interface fields, anchors each allowed pattern, targets the one stack instance and requires tags `Project=nextvisit`, `Environment=production`. Its first-run path downloads the secret-free bundle from the fixed release prefix, verifies the supplied SHA-256 before extraction, and invokes only the allowlisted release installer. The instance role excludes backup object read and KMS decrypt for the backup key. Data EBS, backup bucket, and both KMS keys use `DeletionPolicy: Retain` and `UpdateReplacePolicy: Retain`.

The inline UserData is a fail-closed, noninteractive bootstrap—not an application deploy. Because the retained `AWS::EC2::VolumeAttachment` can complete only after the instance resource exists, UserData waits a bounded 180 seconds for the exact `/dev/disk/by-id` volume and does not use an EC2 `CreationPolicy` that would deadlock that dependency. It validates the EBS volume ID, formats ext4 only when the expected block device has no filesystem signature, mounts its UUID at `/var/lib/nextvisit`, writes a persistent mount, and aborts on root-disk or foreign-filesystem drift. It installs only the exact parameterized Docker Engine/CLI/containerd/Compose and support-package versions from Docker's signed Ubuntu repository. It downloads the exact versioned AWS CLI v2 archive and detached signature, verifies signer fingerprint `FB5DB77FD5C118B80511ADA8A6310ACC4672475C` against the root-owned AWS CLI public key embedded from the official documentation, and only then installs it. It enables Docker and the existing Canonical SSM agent, installs the boot-persistent metadata-reject unit, and creates no application secret. Only after every check succeeds does it atomically replace root-owned mode-0444 `/var/lib/nextvisit/.bootstrap-ok` with the current IMDS instance ID and the source-reviewed UserData SHA-256; a retained marker from a prior instance cannot satisfy preflight. Stack completion is not readiness: the live runbook waits through SSM for a marker matching both current instance and template output digest, then reruns host preflight before accepting the stack.

- [ ] **Step 4: Mutation-test dangerous expansions**

Individually add port 22, make IMDS optional, broaden S3/SSM to `*`, grant backup decrypt, remove Retain, allow a mutable image parameter, remove the UserData bounded EBS-identity/blank-device guard, replace an exact package/version with `latest`, skip the AWS signature/fingerprint check, add the bootstrap marker before final checks, or add a deadlocking EC2 `CreationPolicy`. Each mutation must fail with a fixed diagnostic; restore after every case.

- [ ] **Step 5: Commit**

```bash
git add infra/aws/requirements.txt infra/aws/scripts/cfn-lint.sh infra/aws/account-budget.yml infra/aws/backend-production.yml infra/aws/parameters/backend-production.example.json infra/aws/tests/backend-production-template-test.sh infra/aws/tests/backend-production-template-mutation-test.sh
git commit -m "feat: AWS backend production CloudFormation 추가"
```

### Task 10: Deploy from successful hosted CI through OIDC and SSM

**Files:**
- Create: `.github/workflows/oidc-claim-probe.yml`
- Create: `.github/workflows/backend-deploy.yml`
- Create: `infra/backend/tests/backend-workflow-test.sh`
- Create: `infra/backend/tests/backend-workflow-mutation-test.sh`
- Modify: `.github/workflows/ci.yml`
- Modify: `infra/llm/tests/workflows-test.sh`

**Interfaces:**
- Consumes: completed hosted workflow `CI`, full head SHA, CloudFormation role/bucket/repository/document outputs.
- Produces: immutable ECR image, secret-free S3 release, and one SSM invocation; no AWS key or SSH credential.

- [ ] **Step 1: Write workflow policy tests**

Require `workflow_run` from successful `CI` on main plus guarded manual dispatch, hosted runners only, `BACKEND_DEPLOY_ENABLED == 'true'`, exact main head, non-cancelling production concurrency, job-level `contents: read` and `id-token: write`, immutable actions, checkout exact release SHA with credentials disabled, digest deployment, secret-free bundle, and fixed SSM parameters. Reject PR triggers, `checks: write`, static AWS keys, SSH, broad role/session, tag-only images, body/env/inspect output, or GitHub secrets forwarded to SSM.

- [ ] **Step 2: Run RED**

```bash
sh infra/backend/tests/backend-workflow-test.sh
sh infra/backend/tests/backend-workflow-mutation-test.sh
```

- [ ] **Step 3: Add a temporary manual OIDC claim probe**

The probe has only `contents: read` and `id-token: write`, is manual-only, and is admitted only for exact `refs/heads/main` while repository variable `OIDC_CLAIM_PROBE_ENABLED == 'true'`. It obtains a GitHub OIDC token with audience `sts.amazonaws.com` through Node's HTTPS API, validates JWT structure locally, and prints only decoded `sub`, `aud`, repository owner ID, and repository ID. It never prints the token or payload wholesale. Source work commits this disabled probe so its exact code is reviewed; the control-plane plan runs it once after Organization transfer, records the non-secret claims, sets the variable false, and deletes the workflow in a separate reviewed commit before any backend deployment admission is enabled.

- [ ] **Step 4: Implement deploy with these immutable action SHAs**

```yaml
actions/checkout@11d5960a326750d5838078e36cf38b85af677262
docker/setup-buildx-action@8d2750c68a42422c14e847fe6c8ac0403b4cbd6f
docker/build-push-action@10e90e3645eae34f1e60eeb005ba3a3d33f178e8
aws-actions/configure-aws-credentials@61815dcd50bd041e203e49132bacad1fd04d2708
```

Protected main plus a successful `workflow_run` whose source event is `push`, head branch is `main`, head repository is the canonical private repository, and head SHA still equals remote main is the CI-result proof; the deploy workflow does not query Checks. Manual dispatch reruns the local hosted-equivalent verification job before AWS credentials are requested. The hosted CI job installs only `infra/aws/requirements.txt` in its ephemeral environment and asserts `cfn-lint 1.56.1` before template validation.

- [ ] **Step 5: Run policy/actionlint mutations and commit**

```bash
sh infra/backend/tests/backend-workflow-test.sh
sh infra/backend/tests/backend-workflow-mutation-test.sh
sh infra/llm/tests/workflows-test.sh
actionlint -ignore 'label "llm" is unknown' .github/workflows/*.yml
git add .github/workflows/oidc-claim-probe.yml .github/workflows/backend-deploy.yml .github/workflows/ci.yml infra/backend/tests/backend-workflow-test.sh infra/backend/tests/backend-workflow-mutation-test.sh infra/llm/tests/workflows-test.sh
git commit -m "ci: AWS OIDC backend 배포 추가"
```

Do not enable or run the probe during source work. Its one approved run and subsequent deletion are explicit control-plane steps; backend deployment remains disabled until the deletion commit passes `ci-gate`.

### Task 11: Add isolated restore and metadata-only monitoring

**Files:**
- Create: `infra/aws/backend-restore-drill.yml`
- Modify: `infra/aws/backend-production.yml`
- Create: `infra/backend/scripts/restore-backup.sh`
- Create: `infra/backend/scripts/publish-metrics.sh`
- Create: `infra/backend/systemd/nextvisit-monitor.service`
- Create: `infra/backend/systemd/nextvisit-monitor.timer`
- Create: `infra/backend/tests/restore-test.sh`
- Create: `infra/backend/tests/monitoring-test.sh`

**Interfaces:**
- Consumes: an MFA-assumed `nextvisit-restore` operator role, selected base/WAL keys, cutoff time, and expiry String parameter.
- Produces: temporary tagged `t3.medium` restore stack and metrics `DiskUsedPercent`, `ExternalHealth`, `WalArchiveAgeSeconds`, `WalSpoolBacklogFiles`, `BaseBackupAgeSeconds`, `AccessTokenDaysRemaining`.

- [ ] **Step 1: Write RED restore/metric fixtures**

Reject production data paths, production instance profile, wildcard backup read, absent MFA condition, unchecked object checksum, cleanup before evidence capture, metric dimensions containing tokens/paths, health response output, and missing-data-success alarms.

- [ ] **Step 2: Implement the temporary recovery boundary**

The restore operator can create/delete only `Project=nextvisit, Purpose=restore-drill` stacks. The temporary instance role reads/decrypts only selected backup prefixes. Restore to a new encrypted volume and database, verify schema plus synthetic case/prep-card queries, record measured RPO/RTO, and require a separate cleanup approval. Never mount or modify production EBS.

- [ ] **Step 3: Implement fixed CloudWatch metrics and alarms**

Alarm at disk 80%, external health below 1 with missing data breaching, WAL age above 300 seconds, persistent spool backlog, base backup age above 90,000 seconds, and Access token days at or below 7. Keep the log group at seven days but do not ship application/access logs until redaction is separately approved.

- [ ] **Step 4: Verify and commit**

```bash
sh infra/backend/tests/restore-test.sh
sh infra/backend/tests/monitoring-test.sh
sh infra/aws/scripts/cfn-lint.sh infra/aws/backend-restore-drill.yml infra/aws/backend-production.yml
git add infra/aws/backend-restore-drill.yml infra/aws/backend-production.yml infra/backend/scripts/restore-backup.sh infra/backend/scripts/publish-metrics.sh infra/backend/systemd/nextvisit-monitor.service infra/backend/systemd/nextvisit-monitor.timer infra/backend/tests/restore-test.sh infra/backend/tests/monitoring-test.sh
git commit -m "feat: backend 복원 훈련과 운영 경보 추가"
```

### Task 12: Write the exact runbook and prove all source artifacts

**Files:**
- Create: `docs/operations/full-service-runbook.md`
- Modify: `scripts/ci/repository-contract-test.sh`
- Modify: `README.md`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: Tasks 1-11 and all CloudFormation outputs.
- Produces: fail-closed commands for preview/change-set/apply/deploy/rollback/backup/restore, without embedded account/domain/secret values.

- [ ] **Step 1: Make the repository contract require the runbook**

Replace the earlier transitional behavior with `test -s "$repo_root/docs/operations/full-service-runbook.md"`, run the test, and observe RED.

- [ ] **Step 2: Write self-contained command blocks**

Every block starts `set -eu`, validates required environment variables with `${NAME:?message}`, sets region explicitly, uses stack outputs rather than copied IDs, previews a change set before execution, and never places SecureString values in argv/history. Include normal rollback admission order: set `BACKEND_DEPLOY_ENABLED=false`, cancel/recheck queued/running jobs, keep LLM false, then call the exact previous-release rollback. Incorporate the already-tested `infra/llm/scripts/smoke-access.sh` as the sole EC2 Access check and document its three file-path arguments without copying credential values.

- [ ] **Step 3: Run all source acceptance**

```bash
cd backend
./gradlew --no-daemon -Dorg.gradle.java.home=/Users/youngmin/Library/Java/JavaVirtualMachines/temurin-21.0.10/Contents/Home clean test :api:bootJar
cd ..
find infra/backend infra/aws -type f -name '*.sh' -exec shellcheck {} +
sh infra/backend/tests/image-lock-test.sh
sh infra/backend/tests/api-image-test.sh
sh infra/backend/tests/compose-test.sh
sh infra/backend/tests/caddy-test.sh
sh infra/backend/tests/host-preflight-test.sh
sh infra/backend/tests/secrets-test.sh
sh infra/backend/tests/migration-gate-test.sh
sh infra/backend/tests/backup-test.sh
sh infra/backend/tests/deploy-rollback-test.sh
sh infra/backend/tests/canary-full-service-test.sh
sh infra/backend/tests/restore-test.sh
sh infra/backend/tests/monitoring-test.sh
sh infra/backend/tests/backend-workflow-test.sh
sh infra/backend/tests/backend-workflow-mutation-test.sh
sh infra/aws/scripts/cfn-lint.sh infra/aws/*.yml
sh scripts/ci/repository-contract-test.sh
git diff --check
```

- [ ] **Step 4: Commit docs**

```bash
git add docs/operations/full-service-runbook.md scripts/ci/repository-contract-test.sh README.md backend/README.md
git commit -m "docs: AWS backend 운영 절차 추가"
```

### Task 13: Apply only after a new owner approval and prove LLM-off production

**Files:**
- External state plus secret-free evidence appended to `docs/operations/full-service-runbook.md`.

**Interfaces:**
- Consumes: Organization/repository, observed OIDC claim, base domain, AWS account ID, region approval, budget USD amount, alert email, explicit AMI ID, cost estimate, and approved change sets.
- Produces: HTTPS API/DB, verified backups/restore/rollback, and `NEXTVISIT_LLM_ENABLED=false`.

- [ ] **Step 1: Create budget first**

Validate the authenticated account ID and region, render the budget stack, show the user its monthly amount/email and 80%/100% notices, obtain approval, apply, and require notification confirmation before production resources.

- [ ] **Step 2: Prepare but do not execute the production change set**

Resolve the current Canonical Ubuntu 24.04 amd64 AMI and exact currently available Docker/Compose/containerd/support packages plus an exact AWS CLI v2 version/signature, validate their official owners and signed sources, render parameters from protected variables, run cfn-lint/validate-template, create a named change set, and present resources, bootstrap versions, IAM capabilities, replacements, retained resources, and current monthly estimate. Stop for explicit approval.

- [ ] **Step 3: Apply and verify host controls before secrets**

After approval, execute the exact change set, then wait a bounded 15 minutes for SSM and `/var/lib/nextvisit/.bootstrap-ok`; stack `CREATE_COMPLETE` alone is insufficient. Require Session Manager, expected EBS mount, rerun host preflight, IMDSv2, host identity, metadata rejection from a disposable unprivileged container, and zero port 22/API/DB exposure.

- [ ] **Step 4: Have the owner enter only the DB SecureString**

CloudFormation creates KMS/path/IAM outputs, not the value. The owner uses AWS's protected input or the audited non-logging runbook path. Preflight validates presence/type/key/path without outputting the value. Do not create Access parameters yet.

- [ ] **Step 5: Deploy and exercise LLM-off behavior**

Enable `BACKEND_DEPLOY_ENABLED` only for the reviewed main release, deploy once, then set it false again during acceptance. From an SSM session, run the exact current release's `canary-full-service.sh template-ready APP_BASE_URL API_BASE_URL`; require the internal marked fixture, non-empty all-template prep card, discarded bearer files, and a recorded non-secret case ID while the public `/demo` stays disabled. Also verify external HTTPS health, exact CORS, DB persistence across redeploy, API response while the LLM URL is unreachable, no host API/DB/canary port, backup remote checksum, isolated restore under 2 hours, and safe image rollback.

- [ ] **Step 6: Measure backup RPO under writes**

Insert a synthetic marker transaction, force a WAL switch, observe the finalized WAL upload without bodies/secrets, restore through the selected cutoff in the temporary stack, and calculate marker-to-durable-object time. Require at most 300 seconds and no spool backlog before acceptance.

- [ ] **Step 7: Record identifiers, dates, and fixed outcomes only**

Append stack names, output resource names, release SHA/digest, test timestamps, measured RPO/RTO, and PASS/FAIL. Do not record IP-linked user data, parameter values, response bodies, tokens, or credentials. Keep LLM and demo false for the next plans.
