#!/bin/sh
# The systemd unit's own ExecStartPre line uses literal ${VAR} syntax that
# systemd (not the shell) expands; the matching require_line call below must
# quote it the same way.
# shellcheck disable=SC2016
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
unit="$repo_root/infra/llm/systemd/nextvisit-llm.service"

[ -f "$unit" ] || {
  printf 'unit file is missing: %s\n' "$unit" >&2
  exit 1
}

require_line() {
  expected=$1
  message=$2
  if ! grep -F -x "$expected" "$unit" >/dev/null; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

# Forbids a pattern outside comment lines (mirrors infra/llm/tests/workflows-test.sh's forbid_ere).
forbid_ere() {
  pattern=$1
  message=$2
  if awk '
    BEGIN { pattern = ARGV[1]; ARGV[1] = "" }
    /^[[:space:]]*#/ { next }
    $0 ~ pattern { found = 1; exit }
    END { exit found ? 0 : 1 }
  ' "$pattern" "$unit"; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

require_count() {
  pattern=$1
  expected=$2
  message=$3
  actual=$(grep -Fc "$pattern" "$unit")
  if [ "$actual" != "$expected" ]; then
    printf '%s (expected %s, found %s)\n' "$message" "$expected" "$actual" >&2
    exit 1
  fi
}

# --- reboot recovery wiring: after docker + network, requires the release ---

require_line 'After=docker.service network-online.target' \
  "unit must start after docker.service and network-online.target"
require_line 'Wants=network-online.target' \
  "unit must want network-online.target"
require_line 'Requires=docker.service' \
  "unit must require docker.service"
require_line 'ConditionPathExists=/opt/nextvisit/llm/current' \
  "unit must require the staged current release to exist"
require_line 'WantedBy=multi-user.target' \
  "unit must be installable for normal boot"

# --- runs as the unprivileged, Docker-root-equivalent deploy account -------

require_line 'User=nextvisit-runner' \
  "unit must run as nextvisit-runner, never root"
require_line 'SupplementaryGroups=docker nextvisit-cloudflared' \
  "unit must use exactly the docker and nextvisit-cloudflared groups bootstrap-ubuntu-host.sh creates"
require_line 'WorkingDirectory=/opt/nextvisit/llm/current' \
  "unit must operate only out of the current release"

# --- token metadata preflight: path only, never a value ---------------------

require_line 'Environment=NEXTVISIT_LLM_TOKEN_FILE=/etc/nextvisit/llm.token' \
  "unit must carry only the non-secret token file path"
require_line 'ExecStartPre=/opt/nextvisit/llm/current/scripts/verify-tunnel-token-file.sh ${NEXTVISIT_LLM_TOKEN_FILE}' \
  "unit must preflight the token file from the staged release before starting anything"
require_count 'ExecStartPre=' 1 "unit must have exactly one ExecStartPre"

# --- exact three-file/project apply order: healthy ollama, then model-init, --
# --- then cloudflared --------------------------------------------------------
#
# Order matters as much as content here -- systemd runs ExecStart= lines in
# file order -- so these compare each of the three lines against its exact
# expected *position*, not merely that all three strings appear somewhere
# (three order-independent greps would still pass with ollama and cloudflared
# swapped).

exec_start_1="$(grep -F 'ExecStart=' "$unit" | sed -n '1p')"
exec_start_2="$(grep -F 'ExecStart=' "$unit" | sed -n '2p')"
exec_start_3="$(grep -F 'ExecStart=' "$unit" | sed -n '3p')"

expected_exec_start_1='ExecStart=/usr/bin/docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach --wait ollama'
expected_exec_start_2='ExecStart=/usr/bin/docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml run --rm model-init'
expected_exec_start_3='ExecStart=/usr/bin/docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml up --detach --wait cloudflared'

if [ "$exec_start_1" != "$expected_exec_start_1" ]; then
  printf 'unit must first bring up healthy ollama under the exact three-file project (line 1 was: %s)\n' \
    "$exec_start_1" >&2
  exit 1
fi
if [ "$exec_start_2" != "$expected_exec_start_2" ]; then
  printf 'unit must run model-init idempotently over the compose network before cloudflared (line 2 was: %s)\n' \
    "$exec_start_2" >&2
  exit 1
fi
if [ "$exec_start_3" != "$expected_exec_start_3" ]; then
  printf 'unit must start cloudflared only after ollama and model-init (line 3 was: %s)\n' \
    "$exec_start_3" >&2
  exit 1
fi
require_count 'ExecStart=' 3 "unit must have exactly three ExecStart lines in this order"

# --- ExecStop preserves the named model volume -------------------------------

require_line 'ExecStop=/usr/bin/docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml down' \
  "unit must stop with the exact three-file down command"
require_count 'ExecStop=' 1 "unit must have exactly one ExecStop"
forbid_ere '(--volumes|(^|[[:space:]])-v([[:space:]]|$))' \
  "unit must never pass --volumes/-v to docker compose down"

# --- bounded restart, not a fight with a live deploy -------------------------

require_line 'Type=oneshot' "unit must be Type=oneshot"
require_line 'RemainAfterExit=yes' "unit must remain active after its ExecStart sequence exits"
require_line 'Restart=on-failure' "unit must use Restart=on-failure, not Restart=always"
require_line 'RestartSec=15' "unit restart backoff must be bound"
require_line 'TimeoutStartSec=600' "unit start sequence must be time-bounded"
forbid_ere '^Restart=always$' "unit must never use Restart=always"
# RestartSec alone never trips systemd's default 5-starts-in-10s limiter at a
# 15s spacing, so a persistently failing unit would retry forever -- driving
# `docker compose up` against the same project every 15 seconds, exactly the
# "fight a live deploy" scenario Restart=always was rejected for. These two
# bound the total retry window instead -- but the window must exceed
# 5 * (TimeoutStartSec + RestartSec) = 5 * (600 + 15) = 3075 seconds, or a
# unit that fails by *hanging* until TimeoutStartSec (not failing fast) would
# never accumulate 5 starts inside the window and the limiter would never
# trip, retrying the hang forever.
require_line 'StartLimitBurst=5' "unit restart attempts must be bounded"
require_line 'StartLimitIntervalSec=3700' "unit restart window must exceed 5 * (TimeoutStartSec + RestartSec) so a hanging start still trips the limiter"

# --- no token value, env file, or root execution anywhere in the unit -------

forbid_ere 'TUNNEL_TOKEN' "unit must never reference the legacy TUNNEL_TOKEN literal"
forbid_ere 'env_file' "unit must never consume an env_file"
forbid_ere '/etc/nextvisit/llm\.env' "unit must never reference the legacy Tunnel environment file path"
forbid_ere '^User=root$' "unit must never run as root"
forbid_ere '^ExecStart(Pre)?=.*sudo' "unit must never elevate with sudo"

# Every line naming the token file must be one of the two audited lines
# above -- both already asserted exact -- so no other command line can smuggle
# a token value, an env file, or anything beyond the bare path.
token_line_count=$(grep -Fc 'NEXTVISIT_LLM_TOKEN_FILE' "$unit")
[ "$token_line_count" -eq 2 ] || {
  printf 'unit must reference NEXTVISIT_LLM_TOKEN_FILE only from its Environment= and ExecStartPre= lines (found %s)\n' \
    "$token_line_count" >&2
  exit 1
}

printf 'systemd unit structural checks passed\n'

# --- systemd-analyze verify, where available ---------------------------------
#
# CI and the real host both have systemd; this sandbox may not. verify also
# resolves every ExecStart*/ExecStop command to an executable file on THIS
# filesystem, which /opt/nextvisit/llm/current never is here (that only
# exists once stage-runtime.sh has actually staged a release) -- so verifying
# the unit unmodified always fails on "is not executable: No such file or
# directory" for the token preflight and for /usr/bin/docker, regardless of
# whether the unit itself is correct. Verify a rewritten copy instead: the
# WorkingDirectory/ExecStartPre prefix points at a throwaway directory that
# actually holds an executable stub at the same scripts/verify-tunnel-token-
# file.sh relative path, and /usr/bin/docker is swapped for another real
# executable already on PATH. Everything else in the unit -- ordering,
# arguments, User=, SupplementaryGroups=, the exact three-file project -- is
# untouched, so a genuinely typo'd ExecStart path is still caught. Real local
# accounts and units (nextvisit-runner, nextvisit-cloudflared, docker.service)
# that only exist once bootstrap-ubuntu-host.sh has actually run are still
# expected to be missing here and are not treated as failures; anything else
# is.
if command -v systemd-analyze >/dev/null 2>&1; then
  verify_stub_dir="$(mktemp -d)"
  verify_unit_dir="$(mktemp -d)"
  cleanup_verify_dirs() {
    rm -rf "$verify_stub_dir" "$verify_unit_dir"
    return 0
  }
  trap cleanup_verify_dirs EXIT HUP INT TERM

  mkdir -p "$verify_stub_dir/scripts"
  cat >"$verify_stub_dir/scripts/verify-tunnel-token-file.sh" <<'STUB'
#!/bin/sh
exit 0
STUB
  chmod +x "$verify_stub_dir/scripts/verify-tunnel-token-file.sh"
  docker_stub="$(command -v true)"

  rewritten_unit="$verify_unit_dir/nextvisit-llm.service"
  sed \
    -e "s#/opt/nextvisit/llm/current#$verify_stub_dir#g" \
    -e "s#/usr/bin/docker#$docker_stub#g" \
    "$unit" >"$rewritten_unit"

  set +e
  verify_output="$(systemd-analyze verify "$rewritten_unit" 2>&1)"
  verify_status=$?
  set -e
  unexpected="$(printf '%s\n' "$verify_output" | grep -Eiv \
    'does not exist|user .*credentials|unknown group|docker\.service|^$' || true)"
  if [ "$verify_status" -ne 0 ] && [ -n "$unexpected" ]; then
    printf 'systemd-analyze verify reported unexpected issues on the rewritten unit:\n%s\n' \
      "$unexpected" >&2
    exit 1
  fi
  printf 'systemd-analyze verify: %s\n' "$verify_output"

  cleanup_verify_dirs
  trap - EXIT HUP INT TERM
else
  printf 'systemd-analyze not available; skipped\n'
fi
