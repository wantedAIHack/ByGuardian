#!/bin/sh
set -eu

attempts="${OLLAMA_WAIT_ATTEMPTS:-60}"
interval="${OLLAMA_WAIT_INTERVAL_SECONDS:-2}"
attempt=1

while [ "$attempt" -le "$attempts" ]; do
  if ollama list >/dev/null 2>&1; then
    exit 0
  fi
  if [ "$attempt" -lt "$attempts" ]; then
    sleep "$interval"
  fi
  attempt=$((attempt + 1))
done

printf '%s\n' "Ollama did not become ready within ${attempts} attempts" >&2
exit 1
