# NextVisit LLM operations

**2026-09-17 live state.** The real deployment is a single Ubuntu laptop
(SSH alias `llm`) running the API, PostgreSQL, Ollama, and cloudflared
together; see
[`docs/superpowers/specs/2026-09-17-single-host-deployment-design.md`](../../docs/superpowers/specs/2026-09-17-single-host-deployment-design.md).
Under that design, cloudflared tunnels the **API** (`api.byguardian.site`),
not Ollama, and the API reaches Ollama over the internal Docker bridge
network (`nextvisit-llm_default`) — never over the internet. As a
consequence, the Cloudflare Access / public-`llm.<domain>`-hostname material
in this file (sections below) and the GitHub organization runner group in
`OWNER_CHECKLIST.md` describe a plan that was never built and is now
obsolete for that reason; `OWNER_CHECKLIST.md`'s "현재 상태" section marks
each obsolete item explicitly. The command reference below is kept for its
still-accurate parts (Compose usage, immutable releases, macOS development)
and as a historical record where superseded.

The required image is Ollama `0.33.3`; Docker Compose v2.24.4 or later is
required. The default model is `qwen3:4b-q4_K_M`. The Compose default context
is **8,192** tokens (`compose.yml:11`, `"${OLLAMA_CONTEXT_LENGTH:-8192}"`;
raised from 2,048 by `OWNER_CHECKLIST.md`'s 2026-09-15 entry) because the model
needs about 2,500 tokens of thinking budget before it can answer.

**Live value, observed 2026-09-16 (host clock).** Until 16:40 UTC the running
`nextvisit-llm-ollama-1` container had `OLLAMA_CONTEXT_LENGTH=8192`. At
16:49:35 UTC the container was recreated — by what or whom is not established —
and came up with `OLLAMA_CONTEXT_LENGTH=2048`, which is **not** the Compose
default and breaks the question-rewrite response schema; see
[`docs/qa/2026-09-17-llm-activation.md`](../../docs/qa/2026-09-17-llm-activation.md)
§3. Check the live value with `docker inspect` before trusting either number.

The named volume `nextvisit-llm-ollama-data` survives container replacement.

The repository owner's post-merge setup, hardware acceptance, activation, and
rollback checklist is [`OWNER_CHECKLIST.md`](./OWNER_CHECKLIST.md). Complete it
in order; this runbook remains the command reference.

## macOS CPU development

```bash
cd infra/llm
cp .env.example .env
docker compose -f compose.yml up --detach --wait --build ollama
docker compose -f compose.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose -f compose.yml down
```

`model-init` downloads about 4.4 GB only when the configured tag is missing.
Omit that command when checking container health without downloading the model.
Never use `docker compose down --volumes` for routine deployment.

## Ubuntu laptop preparation — deferred and organization-gated

1. Install Ubuntu Server 24.04 x86_64 and the NVIDIA driver recommended for the
   laptop, then confirm `nvidia-smi` works. The bootstrap below never installs,
   upgrades, or otherwise touches a driver package; it refuses to run unless the
   driver already works.
2. Run the two-phase host bootstrap on AC power. `prepare` installs nothing: it
   writes only the official signed Docker and NVIDIA repository key and list
   files, then records the resolved candidate versions and those files'
   SHA-256 fingerprints in the non-secret, root-owned, mode-`0444` lock
   `/etc/nextvisit/ubuntu-packages.lock`.

   ```bash
   sudo infra/llm/scripts/bootstrap-ubuntu-host.sh prepare
   ```

   Review the printed `package name=version` lines. Only when they are the
   versions you intend to run, pass the printed lock SHA-256 back to `apply`:

   ```bash
   sudo infra/llm/scripts/bootstrap-ubuntu-host.sh apply <ubuntu-packages.lock sha256>
   ```

   `apply` refuses a missing or mismatched SHA-256 before touching any package,
   re-resolves the candidates to reject drift since `prepare`, and then installs
   exactly those `package=version` arguments. It never runs a general
   distribution upgrade. It also creates the system group `nextvisit-cloudflared`
   with numeric GID `65532`, the locked `nextvisit-runner` account (no usable
   password, home `/home/nextvisit-runner`, shell `/bin/bash`, groups
   `docker,nextvisit-cloudflared`), the runner-owned `/opt/nextvisit/llm` and
   `/opt/nextvisit/llm/releases` (both mode `0750` — `nextvisit-runner` needs
   to write the parent directly to atomically replace `current`, not only its
   contents), and `/etc/nextvisit` as `root:65532` mode `0750`. It configures
   Docker's NVIDIA runtime, writes the
   logind drop-in `/etc/systemd/logind.conf.d/10-nextvisit-llm.conf`, and masks
   the sleep, suspend, hibernate, and hybrid-sleep targets. Both phases are
   idempotent; re-running either leaves host state unchanged.

   `apply` never creates or overwrites `/etc/nextvisit/llm.token` — step 5
   installs it, and an existing token file survives `apply` byte for byte.
   Record the printed `installed name=version` lines in `OWNER_CHECKLIST.md`
   section 2. Reboot yourself afterwards; the script never reboots.

   `nextvisit-cloudflared` (GID `65532`) lets the unattended deploy job read
   `/etc/nextvisit/llm.token` without `sudo`; it must match the numeric group
   the token file and its parent directory are created with below.

3. Do **not** register this laptop as a self-hosted runner for the current
   personal-account repository `y-minion/wanted_Hackaton`. Its remote owner is
   a GitHub User, so repository-level runner allocation would happen before an
   in-repository workflow linter can protect it. Keep deployment disabled here.
   For manual checks only, use a read-only private-repository clone owned by
   `nextvisit-runner`, then run:

   ```bash
   cd infra/llm
   ./scripts/verify-host.sh gpu
   ```

   GPU mode also verifies the service account's locked password, that
   `/opt/nextvisit/llm` and its `releases/` are both runner-owned, the masked
   sleep targets, and the absence of a public `11434` listener. `nextvisit-runner`
   can read its own password
   state without `sudo`, so this still runs unprivileged as that account —
   which is what actually proves the account this repo's deploy workflow runs
   as can reach the Docker socket. It deliberately does not require the Tunnel
   token, so it can pass before step 5.

4. In Cloudflare Zero Trust, create a remotely managed Tunnel route whose
   service is `http://ollama:11434`. Protect its public hostname with an Access
   service-token policy.
5. Store only the raw Tunnel token locally, as a file Compose mounts as a
   secret rather than as an environment variable:

   ```bash
   sudo install -d -o root -g 65532 -m 0750 /etc/nextvisit
   sudo install -o root -g 65532 -m 0440 /dev/null /etc/nextvisit/llm.token
   sudoedit /etc/nextvisit/llm.token
   sudo infra/llm/scripts/verify-tunnel-token-file.sh /etc/nextvisit/llm.token
   ```

   The file holds exactly one line: the raw token value issued by Cloudflare,
   terminated by a single LF, with no `TUNNEL_TOKEN=` prefix. GID `65532` is
   the cloudflared container's non-root runtime user; the host-side
   `nextvisit-cloudflared` group above reuses that same number so
   `nextvisit-runner` can read it. Do not store Access credentials in this
   file. The verify script prints only `tunnel token file verified` and
   never the token itself.
6. Protect `main` in the repository settings by requiring pull-request review.
   Confirm `LLM CI` succeeds before merging changes covered by its path filters.
   Do not make this path-filtered workflow an unconditional required check:
   GitHub leaves skipped required workflows pending. Refactor it to always run
   before making its checks globally required.
7. Before any runner registration, transfer or mirror the private repository to
   a GitHub organization that supports organization runner groups and restricted
   workflows. In that organization, configure the canonical group
   `llm-production` with repository access limited to exactly `<ORG>/<REPO>`,
   `restricted_to_workflows=true`, and selected workflow exactly
   `<ORG>/<REPO>/.github/workflows/llm-deploy.yml@refs/heads/main`. Only then
   use GitHub's generated group-runner setup commands as `nextvisit-runner`,
   retain the `self-hosted`, `linux`, and `llm` labels, and install/start the
   service with `sudo ./svc.sh install nextvisit-runner` followed by
   `sudo ./svc.sh start`.

   The repository workflow linter is defense in depth only; runner allocation
   precedes that job. If the organization cannot enforce the exact restriction,
   leave deployment disabled. A separate pull-based CD design needs explicit
   approval rather than falling back to a repository-level runner.
8. After the organization restriction is in place, create the GitHub
   environment `llm-laptop`. Keep required reviewers on it if the account plan
   supports them.
9. Leave `LLM_DEPLOY_ENABLED` absent or `false` until sections 1–6 of
   `OWNER_CHECKLIST.md` pass, including the organization runner-group boundary,
   Ubuntu hardware acceptance, and reboot recovery. Set it to `true` only when
   automatic `main` deployments should begin.

## Manual production-equivalent start

```bash
cd infra/llm
export NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml up --detach --wait --build ollama
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init
./scripts/smoke-openai.sh http://127.0.0.1:11434/v1
docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach --wait ollama cloudflared
ollama_id="$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps -q ollama)"
test -n "$ollama_id"
test "$(docker inspect -f '{{len .HostConfig.PortBindings}}' "$ollama_id")" = 0
```

The first start exposes only loopback for the local smoke. The final command
recreates Ollama with no published host port and connects it to `cloudflared`.

## Immutable releases and reboot recovery

`infra/llm/scripts/stage-runtime.sh FULL_COMMIT_SHA CHECKOUT_ROOT` copies only
`Dockerfile`, the three Compose files, and the three runtime scripts the
Compose stack actually reads at build or run time (`scripts/wait-for-ollama.sh`,
`scripts/ensure-model.sh`, `scripts/verify-tunnel-token-file.sh`) into
`/opt/nextvisit/llm/releases/<FULL_COMMIT_SHA>` — never the checkout tree
itself, so a `.git` directory, `.env` file, key, token, or other credential
that happens to live in the checkout can never reach a release. It refuses a
non-lowercase-hex SHA, a checkout whose HEAD does not match it, a symlinked
source path, a missing or world-writable source file, and a pre-existing
release directory whose actual contents no longer match what would be
staged — both a changed byte in one of the seven allowlisted files and a
wholly different file added to the release directory (catching both a dirty
checkout and direct tampering with an already-staged release). Staging the
same commit again is a no-op reuse. Releases are never
deleted, so every prior SHA stays available for rollback: re-running
`stage-runtime.sh` with an earlier commit's SHA and a checkout pinned to that
commit stages nothing new and flips `current` back to it. `current` is
replaced with a fresh symlink to the release directory via an atomic rename,
never a copy.

`infra/llm/systemd/nextvisit-llm.service` recovers that exact stack after a
reboot: it starts after `docker.service` and `network-online.target`,
requires `/opt/nextvisit/llm/current` to exist, and runs entirely as
`nextvisit-runner` (`SupplementaryGroups=docker nextvisit-cloudflared`,
`WorkingDirectory=/opt/nextvisit/llm/current`). Its only token-related setting
is the non-secret path `NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token`,
which its `ExecStartPre` passes straight to `verify-tunnel-token-file.sh`
before anything else runs. It then applies the exact
`compose.yml -f compose.gpu.yml -f compose.tunnel.yml` project, brings up
healthy `ollama`, runs `model-init` (idempotent — it only pulls when the
model is missing), and starts `cloudflared` last. `ExecStop` always uses the
same three-file `down` without `--volumes`, so the named model volume
survives a stop. `Restart=on-failure` with a bounded `TimeoutStartSec` is
deliberate: `Restart=always` would fight a deploy that drives the same
Compose project directly. Starting successfully needs three things to exist
first, not just `ConditionPathExists`'s `/opt/nextvisit/llm/current`: the
Tunnel token file `ExecStartPre` verifies, the built `ollama` image, and the
already-pulled model — this unit never builds or downloads anything itself.
`OWNER_CHECKLIST.md` section 2 stages a release and installs the unit
(`/etc/systemd/system/nextvisit-llm.service`, `systemctl daemon-reload`,
`systemctl enable`) but deliberately does not start it yet, because none of
those three preconditions exist at that point in the checklist. Section 3
creates the token file; section 4 builds the image, pulls the model, and
verifies Tunnel/Access end to end — only at the end of section 4, once all
three preconditions hold, does the checklist run `systemctl start
nextvisit-llm.service` and confirm `systemctl is-active
nextvisit-llm.service` reports `active`, since the deploy workflow hard-gates
on that.

`bootstrap-ubuntu-host.sh` owns `/opt/nextvisit/llm` itself to
`nextvisit-runner:nextvisit-runner 0750` (not only `releases/` under it), so
this unprivileged account can create and atomically replace `current` without
`sudo` — it already fully controls `releases/` and is in the `docker` group,
which is root-equivalent for this host, so this grants no new capability.
`verify-host.sh gpu` asserts that ownership alongside its existing `releases/`
check. `llm-deploy.yml`'s `deploy` job calls
`stage-runtime.sh "$GITHUB_SHA" "$GITHUB_WORKSPACE"` right after verifying the
host and the token file, then runs every Compose command with
`working-directory: /opt/nextvisit/llm/current` instead of the checkout. The
loopback smoke script isn't staged (it's a CI verification aid, not something
the running stack reads), so that step invokes it by its checkout path,
`"$GITHUB_WORKSPACE/infra/llm/scripts/smoke-openai.sh"`, while everything else
in that step still runs from `current`. This wiring is intentionally minimal;
the job's broader hardening (trust-boundary mutations, concurrency, the
no-port/no-token assertions) is separate, later work.

## Deferred hardware acceptance

After the final start, run `nvidia-smi`, then
`docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml exec ollama ollama ps`.
Record `100% GPU`, response time with the 2K context, behavior after a reboot,
runner service status, Tunnel reachability through Access, and volume reuse.
These results cannot be claimed from macOS and remain open until the laptop is ready.

## Cloudflare Access smoke (EC2, not the laptop)

`infra/llm/scripts/smoke-access.sh BASE_URL CLIENT_ID_FILE CLIENT_SECRET_FILE`
proves the Cloudflare Access boundary end to end without ever letting a
credential or a model response body reach stdout, stderr, a log, or a process
argument list. The two credential arguments are file paths, never values: it
requires an HTTPS base URL and, for each credential file, a regular
non-symlink file with mode `0440`, `0400`, or `0600`, a single header-safe
ASCII line, and a bounded size — a newline embedded in the value (which could
forge an extra HTTP header) is rejected the same way any other unsafe
character is. The two `CF-Access-Client-Id`/`CF-Access-Client-Secret` headers
are written into a mode-`0600` curl config file that is the only thing passed
on curl's argv; response bodies land in private temporary files; `jq` always
runs with both output streams suppressed (a parse error would otherwise echo
the offending fragment); every temporary file is removed by a trap on every
exit path, including signals. Connect timeout is 5 seconds, total timeout 45
seconds. It exits zero only when the authenticated request returns HTTP 200
with an OpenAI-compatible envelope whose decoded assistant content matches
`smoke-openai.sh`'s exact one-question key set (additive outer envelope
fields, like an OpenAI-style `id`/`object`, remain accepted — only the
decoded question object's key set is closed) and the unauthenticated request
is refused with HTTP 302, 401, or 403; any other pairing, a timeout, or an
invalid envelope fails with a fixed diagnostic that never interpolates a
response, a header, or a credential. This is why the AWS release bundle
allowlists this script for SSM to run on EC2 beside the Access configtree
files — it is the sole authorised caller's own acceptance check, and it is
never executed on the laptop for external authentication.

## Secrets and logs

Never print the Tunnel token, Access service-token values, Authorization
headers, prompts, provider response bodies, generated sentences, or guardian
data. The smoke scripts report only fixed status text.
