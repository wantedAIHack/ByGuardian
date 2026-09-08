#!/bin/sh
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/nextvisit-llm-runner-group.XXXXXX")"
fixture_number=0

cleanup() {
  rm -rf "$fixture_root"
}
trap cleanup EXIT HUP INT TERM

prepare_fixture() {
  fixture_number=$((fixture_number + 1))
  fixture="$fixture_root/$fixture_number"
  mkdir -p "$fixture/.github/workflows" "$fixture/infra/llm/tests"
  cp "$repo_root/.github/workflows/llm-ci.yml" "$fixture/.github/workflows/llm-ci.yml"
  cp "$repo_root/.github/workflows/llm-deploy.yml" "$fixture/.github/workflows/llm-deploy.yml"
  cp "$repo_root/infra/llm/tests/workflows-test.sh" "$fixture/infra/llm/tests/workflows-test.sh"
}

expect_rejected() {
  name=$1
  if sh "$fixture/infra/llm/tests/workflows-test.sh" >/dev/null 2>&1; then
    printf '%s\n' "$name must be rejected by the workflow policy" >&2
    exit 1
  fi
}

prepare_fixture
perl -0pi -e 's{      group: llm-production\n}{}' \
  "$fixture/.github/workflows/llm-deploy.yml"
expect_rejected "deploy without an organization runner group"

prepare_fixture
perl -0pi -e 's{      group: llm-production}{      group: another-production-group}' \
  "$fixture/.github/workflows/llm-deploy.yml"
expect_rejected "deploy with a changed organization runner group"

prepare_fixture
perl -0pi -e 's{    runs-on:\n      group: llm-production\n      labels: \[self-hosted, linux, llm\]}{    runs-on: [self-hosted, linux, llm]}' \
  "$fixture/.github/workflows/llm-deploy.yml"
expect_rejected "deploy falling back to label-only runner selection"

prepare_fixture
perl -0pi -e 's{    runs-on: ubuntu-latest}{    runs-on: [self-hosted, linux, llm]}' \
  "$fixture/.github/workflows/llm-ci.yml"
expect_rejected "CI job using self-hosted labels"

prepare_fixture
perl -0pi -e 's{    runs-on: ubuntu-latest}{    runs-on:\n      group: llm-production\n      labels: [self-hosted, linux, llm]}' \
  "$fixture/.github/workflows/llm-ci.yml"
expect_rejected "CI job using a self-hosted runner group"

printf '%s\n' "workflow runner-group mutation tests passed"
