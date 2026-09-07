#!/bin/sh
set -eu

: "${OLLAMA_HOST:?OLLAMA_HOST is required}"
: "${OLLAMA_MODEL:?OLLAMA_MODEL is required}"

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
export OLLAMA_HOST
"$script_dir/wait-for-ollama.sh"
if ollama show "$OLLAMA_MODEL" >/dev/null 2>&1; then
  printf '%s\n' "Ollama model is already present: $OLLAMA_MODEL"
  exit 0
fi

printf '%s\n' "Pulling Ollama model: $OLLAMA_MODEL"
ollama pull "$OLLAMA_MODEL" >/dev/null
printf '%s\n' "Ollama model is ready: $OLLAMA_MODEL"
