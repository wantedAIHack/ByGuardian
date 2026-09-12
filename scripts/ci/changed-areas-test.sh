#!/bin/sh
set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
classifier=${CHANGED_AREAS_SCRIPT:-$script_dir/changed-areas.sh}
scratch=$(mktemp -d "${TMPDIR:-/tmp}/changed-areas-test.XXXXXX")
trap 'rm -rf "$scratch"' EXIT HUP INT TERM

check() {
  label=$1 paths=$2 frontend=$3 backend=$4 infra=$5 integration=$6
  expected=$(printf 'frontend=%s\nbackend=%s\ninfra=%s\nintegration=%s\nsecurity=true' \
    "$frontend" "$backend" "$infra" "$integration")
  actual=$(printf '%s' "$paths" | GITHUB_OUTPUT='' sh "$classifier")
  if [ "$actual" != "$expected" ]; then
    printf 'FAIL: %s\nexpected:\n%s\nactual:\n%s\n' "$label" "$expected" "$actual" >&2
    exit 1
  fi
}

check frontend 'frontend/src/App.tsx' true false false true
check frontend-lock 'frontend/package-lock.json' true false false true
check backend 'backend/api/src/main/App.java' false true false true
check gradle-lock 'backend/engine/gradle.lockfile' false true false true
check gradle-checksums 'backend/gradle/verification-metadata.xml' false true false true
check llm 'infra/llm/scripts/ensure-model.sh' false false true false
check workflow '.github/workflows/ci.yml' false false true false
check workflow-policy 'infra/llm/tests/workflows-test.sh' false false true false
check local-infra 'infra/local/compose.integration.yml' false false false true
check ci-script 'scripts/ci/integration-e2e.sh' false false false true
check docs 'README.md
docs/deployment.md
frontend/README.md
backend/README.md' false false false false
check mixed 'frontend/src/App.tsx
infra/llm/Dockerfile' true false true true
check unknown 'unknown/security-sensitive.file' true true true true
check unknown-with-spaces 'unknown/a file.txt' true true true true
check empty '' false false false false

# Outputs append to the runner file and never leak onto stdout.
printf 'existing=value\n' >"$scratch/output"
actual=$(printf '%s\n' 'backend/api/build.gradle.kts' | GITHUB_OUTPUT="$scratch/output" sh "$classifier")
test -z "$actual"
test "$(sed -n '1p' "$scratch/output")" = existing=value
test "$(wc -l <"$scratch/output" | tr -d ' ')" = 6
grep -qx 'backend=true' "$scratch/output"
printf '%s\n' 'changed-areas: 16 fixtures passed'
