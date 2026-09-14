#!/bin/sh
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM
mkdir -p "$test_root/bin"

cat >"$test_root/bin/docker" <<'FAKE'
#!/bin/sh
set -eu
runtimes="${FAKE_DOCKER_RUNTIMES:-}"
if [ -z "$runtimes" ]; then runtimes='{"nvidia":{},"runc":{}}'; fi
case "$*" in
  info) exit 0 ;;
  "compose version --short") printf '%s\n' '2.24.4' ;;
  "info --format {{json .Runtimes}}") printf '%s\n' "$runtimes" ;;
  *) exit 2 ;;
esac
FAKE

cat >"$test_root/bin/curl" <<'FAKE'
#!/bin/sh
exit 0
FAKE

cat >"$test_root/bin/jq" <<'FAKE'
#!/bin/sh
exit 0
FAKE

cat >"$test_root/bin/df" <<'FAKE'
#!/bin/sh
printf '%s\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on'
printf '/dev/fake 100 1 %s 1%% /fake\n' "${FAKE_AVAILABLE_KIB:-unknown}"
FAKE

chmod +x "$test_root/bin/docker" "$test_root/bin/curl" \
  "$test_root/bin/jq" "$test_root/bin/df"

set +e
output="$(
  PATH="$test_root/bin:$PATH" \
    NEXTVISIT_LLM_DATA_PATH=. \
    "$script_dir/../scripts/verify-host.sh" cpu 2>&1
)"
status=$?
set -e

if [ "$status" -eq 0 ]; then
  printf '%s\n' "verify-host accepted non-numeric disk availability" >&2
  exit 1
fi
test "$output" = "unable to determine available disk space"

output="$(
  PATH="$test_root/bin:$PATH" \
    NEXTVISIT_LLM_DATA_PATH=. \
    FAKE_AVAILABLE_KIB=20971520 \
    "$script_dir/../scripts/verify-host.sh" cpu
)"
test "$output" = "host verification passed for cpu mode"

printf '%s\n' "verify-host cpu tests passed"

# --- Step 5: gpu mode -------------------------------------------------------
#
# gpu mode now checks everything bootstrap-ubuntu-host.sh established, so the
# fakes here stand in for the bootstrapped host: a working driver, a Docker
# nvidia runtime, a locked service account, a runner-owned release directory,
# masked sleep targets, and no public 11434 listener.

cat >"$test_root/bin/nvidia-smi" <<'FAKE'
#!/bin/sh
exit 0
FAKE

cat >"$test_root/bin/nvidia-ctk" <<'FAKE'
#!/bin/sh
exit 0
FAKE

cat >"$test_root/bin/passwd" <<'FAKE'
#!/bin/sh
set -eu
test "${1:-}" = -S
if [ -n "${FAKE_PASSWD_MISSING:-}" ]; then
  printf 'passwd: user %s does not exist\n' "$2" >&2
  exit 1
fi
printf '%s %s 01/01/2026 0 99999 7 -1\n' "$2" "${FAKE_PASSWD_STATE:-L}"
FAKE

cat >"$test_root/bin/stat" <<'FAKE'
#!/bin/sh
set -eu
test "${1:-}" = -c
path=$4
if [ "$path" = "${FAKE_RELEASES_PATH:-}" ]; then
  printf '%s %s\n' "${FAKE_RELEASES_OWNER:-nextvisit-runner}" "${FAKE_RELEASES_MODE:-750}"
elif [ "$path" = "${FAKE_LLM_DIR_PATH:-}" ]; then
  printf '%s %s\n' "${FAKE_LLM_DIR_OWNER:-nextvisit-runner}" "${FAKE_LLM_DIR_MODE:-750}"
else
  printf 'fake stat: unexpected target %s\n' "$path" >&2
  exit 2
fi
FAKE

cat >"$test_root/bin/systemctl" <<'FAKE'
#!/bin/sh
set -eu
test "${1:-}" = is-enabled
for unit in ${FAKE_MASKED_TARGETS:-sleep.target suspend.target hibernate.target hybrid-sleep.target}; do
  if [ "$unit" = "$2" ]; then printf 'masked\n'; exit 1; fi
done
printf 'static\n'
FAKE

mkdir -p "$test_root/ssbin"
cat >"$test_root/ssbin/ss" <<'FAKE'
#!/bin/sh
set -eu
if [ -n "${FAKE_SS_FAIL:-}" ]; then
  exit 3
fi
printf '%s\n' "${FAKE_LISTENERS:-LISTEN 0 4096 127.0.0.1:11434 0.0.0.0:*}"
FAKE

chmod +x "$test_root/bin/nvidia-smi" "$test_root/bin/nvidia-ctk" \
  "$test_root/bin/passwd" "$test_root/bin/stat" "$test_root/bin/systemctl" \
  "$test_root/ssbin/ss"

# The real utilities verify-host.sh shells out to that this fixture does NOT
# fake. They live in their own directory so the "missing ss" case below can
# build a PATH with no real system bin on it at all: dropping $test_root/ssbin
# only hides `ss` if the real /usr/bin is not still reachable behind it. That
# is exactly how this fixture passed on macOS (which ships no `ss`) while
# silently asserting nothing on Ubuntu (which does).
mkdir -p "$test_root/sysbin"
for real_tool in awk sed grep uname dirname; do
  real_path="$(command -v "$real_tool")" ||
    { printf '%s\n' "verify-host-test requires $real_tool on PATH" >&2; exit 1; }
  ln -sf "$real_path" "$test_root/sysbin/$real_tool"
done

# Nested the way the real host lays it out (llm_dir is releases_dir's parent)
# so verify-host.sh's own `dirname -- "$releases_path"` lands on a directory
# this fixture controls independently of releases_dir.
llm_dir="$test_root/opt/nextvisit/llm"
releases_dir="$llm_dir/releases"
mkdir -p "$releases_dir"

gpu_path="$test_root/bin:$test_root/ssbin:$PATH"

run_gpu() {
  set +e
  gpu_output="$(
    PATH="${GPU_PATH_OVERRIDE:-$gpu_path}" \
      NEXTVISIT_LLM_DATA_PATH=. \
      FAKE_AVAILABLE_KIB=20971520 \
      NEXTVISIT_LLM_RELEASES_PATH="$releases_dir" \
      FAKE_RELEASES_PATH="$releases_dir" \
      FAKE_LLM_DIR_PATH="$llm_dir" \
      "$script_dir/../scripts/verify-host.sh" gpu 2>&1
  )"
  gpu_status=$?
  set -e
  # POSIX leaves the persistence of a variable-assignment prefix on a shell
  # FUNCTION call unspecified, and this repo's `sh` can keep it set for the rest
  # of the script. Clearing the knobs here is what stops one case's fixture from
  # leaking into the next and making it pass for an unrelated reason.
  unset GPU_PATH_OVERRIDE FAKE_DOCKER_RUNTIMES FAKE_PASSWD_STATE \
    FAKE_PASSWD_MISSING FAKE_RELEASES_OWNER FAKE_RELEASES_MODE \
    FAKE_LLM_DIR_OWNER FAKE_LLM_DIR_MODE \
    FAKE_MASKED_TARGETS FAKE_LISTENERS FAKE_SS_FAIL
}

expect_gpu_pass() {
  if [ "$gpu_status" -ne 0 ]; then
    printf '%s: expected gpu mode to pass but it failed: %s\n' "$1" "$gpu_output" >&2
    exit 1
  fi
  if [ "$gpu_output" != "host verification passed for gpu mode" ]; then
    printf '%s: gpu mode must print exactly the fixed success line, got "%s"\n' \
      "$1" "$gpu_output" >&2
    exit 1
  fi
}

# Asserting the exact rejection message keeps a case from going green because
# some unrelated check failed first.
expect_gpu_fail() {
  if [ "$gpu_status" -eq 0 ]; then
    printf '%s: expected gpu mode to reject the host\n' "$1" >&2
    exit 1
  fi
  if [ "$gpu_output" != "$2" ]; then
    printf '%s: expected rejection "%s" but got "%s"\n' "$1" "$2" "$gpu_output" >&2
    exit 1
  fi
}

# A fully bootstrapped host passes, and no tunnel token file exists anywhere in
# this fixture: gpu mode must not require one before the token-install phase.
run_gpu
expect_gpu_pass "bootstrapped host"

GPU_PATH_OVERRIDE="$test_root/bin:$test_root/sysbin" run_gpu
expect_gpu_fail "missing ss" "ss is required for gpu mode"

FAKE_DOCKER_RUNTIMES='{"runc":{}}' run_gpu
expect_gpu_fail "missing Docker nvidia runtime" "Docker nvidia runtime is not configured"

FAKE_PASSWD_STATE=P run_gpu
expect_gpu_fail "unlocked service account" "nextvisit-runner must have no usable password"

FAKE_PASSWD_MISSING=1 run_gpu
expect_gpu_fail "unreadable account state" \
  "unable to read the nextvisit-runner password state; create the account first, then run gpu mode as nextvisit-runner or root"

FAKE_LLM_DIR_OWNER=root run_gpu
expect_gpu_fail "llm directory owned by root" \
  "$llm_dir must be owned by nextvisit-runner with mode 0750"

FAKE_LLM_DIR_MODE=755 run_gpu
expect_gpu_fail "world-readable llm directory" \
  "$llm_dir must be owned by nextvisit-runner with mode 0750"

FAKE_RELEASES_OWNER=root run_gpu
expect_gpu_fail "release directory owned by root" \
  "$releases_dir must be owned by nextvisit-runner with mode 0750"

FAKE_RELEASES_MODE=755 run_gpu
expect_gpu_fail "world-readable release directory" \
  "$releases_dir must be owned by nextvisit-runner with mode 0750"

NEXTVISIT_LLM_RELEASES_PATH_SAVED="$releases_dir"
releases_dir="$test_root/missing-llm-dir/releases"
run_gpu
expect_gpu_fail "missing llm directory" "$test_root/missing-llm-dir must be a directory"
releases_dir="$NEXTVISIT_LLM_RELEASES_PATH_SAVED"

NEXTVISIT_LLM_RELEASES_PATH_SAVED="$releases_dir"
releases_dir="$llm_dir/missing-releases"
run_gpu
expect_gpu_fail "missing release directory" "$releases_dir must be a directory"
releases_dir="$NEXTVISIT_LLM_RELEASES_PATH_SAVED"

FAKE_MASKED_TARGETS="suspend.target hibernate.target hybrid-sleep.target" run_gpu
expect_gpu_fail "unmasked sleep target" \
  "sleep.target must be masked on this dedicated server host"

FAKE_MASKED_TARGETS="sleep.target suspend.target hibernate.target" run_gpu
expect_gpu_fail "unmasked hybrid-sleep target" \
  "hybrid-sleep.target must be masked on this dedicated server host"

FAKE_LISTENERS="LISTEN 0 4096 0.0.0.0:11434 0.0.0.0:*" run_gpu
expect_gpu_fail "public Ollama listener" \
  "port 11434 must not be published outside loopback"

FAKE_LISTENERS="LISTEN 0 4096 [::1]:11434 [::]:*" run_gpu
expect_gpu_pass "IPv6 loopback listener"

FAKE_LISTENERS="LISTEN 0 4096 0.0.0.0:22 0.0.0.0:*" run_gpu
expect_gpu_pass "unrelated public listener"

# IMPORTANT 4: an `ss` that errors (netlink blocked, a hardened container, a
# broken binary) must fail closed rather than be silently read as "nothing is
# listening" -- every other check in this file already fails closed.
FAKE_SS_FAIL=1 run_gpu
expect_gpu_fail "ss enumeration failure" "unable to enumerate listening sockets"

# gpu mode must stay usable before the owner installs the Tunnel token.
if grep -Fq 'llm.token' "$script_dir/../scripts/verify-host.sh"; then
  printf '%s\n' "verify-host.sh must not require the tunnel token" >&2
  exit 1
fi

printf '%s\n' "verify-host tests passed"
