#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
infra_dir="$repo_root/infra/llm"
verify_script="$infra_dir/scripts/verify-tunnel-token-file.sh"
tunnel_compose="$infra_dir/compose.tunnel.yml"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM
mkdir -p "$test_root/bin"

secret_sentinel="do-not-print-this-fake-tunnel-token-sentinel"

# A single fake `stat` reports whatever numeric owner/group/mode the caller
# asks for via environment variables, keyed on whether the queried path's
# basename matches the token file name. This proves the metadata rules
# without needing real root:65532 ownership, which this development machine
# cannot create.
cat >"$test_root/bin/stat" <<'FAKE'
#!/bin/sh
set -eu
case "${1:-}" in
  -c)
    shift 2
    for p in "$@"; do
      case "$p" in
        --) ;;
        *)
          base=$(basename "$p")
          if [ "$base" = "${FAKE_FILE_NAME:-llm.token}" ]; then
            printf '%s %s %s\n' "${FAKE_FILE_UID:-0}" "${FAKE_FILE_GID:-65532}" "${FAKE_FILE_MODE:-440}"
          else
            printf '%s %s %s\n' "${FAKE_PARENT_UID:-0}" "${FAKE_PARENT_GID:-65532}" "${FAKE_PARENT_MODE:-750}"
          fi
          ;;
      esac
    done
    ;;
  *)
    exit 2
    ;;
esac
FAKE
chmod +x "$test_root/bin/stat"

fixture_number=0
new_fixture() {
  # A variable-assignment prefix on a shell FUNCTION call (e.g.
  # `FAKE_PARENT_UID=1000 run_verify ...`) has POSIX-unspecified persistence
  # after the call returns: bash and dash discard it, but this repo's own
  # `sh` (and ksh) can leave it set for the rest of the script, silently
  # corrupting every later fixture. Reset explicitly on every new fixture so
  # each case's rejection reason cannot leak from the previous one.
  unset FAKE_PARENT_UID FAKE_PARENT_GID FAKE_PARENT_MODE \
    FAKE_FILE_UID FAKE_FILE_GID FAKE_FILE_MODE FAKE_FILE_NAME
  fixture_number=$((fixture_number + 1))
  fixture_dir="$test_root/fixture-$fixture_number"
  mkdir -p "$fixture_dir/etc/nextvisit"
  fixture_parent="$fixture_dir/etc/nextvisit"
  fixture_file="$fixture_parent/llm.token"
  printf '%s\n' "$secret_sentinel" >"$fixture_file"
  chmod 750 "$fixture_parent"
  chmod 440 "$fixture_file"
}

run_verify() {
  set +e
  verify_output="$(PATH="$test_root/bin:$PATH" "$verify_script" "$@" 2>&1)"
  verify_status=$?
  set -e
}

expect_pass() {
  name=$1
  if [ "$verify_status" -ne 0 ]; then
    printf '%s: expected verify-tunnel-token-file.sh to pass but it failed: %s\n' "$name" "$verify_output" >&2
    exit 1
  fi
  if [ "$verify_output" != "tunnel token file verified" ]; then
    printf '%s: verify-tunnel-token-file.sh must print exactly the fixed success line\n' "$name" >&2
    exit 1
  fi
}

expect_fail() {
  name=$1
  expected_message=$2
  if [ "$verify_status" -eq 0 ]; then
    printf '%s: expected verify-tunnel-token-file.sh to reject the fixture\n' "$name" >&2
    exit 1
  fi
  case "$verify_output" in
    *"$secret_sentinel"*)
      printf '%s: verify-tunnel-token-file.sh must never print token content\n' "$name" >&2
      exit 1
      ;;
  esac
  # Asserting the exact rejection message (not just "some nonzero exit") is
  # what makes this a test of the *named* rule: a leaked FAKE_* override from
  # a prior case, or a check firing out of order, would otherwise still exit
  # nonzero and print green while proving nothing about this case's rule.
  if [ "$verify_output" != "$expected_message" ]; then
    printf '%s: expected rejection "%s" but got "%s"\n' "$name" "$expected_message" "$verify_output" >&2
    exit 1
  fi
}

# --- Step 1/3: structure and fake-metadata fixture tests -------------------

new_fixture
run_verify "$fixture_file"
expect_pass "valid fixture"

run_verify "relative/path/llm.token"
expect_fail "relative path" "tunnel token file path must be absolute"

new_fixture
run_verify "$fixture_parent/missing.token"
expect_fail "missing file" "tunnel token file must exist"

new_fixture
FAKE_PARENT_UID=1000 run_verify "$fixture_file"
expect_fail "parent owned by a non-root user" "tunnel token file parent must be owned by root"

new_fixture
FAKE_PARENT_GID=100 run_verify "$fixture_file"
expect_fail "parent group other than 65532" "tunnel token file parent group must be numeric 65532"

new_fixture
FAKE_PARENT_MODE=755 run_verify "$fixture_file"
expect_fail "parent mode other than 0750" "tunnel token file parent mode must be 0750"

new_fixture
FAKE_FILE_UID=1000 run_verify "$fixture_file"
expect_fail "file owned by a non-root user" "tunnel token file must be owned by root"

new_fixture
FAKE_FILE_GID=100 run_verify "$fixture_file"
expect_fail "file group other than 65532" "tunnel token file group must be numeric 65532"

new_fixture
FAKE_FILE_MODE=644 run_verify "$fixture_file"
expect_fail "file mode other than 0440" "tunnel token file mode must be 0440"

new_fixture
FAKE_FILE_MODE=400 run_verify "$fixture_file"
expect_fail "file mode 0400 must still be rejected as not exactly 0440" "tunnel token file mode must be 0440"

new_fixture
rm -rf "$fixture_parent"
mkdir "$test_root/real-parent-$fixture_number"
chmod 750 "$test_root/real-parent-$fixture_number"
ln -s "$test_root/real-parent-$fixture_number" "$fixture_parent"
printf '%s\n' "$secret_sentinel" >"$test_root/real-parent-$fixture_number/llm.token"
chmod 440 "$test_root/real-parent-$fixture_number/llm.token"
run_verify "$fixture_parent/llm.token"
expect_fail "symlinked parent directory" "tunnel token file parent must not be a symlink"

new_fixture
target_file="$fixture_parent/real.token"
printf '%s\n' "$secret_sentinel" >"$target_file"
chmod 440 "$target_file"
rm -f "$fixture_file"
ln -s "$target_file" "$fixture_file"
run_verify "$fixture_file"
expect_fail "symlinked token file" "tunnel token file must not be a symlink"

new_fixture
rm -f "$fixture_file"
mkdir "$fixture_file"
run_verify "$fixture_file"
expect_fail "token path is a directory, not a regular file" "tunnel token file must be a regular file"

new_fixture
chmod u+w "$fixture_file"
: >"$fixture_file"
chmod 440 "$fixture_file"
run_verify "$fixture_file"
expect_fail "empty token file" "tunnel token file must not be empty"

new_fixture
chmod u+w "$fixture_file"
printf '%s\n%s\n' "$secret_sentinel" "$secret_sentinel" >"$fixture_file"
chmod 440 "$fixture_file"
run_verify "$fixture_file"
expect_fail "token file with more than one line" "tunnel token file must contain exactly one LF-terminated line"

new_fixture
chmod u+w "$fixture_file"
printf '%s' "$secret_sentinel" >"$fixture_file"
chmod 440 "$fixture_file"
run_verify "$fixture_file"
expect_fail "token file missing its trailing LF" "tunnel token file must contain exactly one LF-terminated line"

new_fixture
chmod u+w "$fixture_file"
printf '%s\r\n' "$secret_sentinel" >"$fixture_file"
chmod 440 "$fixture_file"
run_verify "$fixture_file"
expect_fail "token file containing a CR byte" "tunnel token file must not contain CR or NUL bytes"

new_fixture
chmod u+w "$fixture_file"
printf 'token-with-a-nul\000-byte\n' >"$fixture_file"
chmod 440 "$fixture_file"
run_verify "$fixture_file"
expect_fail "token file containing a NUL byte" "tunnel token file must not contain CR or NUL bytes"

new_fixture
chmod u+w "$fixture_file"
head -c 5000 /dev/zero | tr '\0' 'a' >"$fixture_file"
printf '\n' >>"$fixture_file"
chmod 440 "$fixture_file"
run_verify "$fixture_file"
expect_fail "token file larger than 4096 bytes" "tunnel token file must be at most 4096 bytes"

if grep -Eq '(^|[^[:alnum:]_])set[[:space:]]+(-[A-Za-z]*x[A-Za-z]*|-o[[:space:]]+xtrace)([^[:alnum:]_-]|$)' "$verify_script"; then
  printf '%s\n' "verify-tunnel-token-file.sh must never enable shell xtrace" >&2
  exit 1
fi

printf 'tunnel token file fixtures: %s cases passed\n' "$fixture_number"

# --- Step 1/3/4: compose.tunnel.yml must never regress to the legacy design

assert_compose_tunnel_static() {
  file=$1
  if grep -Eq '(^|[[:space:]])env_file:' "$file"; then
    printf '%s\n' "compose.tunnel.yml must not consume an env_file" >&2
    return 1
  fi
  if grep -q 'TUNNEL_TOKEN' "$file"; then
    printf '%s\n' "compose.tunnel.yml must not reference the legacy TUNNEL_TOKEN literal" >&2
    return 1
  fi
  if grep -Eq '"--token"|--token=' "$file"; then
    printf '%s\n' "compose.tunnel.yml must not pass a bare --token argument" >&2
    return 1
  fi
  if ! grep -Fq -- '--token-file' "$file"; then
    printf '%s\n' "compose.tunnel.yml must pass --token-file" >&2
    return 1
  fi
  if ! grep -Eq '^secrets:' "$file"; then
    printf '%s\n' "compose.tunnel.yml must declare a top-level secrets block" >&2
    return 1
  fi
  if grep -Eq '^\s*-\s*"?127\.0\.0\.1' "$file"; then
    printf '%s\n' "compose.tunnel.yml must not publish a host Ollama port" >&2
    return 1
  fi
}

if ! assert_compose_tunnel_static "$tunnel_compose"; then
  exit 1
fi
printf '%s\n' "compose.tunnel.yml static policy passed"

# --- Step 6: mutation test — a reverted legacy design must be rejected ------

mutation_root="$(mktemp -d)"
trap 'rm -rf "$test_root" "$mutation_root"' EXIT HUP INT TERM

legacy_fixture="$mutation_root/legacy-compose.tunnel.yml"
cat >"$legacy_fixture" <<'LEGACY'
services:
  ollama:
    ports: !reset []

  cloudflared:
    image: cloudflare/cloudflared:2026.8.3@sha256:51c9cefcb4569df44e1ad403ab1d3d8065aa8e84339bcfc6aee75502e1140339
    command: ["tunnel", "--no-autoupdate", "run"]
    env_file:
      - "${NEXTVISIT_LLM_ENV_FILE:?Set NEXTVISIT_LLM_ENV_FILE to /etc/nextvisit/llm.env}"
    depends_on:
      ollama:
        condition: service_healthy
    restart: unless-stopped
LEGACY

if assert_compose_tunnel_static "$legacy_fixture" 2>/dev/null; then
  printf '%s\n' "mutation test failed: the legacy TUNNEL_TOKEN/env_file design must be rejected" >&2
  exit 1
fi

bare_token_fixture="$mutation_root/bare-token-compose.tunnel.yml"
sed 's/"--token-file", "\/run\/secrets\/tunnel_token"/"--token"/' "$tunnel_compose" >"$bare_token_fixture"
if assert_compose_tunnel_static "$bare_token_fixture" 2>/dev/null; then
  printf '%s\n' "mutation test failed: a bare --token argument must be rejected" >&2
  exit 1
fi

token_file_flag_fixture="$mutation_root/token-file-flag-only-compose.tunnel.yml"
sed 's/"--token-file", "\/run\/secrets\/tunnel_token"/"--token-file-typo", "\/run\/secrets\/tunnel_token"/' "$tunnel_compose" >"$token_file_flag_fixture"
if ! assert_compose_tunnel_static "$token_file_flag_fixture" 2>/dev/null; then
  printf '%s\n' "sanity check failed: --token-file-typo unexpectedly matched --token-file" >&2
  exit 1
fi

printf '%s\n' "compose.tunnel.yml mutation tests passed"

# --- Step 5: render all three Compose files with a synthetic raw token -----

render_root="$(mktemp -d)"
trap 'rm -rf "$test_root" "$mutation_root" "$render_root"' EXIT HUP INT TERM

token_file="$render_root/synthetic.token"
render_sentinel="synthetic-fake-render-only-tunnel-token-sentinel"
printf '%s\n' "$render_sentinel" >"$token_file"
chmod 600 "$token_file"

rendered="$render_root/rendered.json"
: >"$rendered"
chmod 600 "$rendered"

(
  cd "$infra_dir"
  NEXTVISIT_LLM_TOKEN_FILE="$token_file" docker compose \
    -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml \
    config --format json
) >"$rendered"

jq -e '.services.ollama.ports == null' "$rendered" >/dev/null

if jq -e --arg sentinel "$render_sentinel" '
  [.services[] |
    ((.environment // {}) | to_entries[]?.value),
    ((.command // [])[]),
    ((.labels // {}) | to_entries[]?.value)
  ] | any(. == $sentinel)
' "$rendered" >/dev/null; then
  printf '%s\n' "rendered Compose configuration must never expose the raw token in Env/Cmd/Labels" >&2
  exit 1
fi

jq -e '.services.cloudflared.command == ["tunnel", "--no-autoupdate", "run", "--token-file", "/run/secrets/tunnel_token"]' "$rendered" >/dev/null
jq -e '.services.cloudflared.secrets | length == 1 and .[0].source == "tunnel_token" and .[0].target == "tunnel_token"' "$rendered" >/dev/null
jq -e '.secrets.tunnel_token.file != null' "$rendered" >/dev/null

printf '%s\n' "compose.tunnel.yml render inspection passed"
printf '%s\n' "tunnel token file policy tests passed"
