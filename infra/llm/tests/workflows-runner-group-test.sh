#!/bin/sh
# These Perl mutation programs must receive literal workflow shell variables.
# shellcheck disable=SC2016
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/nextvisit-llm-runner-group.XXXXXX")"
fixture_number=0

# The pristine policy must pass before any rejection counts as a useful mutation.
sh "$repo_root/infra/llm/tests/workflows-test.sh"

cleanup() {
  rm -rf "$fixture_root"
}
trap cleanup EXIT HUP INT TERM

prepare_fixture() {
  fixture_number=$((fixture_number + 1))
  fixture="$fixture_root/$fixture_number"
  mkdir -p "$fixture/.github/workflows" "$fixture/infra/llm/tests"
  cp "$repo_root/.github/workflows/ci.yml" "$fixture/.github/workflows/ci.yml"
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
  "$fixture/.github/workflows/ci.yml"
expect_rejected "CI job using self-hosted labels"

prepare_fixture
perl -0pi -e 's{    runs-on: ubuntu-latest}{    runs-on:\n      group: llm-production\n      labels: [self-hosted, linux, llm]}' \
  "$fixture/.github/workflows/ci.yml"
expect_rejected "CI job using a self-hosted runner group"

prepare_fixture
perl -0pi -e 's{NEXTVISIT_LLM_TOKEN_FILE: /etc/nextvisit/llm.token}{NEXTVISIT_LLM_ENV_FILE: /etc/nextvisit/llm.env}' \
  "$fixture/.github/workflows/llm-deploy.yml"
expect_rejected "deploy reintroducing the legacy Tunnel environment file variable"

prepare_fixture
perl -0pi -e 's{run: infra/llm/scripts/verify-tunnel-token-file.sh /etc/nextvisit/llm.token}{run: |\n          test -r /etc/nextvisit/llm.env\n          test "\$(stat -c \x27%a\x27 /etc/nextvisit/llm.env)" = 600}' \
  "$fixture/.github/workflows/llm-deploy.yml"
expect_rejected "deploy reverting to the unverified legacy environment file check"

mutate_ci() {
  prepare_fixture
  perl -0pi -e "$1" "$fixture/.github/workflows/ci.yml"
  expect_rejected "$2"
}
mutate_ci 's/\@sha256:1547b7c2783d4f266b24fe86ab4dfc18d058588244c58384ac9f56dddb304511//' 'tag-only OSV image'
mutate_ci 's/1547b7c2783d4f266b24fe86ab4dfc18d058588244c58384ac9f56dddb304511/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/' 'different OSV digest'
mutate_ci 's/\@sha256:d366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25//' 'tag-only TruffleHog image'
mutate_ci 's/d366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/' 'different TruffleHog digest'
mutate_ci 's/49933ea5288caeca8642d1e84afbd3f7d6820020/v4/' 'mutable action tag'
mutate_ci 's/pull_request:/pull_request_target:/' 'privileged PR trigger'
mutate_ci 's/contents: read/contents: write/' 'write permissions'
mutate_ci 's/  frontend:/  frontend:\n    permissions: write-all/' 'job permission override'
mutate_ci 's/persist-credentials: false/persist-credentials: true/' 'stored checkout credentials'
mutate_ci 's/fetch-depth: 0/fetch-depth: 1/' 'shallow checkout'
mutate_ci 's/ref: \$\{\{ github.sha \}\}/ref: main/' 'moving checkout ref'
mutate_ci 's/  pull_request:/  pull_request:\n    paths: [docs\/**]/' 'top-level path filter'
mutate_ci 's/--results=verified /--results=unverified /' 'suppressed verified secrets'
mutate_ci 's{        run: node scripts/ci/secret-findings-gate.mjs}{        run: true}' 'removed unverified occurrence gate'
mutate_ci 's{        run: node --test scripts/ci/secret-findings-test.mjs scripts/ci/secret-findings-mutations-test.mjs}{        run: true}' 'removed secret candidate contract tests'
mutate_ci 's/        if: \$\{\{ always\(\) \}\}/        if: false/' 'conditional secret candidate gate'
mutate_ci 's/BASE_SHA: \$\{\{ needs.changes.outputs.scan_base \}\}/BASE_SHA: ignored/' 'secret candidate range diverges from pinned action'
mutate_ci 's{sh scripts/ci/changed-areas-test.sh}{true}' 'removed fail-closed classifier fixtures'
mutate_ci 's/--cap-drop ALL/--cap-drop NET_RAW/' 'retained scanner capabilities'
mutate_ci 's/GITHUB_WORKSPACE:\/src:ro/GITHUB_WORKSPACE:\/src:rw/' 'writable scanner checkout'
mutate_ci 's/test -n "\$head"/true/' 'empty secret scan head'
mutate_ci 's/test "\$base" != "\$head"/true/' 'equal secret scan range'
mutate_ci 's/base=; diff_base=\$\(git hash-object -t tree \/dev\/null\)/exit 0/' 'initial push silently skipped'
mutate_ci 's/then base=; fi/then :; fi/' 'removed reverse-force-push full-head fallback'
mutate_ci 's/then base=; fi/then diff_base=; fi/' 'fallback changes classification instead of the common scan range'
mutate_ci 's/base: \$\{\{ needs.changes.outputs.scan_base \}\}/base: original-before-sha/' 'action bypasses the common full-head fallback'
mutate_ci 's/true:success\|false:skipped/true:success|true:cancelled|false:skipped/' 'cancelled selected job accepted'
mutate_ci 's/test "\$SECURITY_RESULT" = success/true/' 'ignored security failure'
mutate_ci 's/(  ci-gate:\n    needs: [^\n]*\n)    if: [^\n]*/${1}    if: false/' 'conditional stable gate'
mutate_ci 's/(  security:\n    needs: changes\n)    if: [^\n]*/${1}    if: false/' 'conditional security inventory'
mutate_ci 's/needs[.]frontend[.]result/needs.backend.result/' 'cross-wired gate result'
mutate_ci 's/ci-render-only-token-sentinel/legacy-TUNNEL_TOKEN-sentinel/' 'reintroduced TUNNEL_TOKEN literal'
mutate_ci 's/NEXTVISIT_LLM_TOKEN_FILE="\$token_file"/NEXTVISIT_LLM_ENV_FILE="\$token_file"/' 'reintroduced NEXTVISIT_LLM_ENV_FILE variable'
mutate_ci 's{          ! jq -e }{          jq -e }' 'disabled structural token-absence check'
mutate_ci 's/umask 077/umask 077 # --token/' 'introduced a bare --token argument reference'

prepare_fixture
cp "$fixture/.github/workflows/ci.yml" "$fixture/.github/workflows/extra.yml"
expect_rejected 'additional workflow'
printf 'workflow policy: %s mutations rejected\n' "$fixture_number"

# Execute the actual workflow programs: source-text checks alone cannot prove
# the gate truth table or PR/push commit-range semantics.
gate_script="$fixture_root/gate.sh"
range_script="$fixture_root/range.sh"
awk '/# BEGIN ci gate/ { capture=1; next } /# END ci gate/ { capture=0 } capture { sub(/^          /, ""); print }' \
  "$repo_root/.github/workflows/ci.yml" >"$gate_script"
awk '/# BEGIN scan range/ { capture=1; next } /# END scan range/ { capture=0 } capture { sub(/^          /, ""); print }' \
  "$repo_root/.github/workflows/ci.yml" >"$range_script"
test -s "$gate_script"
test -s "$range_script"
gate_cases=0
gate_case() {
  want=$1
  shift
  gate_cases=$((gate_cases + 1))
  got=fail
  if env CHANGES_RESULT=success SECURITY_RESULT=success \
    FRONTEND_SELECTED=true FRONTEND_RESULT=success \
    BACKEND_SELECTED=true BACKEND_RESULT=success \
    INFRA_SELECTED=true INFRA_RESULT=success \
    INTEGRATION_SELECTED=true INTEGRATION_RESULT=success \
    "$@" sh "$gate_script" >"$fixture_root/gate.log" 2>&1; then got=pass; fi
  if [ "$got" != "$want" ]; then
    printf 'gate fixture %s: expected %s, got %s\n' "$gate_cases" "$want" "$got" >&2
    exit 1
  fi
}
gate_case pass
gate_case pass FRONTEND_SELECTED=false FRONTEND_RESULT=skipped BACKEND_SELECTED=false BACKEND_RESULT=skipped \
  INFRA_SELECTED=false INFRA_RESULT=skipped INTEGRATION_SELECTED=false INTEGRATION_RESULT=skipped
gate_case pass FRONTEND_SELECTED=false FRONTEND_RESULT=skipped
for area in FRONTEND BACKEND INFRA INTEGRATION; do
  for selected in true false invalid; do
    for result in success skipped failure cancelled; do
      want=fail
      case "$selected:$result" in true:success|false:skipped) want=pass ;; esac
      gate_case "$want" "${area}_SELECTED=$selected" "${area}_RESULT=$result"
    done
  done
done
for required in CHANGES SECURITY; do
  for result in failure skipped cancelled ''; do
    gate_case fail "${required}_RESULT=$result"
  done
done
printf 'ci-gate: %s execution fixtures passed\n' "$gate_cases"

range_repo="$fixture_root/range-repository"
mkdir "$range_repo"
git -C "$range_repo" init -q -b main
git -C "$range_repo" config user.name 'CI Fixture'
git -C "$range_repo" config user.email 'ci-fixture@example.invalid'
printf '%s\n' 'documentation' >"$range_repo/README.md"
git -C "$range_repo" add README.md
git -C "$range_repo" commit -qm root
root_commit=$(git -C "$range_repo" rev-parse HEAD)
git -C "$range_repo" checkout -qb feature
mkdir -p "$range_repo/frontend/src"
printf '%s\n' 'frontend change' >"$range_repo/frontend/src/App.txt"
git -C "$range_repo" add frontend/src/App.txt
git -C "$range_repo" commit -qm feature
feature_commit=$(git -C "$range_repo" rev-parse HEAD)
git -C "$range_repo" checkout -q main
printf '%s\n' 'target branch advanced' >"$range_repo/target-only.txt"
git -C "$range_repo" add target-only.txt
git -C "$range_repo" commit -qm target
target_commit=$(git -C "$range_repo" rev-parse HEAD)
range_output="$fixture_root/range-output"
range_cases=0
range_case() {
  want=$1
  shift
  range_cases=$((range_cases + 1))
  : >"$range_output"
  got=fail
  if (cd "$range_repo" && env GITHUB_OUTPUT="$range_output" EVENT_NAME=pull_request \
    PR_BASE="$target_commit" PR_HEAD="$feature_commit" PUSH_BEFORE="$root_commit" PUSH_AFTER="$feature_commit" \
    "$@" sh "$range_script") >"$fixture_root/range.log" 2>&1; then got=pass; fi
  if [ "$got" != "$want" ]; then
    printf 'range fixture %s: expected %s, got %s\n' "$range_cases" "$want" "$got" >&2
    exit 1
  fi
}
range_case pass
grep -qx "scan_base=$target_commit" "$range_output"
grep -qx "scan_head=$feature_commit" "$range_output"
grep -qx "diff_base=$root_commit" "$range_output"
test "$(git -C "$range_repo" diff --name-only "$root_commit" "$feature_commit")" = frontend/src/App.txt
range_case pass EVENT_NAME=push
grep -qx "scan_base=$root_commit" "$range_output"
grep -qx "diff_base=$root_commit" "$range_output"
range_case pass EVENT_NAME=push PUSH_BEFORE=0000000000000000000000000000000000000000
grep -qx 'scan_base=' "$range_output"
grep -qx "scan_head=$feature_commit" "$range_output"
empty_tree=$(git -C "$range_repo" hash-object -t tree /dev/null)
grep -qx "diff_base=$empty_tree" "$range_output"
test "$(git -C "$range_repo" diff --name-only "$empty_tree" "$feature_commit" | wc -l | tr -d ' ')" = 2
range_case pass EVENT_NAME=push PUSH_BEFORE="$target_commit"
grep -qx "diff_base=$target_commit" "$range_output"
range_case pass EVENT_NAME=push PUSH_BEFORE="$target_commit" PUSH_AFTER="$root_commit"
grep -qx 'scan_base=' "$range_output"
grep -qx "scan_head=$root_commit" "$range_output"
grep -qx "diff_base=$target_commit" "$range_output"
test "$(git -C "$range_repo" rev-list --count "$root_commit" --)" -gt 0
range_case pass PR_BASE="$target_commit" PR_HEAD="$root_commit"
grep -qx 'scan_base=' "$range_output"
grep -qx "scan_head=$root_commit" "$range_output"
grep -qx "diff_base=$root_commit" "$range_output"
range_case fail PR_HEAD=''
range_case fail PR_BASE=''
range_case fail PR_HEAD=refs/heads/main
range_case fail PR_BASE="$feature_commit"
range_case fail PR_HEAD=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
range_case fail PR_BASE=0000000000000000000000000000000000000000
range_case fail EVENT_NAME=schedule
range_case fail EVENT_NAME=push PUSH_AFTER=0000000000000000000000000000000000000000
printf 'scan ranges: %s real Git fixtures passed\n' "$range_cases"
