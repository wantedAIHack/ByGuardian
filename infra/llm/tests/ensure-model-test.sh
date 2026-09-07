#!/usr/bin/env bash
set -euo pipefail

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
chmod +x "$test_root/bin/ollama"

PATH="$test_root/bin:$script_dir/../scripts:$PATH" \
FAKE_OLLAMA_LOG="$fake_log" \
FAKE_MODEL_PRESENT=true \
OLLAMA_HOST=http://fake-ollama:11434 \
OLLAMA_MODEL=qwen3:4b-q8_0 \
OLLAMA_WAIT_ATTEMPTS=1 \
"$script_dir/../scripts/ensure-model.sh"
test ! -e "$fake_log"

PATH="$test_root/bin:$script_dir/../scripts:$PATH" \
FAKE_OLLAMA_LOG="$fake_log" \
FAKE_MODEL_PRESENT=false \
OLLAMA_HOST=http://fake-ollama:11434 \
OLLAMA_MODEL=qwen3:4b-q8_0 \
OLLAMA_WAIT_ATTEMPTS=1 \
"$script_dir/../scripts/ensure-model.sh"
test "$(cat "$fake_log")" = "qwen3:4b-q8_0"

printf '%s\n' "ensure-model tests passed"
