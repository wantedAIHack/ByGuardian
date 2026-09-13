#!/bin/sh
set -eu

# Fake-curl harness for infra/llm/scripts/smoke-access.sh.
#
# The real script talks to a Cloudflare Access-protected Ollama origin. Here
# `curl` is a fake executable on a prepended PATH that inspects its own
# argv/config for a credential sentinel (never printing it, only refusing
# with a distinct exit code if it finds it in argv or missing from the config
# file it is supposed to carry it in), and returns canned statuses/bodies
# driven entirely by FAKE_* environment knobs. `jq` is the real system jq --
# no fake needed, since the script's own envelope contract is exercised with
# real (and deliberately malformed) JSON fixtures, exactly like
# ensure-model-test.sh does for smoke-openai.sh.

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
smoke="$repo_root/infra/llm/scripts/smoke-access.sh"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM
mkdir -p "$test_root/bin"

credential_sentinel="do-not-print-this-fake-access-credential-sentinel"
body_sentinel="PRIVATE-RESPONSE-BODY-SENTINEL"

good_body='{"choices":[{"message":{"content":"{\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\"}]}"}}]}'

# --- fake curl ---------------------------------------------------------
#
# Authenticated calls pass `--config FILE`; unauthenticated calls do not.
# FAKE_AUTH_EXIT/FAKE_AUTH_STATUS/FAKE_AUTH_BODY drive the authenticated
# leg, FAKE_UNAUTH_EXIT/FAKE_UNAUTH_STATUS the unauthenticated leg.
cat >"$test_root/bin/curl" <<'FAKE'
#!/bin/sh
set -eu
config=""
output=""
prev=""
for arg in "$@"; do
  case "$prev" in
    --config) config="$arg" ;;
    --output) output="$arg" ;;
  esac
  if [ -n "${FAKE_CREDENTIAL_SENTINEL:-}" ]; then
    case "$arg" in
      *"$FAKE_CREDENTIAL_SENTINEL"*)
        printf 'argv-credential-leak\n' >&2
        exit 97
        ;;
    esac
  fi
  prev="$arg"
done

if [ -n "$config" ]; then
  if [ -n "${FAKE_CREDENTIAL_SENTINEL:-}" ] && ! grep -Fq -- "$FAKE_CREDENTIAL_SENTINEL" "$config"; then
    printf 'config-missing-credential\n' >&2
    exit 96
  fi
  exit_code="${FAKE_AUTH_EXIT:-0}"
  if [ "$exit_code" -ne 0 ]; then
    exit "$exit_code"
  fi
  : "${output:?missing --output}"
  printf '%s' "${FAKE_AUTH_BODY:-}" >"$output"
  printf '%s' "${FAKE_AUTH_STATUS:-200}"
else
  exit_code="${FAKE_UNAUTH_EXIT:-0}"
  if [ "$exit_code" -ne 0 ]; then
    exit "$exit_code"
  fi
  printf '%s' "${FAKE_UNAUTH_STATUS:-403}"
fi
FAKE
chmod +x "$test_root/bin/curl"

# --- fixture helpers -----------------------------------------------------

fixture_number=0
new_fixture() {
  # A variable-assignment prefix on a shell FUNCTION call has POSIX-unspecified
  # persistence after the call returns under this repo's own `sh`; reset every
  # knob explicitly so one case's behavior can never leak into the next.
  unset FAKE_CREDENTIAL_SENTINEL FAKE_AUTH_EXIT FAKE_AUTH_STATUS FAKE_AUTH_BODY \
    FAKE_UNAUTH_EXIT FAKE_UNAUTH_STATUS
  fixture_number=$((fixture_number + 1))
  fixture_dir="$test_root/fixture-$fixture_number"
  mkdir -p "$fixture_dir"
  id_file="$fixture_dir/client-id"
  secret_file="$fixture_dir/client-secret"
  printf '%s' "id-$credential_sentinel" >"$id_file"
  printf '%s' "secret-$credential_sentinel" >"$secret_file"
  chmod 440 "$id_file"
  chmod 600 "$secret_file"
  base_url="https://llm.example.invalid/v1"
  FAKE_CREDENTIAL_SENTINEL="$credential_sentinel"
  export FAKE_CREDENTIAL_SENTINEL
}

run_smoke() {
  set +e
  smoke_output="$(
    PATH="$test_root/bin:$PATH" \
      "$smoke" "$@" 2>&1
  )"
  smoke_status=$?
  set -e
}

assert_no_leak() {
  name=$1
  case "$smoke_output" in
    *"$credential_sentinel"*)
      printf '%s: smoke-access leaked the credential sentinel\n' "$name" >&2
      exit 1
      ;;
  esac
  case "$smoke_output" in
    *"$body_sentinel"*)
      printf '%s: smoke-access leaked the response body sentinel\n' "$name" >&2
      exit 1
      ;;
  esac
  if [ "$smoke_status" -eq 97 ]; then
    printf '%s: credential value reached curl argv\n' "$name" >&2
    exit 1
  fi
  if [ "$smoke_status" -eq 96 ]; then
    printf '%s: credential value never reached the curl config file\n' "$name" >&2
    exit 1
  fi
}

expect_pass() {
  name=$1
  expected_message=$2
  if [ "$smoke_status" -ne 0 ]; then
    printf '%s: expected smoke-access to pass but it failed (%s): %s\n' \
      "$name" "$smoke_status" "$smoke_output" >&2
    exit 1
  fi
  if [ "$smoke_output" != "$expected_message" ]; then
    printf '%s: expected exact success line "%s" but got "%s"\n' \
      "$name" "$expected_message" "$smoke_output" >&2
    exit 1
  fi
  assert_no_leak "$name"
}

# Asserting the exact rejection text (not merely a non-zero exit) is what
# makes each case test its own named rule rather than going green because an
# unrelated earlier gate happened to fire first.
expect_fail() {
  name=$1
  expected_message=$2
  if [ "$smoke_status" -eq 0 ]; then
    printf '%s: expected smoke-access to reject but it succeeded: %s\n' "$name" "$smoke_output" >&2
    exit 1
  fi
  assert_no_leak "$name"
  if [ "$smoke_output" != "$expected_message" ]; then
    printf '%s: expected rejection "%s" but got "%s"\n' \
      "$name" "$expected_message" "$smoke_output" >&2
    exit 1
  fi
}

success_line="Access smoke passed"

# --- Step 1: usage and HTTPS-only base URL --------------------------------

new_fixture
run_smoke "$base_url" "$id_file"
expect_fail "missing secret file argument" \
  "Usage: smoke-access.sh BASE_URL CLIENT_ID_FILE CLIENT_SECRET_FILE"

new_fixture
run_smoke "http://llm.example.invalid/v1" "$id_file" "$secret_file"
expect_fail "non-HTTPS base URL" "Access smoke requires an HTTPS base URL"

# --- Step 1: credential file structural rejections ------------------------

new_fixture
run_smoke "$base_url" "$fixture_dir/does-not-exist" "$secret_file"
expect_fail "one missing credential file" "Access smoke: client ID file must exist"

new_fixture
rm -f "$id_file"
ln -s "$secret_file" "$id_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "symlinked credential file" "Access smoke: client ID file must not be a symlink"

new_fixture
chmod 644 "$id_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "credential file with a permissive mode" \
  "Access smoke: client ID file mode must be 0440, 0400, or 0600"

new_fixture
chmod u+w "$id_file"
: >"$id_file"
chmod 440 "$id_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "empty credential file" "Access smoke: client ID file must not be empty"

new_fixture
chmod u+w "$id_file"
head -c 600 /dev/zero | tr '\0' 'a' >"$id_file"
chmod 440 "$id_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "credential file larger than the bound" \
  "Access smoke: client ID file must be at most 512 bytes"

new_fixture
chmod u+w "$secret_file"
: >"$secret_file"
chmod 600 "$secret_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "missing secret credential value" "Access smoke: client secret file must not be empty"

# CR/LF credential injection: a newline embedded in the credential value
# could forge an additional header line in the curl config file.
new_fixture
chmod u+w "$id_file"
printf 'abc123\r\nX-Injected: evil' >"$id_file"
chmod 440 "$id_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "CR/LF credential injection" \
  "Access smoke: client ID value contains characters unsafe for an HTTP header"

new_fixture
chmod u+w "$id_file"
printf 'has a space in it' >"$id_file"
chmod 440 "$id_file"
run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "credential value with an unsafe character" \
  "Access smoke: client ID value contains characters unsafe for an HTTP header"

# --- Step 1: authenticated-leg response handling ---------------------------

new_fixture
FAKE_AUTH_STATUS=500 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "auth non-200" \
  "Access smoke: expected HTTP 200 for the authenticated request but got HTTP 500"

new_fixture
FAKE_AUTH_BODY="not-json-$body_sentinel" run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "malformed outer JSON" "Access smoke returned an invalid envelope"

new_fixture
FAKE_AUTH_BODY="{\"private\":\"$body_sentinel\",\"choices\":\"not-an-array\"}" \
  run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "missing/wrong-typed required outer fields" "Access smoke returned an invalid envelope"

new_fixture
extra_field_body='{"choices":[{"message":{"content":"{\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\",\"unexpected\":true}]}"}}]}'
FAKE_AUTH_BODY="$extra_field_body" run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "unexpected/extra decoded question fields" "Access smoke returned an invalid envelope"

new_fixture
FAKE_AUTH_EXIT=28 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "authenticated request timeout" "Access smoke: authenticated request failed"

# Additive outer OpenAI envelope fields must remain accepted.
new_fixture
additive_body='{"id":"chatcmpl-1","object":"chat.completion","choices":[{"message":{"content":"{\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\"}]}"}}]}'
FAKE_AUTH_BODY="$additive_body" run_smoke "$base_url" "$id_file" "$secret_file"
expect_pass "additive outer envelope fields accepted" "$success_line"

# --- Step 1: unauthenticated-leg response handling --------------------------

new_fixture
additive_body='{"id":"chatcmpl-1","choices":[{"message":{"content":"{\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\"}]}"}}]}'
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_STATUS=200 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "unauthenticated 200" \
  "Access smoke: expected HTTP 302, 401, or 403 for the unauthenticated request but got HTTP 200"

new_fixture
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_STATUS=404 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "unexpected 404" \
  "Access smoke: expected HTTP 302, 401, or 403 for the unauthenticated request but got HTTP 404"

new_fixture
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_STATUS=429 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "unexpected 429" \
  "Access smoke: expected HTTP 302, 401, or 403 for the unauthenticated request but got HTTP 429"

new_fixture
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_STATUS=503 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "unexpected 5xx" \
  "Access smoke: expected HTTP 302, 401, or 403 for the unauthenticated request but got HTTP 503"

new_fixture
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_EXIT=28 run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "unauthenticated request timeout" "Access smoke: unauthenticated request failed"

new_fixture
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_STATUS=302 run_smoke "$base_url" "$id_file" "$secret_file"
expect_pass "unauthenticated 302 accepted" "$success_line"

new_fixture
FAKE_AUTH_BODY="$additive_body" FAKE_UNAUTH_STATUS=401 run_smoke "$base_url" "$id_file" "$secret_file"
expect_pass "unauthenticated 401 accepted" "$success_line"

# --- Step 1: happy path with each allowed credential file mode -------------

new_fixture
FAKE_AUTH_BODY="$good_body" run_smoke "$base_url" "$id_file" "$secret_file"
expect_pass "clean pass with mode 0440/0600 credential files" "$success_line"

new_fixture
chmod u+w "$id_file"
chmod 400 "$id_file"
FAKE_AUTH_BODY="$good_body" run_smoke "$base_url" "$id_file" "$secret_file"
expect_pass "clean pass with mode 0400 client ID file" "$success_line"

# --- Step 1: body sentinel and credential sentinel never leak --------------
#
# The two leak checks above (`assert_no_leak`) already ran on every fixture;
# these two cases specifically drive a jq parse failure AND an argv/config
# leak probe together, so a regression in either suppression is caught even
# if every status-code case above happened to avoid it.

new_fixture
FAKE_AUTH_BODY="{\"choices\":[{\"message\":{\"content\":\"$body_sentinel not json\"}}]}" \
  run_smoke "$base_url" "$id_file" "$secret_file"
expect_fail "body sentinel never leaks even on a jq content-parse failure" \
  "Access smoke returned an invalid envelope"

new_fixture
FAKE_AUTH_BODY="$good_body" run_smoke "$base_url" "$id_file" "$secret_file"
expect_pass "credential sentinel never leaks on a clean pass" "$success_line"

# --- Step 1 fix-round: an inherited/explicit `sh -x` must never leak -------
#
# A source grep for an explicit `set -x`/`-o xtrace` (kept below) can only
# ever catch a *present* enable; it can never catch a *missing* `set +x`
# disable, because that failure mode is an absent line, not a present one.
# The only way to actually prove the property is to run the real script
# under a real `sh -x` with sentinel credentials and inspect its combined
# stdout+stderr for those sentinels, exactly as an operator would invoke it
# on EC2 while debugging a failed SSM invocation.
new_fixture
xtrace_id_sentinel="XTRACE-ID-DO-NOT-LEAK-$credential_sentinel"
xtrace_secret_sentinel="XTRACE-SECRET-DO-NOT-LEAK-$credential_sentinel"
chmod u+w "$id_file" "$secret_file"
printf '%s' "$xtrace_id_sentinel" >"$id_file"
printf '%s' "$xtrace_secret_sentinel" >"$secret_file"
chmod 440 "$id_file"
chmod 600 "$secret_file"

set +e
xtrace_output="$(
  PATH="$test_root/bin:$PATH" \
    FAKE_AUTH_BODY="$good_body" \
    sh -x "$smoke" "$base_url" "$id_file" "$secret_file" 2>&1
)"
xtrace_status=$?
set -e

case "$xtrace_output" in
  *"$xtrace_id_sentinel"*)
    printf 'sh -x invocation leaked the client ID credential\n' >&2
    exit 1
    ;;
esac
case "$xtrace_output" in
  *"$xtrace_secret_sentinel"*)
    printf 'sh -x invocation leaked the client secret credential\n' >&2
    exit 1
    ;;
esac
if [ "$xtrace_status" -ne 0 ]; then
  printf 'sh -x invocation unexpectedly failed (%s): %s\n' "$xtrace_status" "$xtrace_output" >&2
  exit 1
fi
printf '%s\n' "sh -x invocation does not leak credentials"

if grep -Eq '(^|[^[:alnum:]_])set[[:space:]]+(-[A-Za-z]*x[A-Za-z]*|-o[[:space:]]+xtrace)([^[:alnum:]_-]|$)' "$smoke"; then
  printf '%s\n' "smoke-access.sh must never enable shell xtrace" >&2
  exit 1
fi

printf 'smoke-access fixtures: %s cases passed\n' "$fixture_number"
