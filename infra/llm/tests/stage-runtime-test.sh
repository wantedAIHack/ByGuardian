#!/bin/sh
set -eu

# Fixture harness for infra/llm/scripts/stage-runtime.sh.
#
# Every case gets its own fresh Git checkout (a real repository, so HEAD is a
# real 40-hex commit SHA -- no fake `git` binary needed) plus its own fake
# releases/current directories addressed through
# NEXTVISIT_LLM_RELEASES_PATH/NEXTVISIT_LLM_CURRENT_PATH. Nothing here ever
# touches the real /opt/nextvisit/llm paths.

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
stage="$repo_root/infra/llm/scripts/stage-runtime.sh"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

allowlisted_paths='
Dockerfile
compose.yml
compose.gpu.yml
compose.tunnel.yml
scripts/wait-for-ollama.sh
scripts/ensure-model.sh
scripts/verify-tunnel-token-file.sh
'

fixture_number=0

# Fresh checkout + fresh fake releases/current state, one commit already made.
# sha, checkout, releases_dir, current_parent, current_path are set for the
# caller. NEXTVISIT_LLM_* is never left set on a function-call prefix, so
# nothing here uses that form to begin with.
new_fixture() {
  fixture_number=$((fixture_number + 1))
  fixture="$test_root/f$fixture_number"
  checkout="$fixture/checkout"
  releases_dir="$fixture/state/releases"
  current_parent="$fixture/state/run"
  current_path="$current_parent/current"
  mkdir -p "$checkout/scripts" "$releases_dir" "$current_parent"
  chmod 0750 "$releases_dir"

  for rel in $allowlisted_paths; do
    [ -n "$rel" ] || continue
    cp "$repo_root/infra/llm/$rel" "$checkout/$rel"
  done
  chmod 0644 "$checkout/Dockerfile" "$checkout"/compose*.yml
  chmod 0755 "$checkout"/scripts/*.sh

  # A checkout that never contains anything resembling a credential proves
  # nothing about the allowlist -- it would "pass" identically whether or not
  # stage-runtime.sh actually excludes credentials. These are obvious
  # synthetic sentinels, not real secrets, planted purely so the "credential
  # never reaches a release" assertions below have something to catch.
  printf 'NEXTVISIT_FIXTURE_ENV_SENTINEL=not-a-real-secret\n' >"$checkout/.env"
  printf 'nextvisit-fixture-secret-key-sentinel\n' >"$checkout/scripts/secret.key"

  git -C "$checkout" init -q -b main
  git -C "$checkout" config user.name 'Stage Fixture'
  git -C "$checkout" config user.email 'stage-fixture@example.invalid'
  git -C "$checkout" add -A
  git -C "$checkout" commit -q -m initial
  sha="$(git -C "$checkout" rev-parse HEAD)"
}

run_stage() {
  set +e
  stage_output="$(
    NEXTVISIT_LLM_RELEASES_PATH="$releases_dir" \
      NEXTVISIT_LLM_CURRENT_PATH="$current_path" \
      "$stage" "$@" 2>&1
  )"
  stage_status=$?
  set -e
}

expect_pass() {
  name=$1
  expected_sha=$2
  if [ "$stage_status" -ne 0 ]; then
    printf '%s: expected success but stage failed (%s): %s\n' \
      "$name" "$stage_status" "$stage_output" >&2
    exit 1
  fi
  if [ "$stage_output" != "staged release $expected_sha" ]; then
    printf '%s: expected exact success line for %s, got "%s"\n' \
      "$name" "$expected_sha" "$stage_output" >&2
    exit 1
  fi
}

# Asserting the exact rejection text (not merely a non-zero exit) is what
# keeps each case testing its own named rule rather than going green because
# some unrelated check happened to fire first.
expect_fail() {
  name=$1
  expected_message=$2
  if [ "$stage_status" -eq 0 ]; then
    printf '%s: expected stage to reject but it succeeded: %s\n' "$name" "$stage_output" >&2
    exit 1
  fi
  if [ "$stage_output" != "$expected_message" ]; then
    printf '%s: expected rejection "%s" but got "%s"\n' \
      "$name" "$expected_message" "$stage_output" >&2
    exit 1
  fi
}

assert_current_target() {
  name=$1
  expected=$2
  if [ ! -e "$current_path" ] && [ ! -L "$current_path" ]; then
    actual='<absent>'
  else
    actual="$(readlink "$current_path")"
  fi
  if [ "$actual" != "$expected" ]; then
    printf '%s: expected current -> "%s" but found "%s"\n' "$name" "$expected" "$actual" >&2
    exit 1
  fi
}

# --- happy path: exact release contents, current flip, no forbidden files ---

new_fixture
run_stage "$sha" "$checkout"
expect_pass "clean stage" "$sha"

release_dir="$releases_dir/$sha"
assert_current_target "clean stage" "$release_dir"

expected_listing="$test_root/expected-listing-$fixture_number"
actual_listing="$test_root/actual-listing-$fixture_number"
{
  for rel in $allowlisted_paths; do
    [ -n "$rel" ] || continue
    printf '%s\n' "$rel"
  done
  printf '%s\n' RELEASE_MANIFEST.sha256
} | LC_ALL=C sort >"$expected_listing"
(cd "$release_dir" && find . -type f | sed 's#^\./##') | LC_ALL=C sort >"$actual_listing"
if ! cmp -s "$expected_listing" "$actual_listing"; then
  printf 'clean stage: release must contain exactly the allowlisted files plus the manifest\n' >&2
  diff "$expected_listing" "$actual_listing" >&2 || true
  exit 1
fi
[ ! -e "$release_dir/.git" ] || { printf 'clean stage: .git must never be copied\n' >&2; exit 1; }
[ ! -e "$release_dir/.env" ] || { printf 'clean stage: .env must never be copied\n' >&2; exit 1; }
[ ! -e "$release_dir/scripts/secret.key" ] ||
  { printf 'clean stage: scripts/secret.key must never be copied\n' >&2; exit 1; }

# --- double staging is a true no-op reuse, not merely a successful re-copy --

run_stage "$sha" "$checkout"
expect_pass "second stage of the same commit" "$sha"
assert_current_target "second stage of the same commit" "$release_dir"

# --- rejects: a file *added* to an already-staged release, not merely a
# changed byte in one of the allowlisted files -----------------------------
# build_manifest alone only ever hashes allowlisted paths, so a wholly new
# path dropped into an already-staged release used to be invisible to it: a
# planted file like this one used to be tolerated as an untouched reuse (see
# git history for the fixture this replaced). list_release_files' file-set
# comparison in stage-runtime.sh is what now catches it.

: >"$release_dir/.sentinel-added"
run_stage "$sha" "$checkout"
expect_fail "file added to an already-staged release" \
  "release $sha already exists with different content"
assert_current_target "release with an added file leaves current unchanged" "$release_dir"
rm -f "$release_dir/.sentinel-added"

# --- rejects: non-40-hex / non-lowercase commit SHA -------------------------

new_fixture
run_stage "0123456789abcdef" "$checkout"
expect_fail "short SHA" "commit SHA must be 40 lowercase hex characters"

new_fixture
uppercase_sha="$(printf '%s' "$sha" | tr 'a-f' 'A-F')"
run_stage "$uppercase_sha" "$checkout"
expect_fail "uppercase SHA" "commit SHA must be 40 lowercase hex characters"

# --- rejects: usage and a non-directory checkout root ------------------------

new_fixture
run_stage "$sha"
expect_fail "missing checkout argument" \
  "Usage: stage-runtime.sh FULL_COMMIT_SHA CHECKOUT_ROOT"

new_fixture
run_stage "$sha" "$fixture/does-not-exist"
expect_fail "non-directory checkout root" "checkout root must be a directory"

# --- rejects: checkout HEAD does not match the requested SHA, current unchanged

new_fixture
run_stage "$sha" "$checkout"
expect_pass "seed release before mismatch case" "$sha"
seeded_release="$releases_dir/$sha"
first_char="$(printf '%s' "$sha" | cut -c1)"
if [ "$first_char" = a ]; then replacement_char=b; else replacement_char=a; fi
mismatched_sha="$replacement_char$(printf '%s' "$sha" | cut -c2-)"
[ "$mismatched_sha" != "$sha" ]
run_stage "$mismatched_sha" "$checkout"
expect_fail "checkout HEAD mismatch" "checkout HEAD does not match the requested commit SHA"
assert_current_target "checkout HEAD mismatch leaves current unchanged" "$seeded_release"

# --- rejects: a required Compose/script file missing from the checkout ------

new_fixture
rm "$checkout/compose.gpu.yml"
run_stage "$sha" "$checkout"
expect_fail "missing required file" "required release file is missing: compose.gpu.yml"
assert_current_target "missing required file leaves current unset" '<absent>'

# --- rejects: a world-writable source file -----------------------------------

new_fixture
chmod 0666 "$checkout/compose.yml"
run_stage "$sha" "$checkout"
expect_fail "world-writable source" "release source file must not be world-writable: compose.yml"
assert_current_target "world-writable source leaves current unset" '<absent>'

# --- rejects: a symlink standing in for a release source file ---------------

new_fixture
outside_target="$fixture/outside-wait-for-ollama.sh"
cp "$repo_root/infra/llm/scripts/wait-for-ollama.sh" "$outside_target"
rm "$checkout/scripts/wait-for-ollama.sh"
ln -s "$outside_target" "$checkout/scripts/wait-for-ollama.sh"
run_stage "$sha" "$checkout"
expect_fail "symlinked source file" \
  "release source path must not be a symlink: scripts/wait-for-ollama.sh"
assert_current_target "symlinked source file leaves current unset" '<absent>'

# --- rejects: a pre-existing release whose on-disk bytes no longer match ----
# (This is also the "mutate one copied byte" rejection: the release directory
# itself is mutated after a successful stage, not the checkout.)

new_fixture
run_stage "$sha" "$checkout"
expect_pass "seed release before tamper case" "$sha"
tampered_release="$releases_dir/$sha"
printf 'x' >>"$tampered_release/compose.yml"
run_stage "$sha" "$checkout"
expect_fail "tampered release bytes" "release $sha already exists with different content"
assert_current_target "tampered release leaves current unchanged" "$tampered_release"

# --- rollback: staging an old commit again reuses its untouched release ----

new_fixture
run_stage "$sha" "$checkout"
expect_pass "first release" "$sha"
first_sha="$sha"
first_release="$releases_dir/$first_sha"

printf '# release-2 marker\n' >>"$checkout/compose.tunnel.yml"
git -C "$checkout" add -A
git -C "$checkout" commit -q -m second
second_sha="$(git -C "$checkout" rev-parse HEAD)"
run_stage "$second_sha" "$checkout"
expect_pass "second release" "$second_sha"
second_release="$releases_dir/$second_sha"
assert_current_target "second release becomes current" "$second_release"
test -d "$first_release"

git -C "$checkout" checkout -q "$first_sha"
run_stage "$first_sha" "$checkout"
expect_pass "rollback restages the first commit" "$first_sha"
assert_current_target "rollback flips current back to the first release" "$first_release"
test -d "$second_release"

# --- rejects: missing releases/current-parent target directories -----------

new_fixture
rm -rf "$releases_dir"
run_stage "$sha" "$checkout"
expect_fail "missing releases directory" "releases directory does not exist: $releases_dir"

new_fixture
rm -rf "$current_parent"
run_stage "$sha" "$checkout"
expect_fail "missing current parent directory" \
  "current parent directory does not exist: $current_parent"

printf 'stage-runtime: %s fixtures passed\n' "$fixture_number"
