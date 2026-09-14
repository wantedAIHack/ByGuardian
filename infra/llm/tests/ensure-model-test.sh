#!/bin/sh
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT
mkdir -p "$test_root/bin"
fake_log="$test_root/ollama.log"

cat >"$test_root/bin/ollama" <<'FAKE'
#!/bin/sh
set -eu
case "$1" in
  list)
    exit 0
    ;;
  show)
    if [ "${FAKE_MODEL_PRESENT:-false}" = "true" ]; then
      exit 0
    fi
    exit 1
    ;;
  pull)
    printf '%s\n' "$2" >>"$FAKE_OLLAMA_LOG"
    ;;
  *)
    exit 2
    ;;
esac
FAKE

cat >"$test_root/bin/curl" <<'FAKE'
#!/bin/sh
set -eu
response_file=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --output)
      response_file="$2"
      shift 2
      ;;
    *)
      shift
      ;;
  esac
done
: "${response_file:?missing curl output file}"
printf '%s' '{"private":"PRIVATE_RESPONSE_BODY_SENTINEL","choices":[{"message":{"content":"{\"questions\":[{\"rank\":1,\"sentence\":\"확인할까요?\",\"unexpected\":true}]}"}}]}' >"$response_file"
printf '%s' '200'
FAKE
chmod +x "$test_root/bin/ollama" "$test_root/bin/curl"

PATH="$test_root/bin:$script_dir/../scripts:$PATH" \
FAKE_OLLAMA_LOG="$fake_log" \
FAKE_MODEL_PRESENT=true \
OLLAMA_HOST=http://fake-ollama:11434 \
OLLAMA_MODEL=qwen3:4b-q4_K_M \
OLLAMA_WAIT_ATTEMPTS=1 \
"$script_dir/../scripts/ensure-model.sh"
test ! -e "$fake_log"

PATH="$test_root/bin:$script_dir/../scripts:$PATH" \
FAKE_OLLAMA_LOG="$fake_log" \
FAKE_MODEL_PRESENT=false \
OLLAMA_HOST=http://fake-ollama:11434 \
OLLAMA_MODEL=qwen3:4b-q4_K_M \
OLLAMA_WAIT_ATTEMPTS=1 \
"$script_dir/../scripts/ensure-model.sh"
test "$(cat "$fake_log")" = "qwen3:4b-q4_K_M"

printf '%s\n' "ensure-model tests passed"

set +e
smoke_output="$(
  PATH="$test_root/bin:$PATH" \
    "$script_dir/../scripts/smoke-openai.sh" http://fake-openai/v1 2>&1
)"
smoke_status=$?
set -e
if [ "$smoke_status" -eq 0 ]; then
  printf '%s\n' "smoke-openai accepted an unexpected question field" >&2
  exit 1
fi
test "$smoke_status" -eq 1
case "$smoke_output" in
  *PRIVATE_RESPONSE_BODY_SENTINEL*)
    printf '%s\n' "smoke-openai leaked the response body" >&2
    exit 1
    ;;
esac
test "$smoke_output" = "OpenAI smoke returned an invalid envelope"

printf '%s\n' "smoke-openai exact-field tests passed"
