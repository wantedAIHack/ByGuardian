#!/bin/sh
set -eu
# An inherited or explicitly requested `-x`/xtrace would otherwise echo the
# plaintext Access Client ID/Secret to stderr the moment they are read or
# interpolated into the curl config file below -- exactly what an operator
# reaches for when debugging a failed SSM invocation on EC2. Disable it
# unconditionally, immediately, before any credential is ever touched.
set +x

# Response-body-silent Cloudflare Access smoke.
#
# Proves two things about the Access-protected Ollama origin without ever
# letting a credential or a model response body reach stdout, stderr, a log,
# or a process argument list:
#   1. An authenticated request (Access Client ID/Secret headers) succeeds
#      with HTTP 200 and an OpenAI-compatible envelope whose decoded
#      assistant content matches smoke-openai.sh's exact one-question
#      contract.
#   2. The same request without those headers is refused with HTTP
#      302/401/403.
#
# Credentials are consumed as file paths (CLIENT_ID_FILE/CLIENT_SECRET_FILE),
# never as values: never on curl argv, never in an environment variable,
# never echoed. This is what lets the AWS release bundle allowlist this
# script for SSM to run on EC2 beside the Access configtree files -- it
# never touches the laptop for external authentication.

max_credential_bytes=512
model="qwen3:4b-q8_0"

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[ "$#" -eq 3 ] || fail "Usage: smoke-access.sh BASE_URL CLIENT_ID_FILE CLIENT_SECRET_FILE"

base_url=$1
client_id_file=$2
client_secret_file=$3

case "$base_url" in
  https://*) ;;
  *) fail "Access smoke requires an HTTPS base URL" ;;
esac

# Numeric file mode without dereferencing symlinks (lstat semantics on both
# GNU and BSD stat implementations).
mode_of() {
  if out="$(stat -c '%a' -- "$1" 2>/dev/null)"; then
    printf '%s\n' "$out"
    return 0
  fi
  stat -f '%Lp' -- "$1"
}

# Reads and validates one credential file, printing its value on success.
# Never prints the value anywhere else -- every failure path is a fixed
# message that never interpolates file content.
read_credential() {
  label=$1
  path=$2

  [ -L "$path" ] && fail "Access smoke: $label file must not be a symlink"
  [ -e "$path" ] || fail "Access smoke: $label file must exist"
  [ -f "$path" ] || fail "Access smoke: $label file must be a regular file"

  cred_mode="$(mode_of "$path")"
  case "$cred_mode" in
    440 | 400 | 600) ;;
    *) fail "Access smoke: $label file mode must be 0440, 0400, or 0600" ;;
  esac

  cred_size="$(wc -c <"$path" | tr -d ' ')"
  [ "$cred_size" -le "$max_credential_bytes" ] ||
    fail "Access smoke: $label file must be at most $max_credential_bytes bytes"

  # Command substitution strips trailing newlines, so a single LF-terminated
  # line collapses to just its value; any newline that survives came from
  # inside the line and is caught below along with every other header-unsafe
  # or CR/LF-injecting byte.
  cred_value="$(cat -- "$path")"
  [ -n "$cred_value" ] || fail "Access smoke: $label file must not be empty"

  case "$cred_value" in
    *[!A-Za-z0-9._~+/=-]*)
      fail "Access smoke: $label value contains characters unsafe for an HTTP header"
      ;;
  esac

  printf '%s' "$cred_value"
}

client_id="$(read_credential "client ID" "$client_id_file")"
client_secret="$(read_credential "client secret" "$client_secret_file")"

payload_file="$(mktemp)"
auth_config="$(mktemp)"
auth_response_file="$(mktemp)"
trap 'rm -f "$payload_file" "$auth_config" "$auth_response_file"' EXIT HUP INT TERM
chmod 600 "$payload_file" "$auth_config" "$auth_response_file"

jq -n --arg model "$model" '{
  model: $model,
  stream: false,
  temperature: 0.1,
  seed: 0,
  max_tokens: 128,
  response_format: {type: "json_object"},
  messages: [
    {role: "system", content: "/no_think JSON 객체만 반환하세요. 정확히 {\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\"}]} 형식입니다."},
    {role: "user", content: "{\"questions\":[{\"rank\":1,\"templateSentence\":\"확인할까요?\"}]}"}
  ]
}' >"$payload_file"

# The two Access headers live only in this mode-0600 config file, never on
# curl's own argv, so a process listing can never reveal them. The
# credential values are already validated header-safe ASCII with no CR/LF,
# so interpolating them into a double-quoted config-file header line cannot
# forge or break out of that line.
printf '%s\n' \
  'silent' \
  'show-error' \
  'connect-timeout = 5' \
  'max-time = 45' \
  'header = "Content-Type: application/json"' \
  "header = \"CF-Access-Client-Id: $client_id\"" \
  "header = \"CF-Access-Client-Secret: $client_secret\"" \
  >"$auth_config"

set +e
auth_status="$(curl --config "$auth_config" \
  --output "$auth_response_file" --write-out '%{http_code}' \
  --data-binary "@$payload_file" \
  "${base_url%/}/chat/completions")"
auth_curl_exit=$?
set -e
[ "$auth_curl_exit" -eq 0 ] || fail "Access smoke: authenticated request failed"

case "$auth_status" in
  200) ;;
  *) fail "Access smoke: expected HTTP 200 for the authenticated request but got HTTP $auth_status" ;;
esac

# Both jq streams are suppressed: a parse error on a malformed or
# credential/body-bearing fragment would otherwise echo that fragment to
# stderr. The check itself is byte-identical to smoke-openai.sh's envelope
# contract -- additive outer fields are ignored (only .choices is inspected),
# while the decoded question object's key set is exact.
if ! jq -e '
  (.choices | type == "array" and length > 0) and
  (.choices[0].message.content | type == "string") and
  ((.choices[0].message.content | fromjson) as $content |
    ($content | keys == ["questions"]) and
    ($content.questions | type == "array" and length == 1) and
    ($content.questions[0] | type == "object" and keys == ["rank", "sentence"]) and
    ($content.questions[0].rank == 1) and
    ($content.questions[0].sentence | type == "string" and endswith("?")))
' "$auth_response_file" >/dev/null 2>&1; then
  fail "Access smoke returned an invalid envelope"
fi

# The unauthenticated leg carries no Access headers and its body is
# discarded straight to /dev/null -- only the status line is ever inspected.
set +e
unauth_status="$(curl --silent --show-error \
  --connect-timeout 5 --max-time 45 \
  --output /dev/null --write-out '%{http_code}' \
  --header 'Content-Type: application/json' \
  --data-binary "@$payload_file" \
  "${base_url%/}/chat/completions")"
unauth_curl_exit=$?
set -e
[ "$unauth_curl_exit" -eq 0 ] || fail "Access smoke: unauthenticated request failed"

case "$unauth_status" in
  302 | 401 | 403) ;;
  *) fail "Access smoke: expected HTTP 302, 401, or 403 for the unauthenticated request but got HTTP $unauth_status" ;;
esac

printf '%s\n' "Access smoke passed"
