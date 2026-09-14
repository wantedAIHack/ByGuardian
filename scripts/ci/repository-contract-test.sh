#!/bin/sh

set -eu

script_dir=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
repo_root=$(CDPATH='' cd -- "$script_dir/../.." && pwd)

test -d "$repo_root/frontend/src"
test -d "$repo_root/backend/api/src"
test -d "$repo_root/infra/llm"
test -f "$repo_root/frontend/README.md"
test -f "$repo_root/README.md"
test -f "$repo_root/backend/README.md"
test -z "$(git -C "$repo_root" ls-files '.context/*')"

# grep -E, not ripgrep: GitHub's ubuntu-latest image ships no `rg`, so this
# check exited 127 ("rg: not found") on every hosted run while passing on any
# developer machine that happened to have ripgrep installed.
if grep -nE 'frontend.*별도.*branch|프론트엔드.*미구현|LLM.*미착수|systemd로 Ollama' \
  "$repo_root/README.md" "$repo_root/backend/README.md"; then
  exit 1
else
  grep_status=$?
fi

test "$grep_status" -eq 1 || exit "$grep_status"

echo "repository contract tests passed"
