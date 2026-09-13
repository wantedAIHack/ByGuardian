#!/bin/sh
set -eu

# Stage an immutable, reviewed-commit release of the LLM runtime and flip the
# atomic `current` pointer to it.
#
#   stage-runtime.sh FULL_COMMIT_SHA CHECKOUT_ROOT
#
# CHECKOUT_ROOT must be a Git checkout whose HEAD is exactly FULL_COMMIT_SHA.
# Only an explicit allowlist of runtime files is ever copied -- never the
# checkout tree itself -- so a `.git` directory, `.env` file, key, token, or
# other credential that happens to live in the checkout can never reach a
# release. Copies land in a temporary sibling directory under the releases
# path, are recorded in a content manifest, synced, and only then atomically
# renamed into place; an existing release directory for the same commit is
# reused when (and only when) its manifest matches byte for byte, and a
# release directory is never deleted, which is what preserves prior SHAs for
# rollback. `current` is then replaced with a fresh symlink via one more
# atomic rename.
#
# NEXTVISIT_LLM_RELEASES_PATH and NEXTVISIT_LLM_CURRENT_PATH relocate the two
# target paths for testing, following the override convention verify-host.sh
# already uses for NEXTVISIT_LLM_RELEASES_PATH. Leave both unset on a real
# host.

usage='Usage: stage-runtime.sh FULL_COMMIT_SHA CHECKOUT_ROOT'

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

[ "$#" -eq 2 ] || fail "$usage"
sha=$1
checkout_root_arg=$2

# --- commit SHA -------------------------------------------------------------

sha_len=${#sha}
[ "$sha_len" -eq 40 ] || fail "commit SHA must be 40 lowercase hex characters"
case "$sha" in
  *[!0-9a-f]*) fail "commit SHA must be 40 lowercase hex characters" ;;
esac

# --- checkout root and HEAD --------------------------------------------------

[ -d "$checkout_root_arg" ] || fail "checkout root must be a directory"
checkout_root="$(CDPATH='' cd -- "$checkout_root_arg" && pwd)"

head_sha="$(cd "$checkout_root" && git rev-parse --verify HEAD 2>/dev/null)" ||
  fail "unable to resolve checkout HEAD via git"
[ "$head_sha" = "$sha" ] || fail "checkout HEAD does not match the requested commit SHA"

# --- target paths -------------------------------------------------------------

releases_path="${NEXTVISIT_LLM_RELEASES_PATH:-/opt/nextvisit/llm/releases}"
current_path="${NEXTVISIT_LLM_CURRENT_PATH:-/opt/nextvisit/llm/current}"

[ -d "$releases_path" ] || fail "releases directory does not exist: $releases_path"
releases_path="$(CDPATH='' cd -- "$releases_path" && pwd)"

current_dir="$(dirname -- "$current_path")"
[ -d "$current_dir" ] || fail "current parent directory does not exist: $current_dir"
current_dir="$(CDPATH='' cd -- "$current_dir" && pwd)"
current_path="$current_dir/$(basename -- "$current_path")"

# --- allowlisted release contents --------------------------------------------
#
# Exactly what compose.yml's `ollama` service needs to build (Dockerfile plus
# the two scripts it COPYs), the three Compose files the unit applies, and the
# one script the unit runs directly (its token preflight). Nothing else is
# ever read from the checkout.

allowlist='
Dockerfile
compose.yml
compose.gpu.yml
compose.tunnel.yml
scripts/wait-for-ollama.sh
scripts/ensure-model.sh
scripts/verify-tunnel-token-file.sh
'

mode_for() {
  case "$1" in
    scripts/*.sh) printf '0755\n' ;;
    *) printf '0644\n' ;;
  esac
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" | awk '{print $1}'
  else
    shasum -a 256 -- "$1" | awk '{print $1}'
  fi
}

# Numeric "other" mode digit without dereferencing symlinks, mirroring
# verify-tunnel-token-file.sh's dual GNU/BSD stat fallback.
other_mode_digit() {
  mode="$(stat -c '%a' -- "$1" 2>/dev/null || stat -f '%Lp' -- "$1")"
  printf '%s' "$mode" | sed 's/.*\(.\)$/\1/'
}

# Every path component under checkout_root must be a real entry, never a
# symlink: a symlinked component (or the file itself) is the one way a
# checkout could point a "release file" outside the checkout tree.
check_no_symlink_component() {
  rel=$1
  path="$checkout_root"
  remaining=$rel
  while [ -n "$remaining" ]; do
    case "$remaining" in
      */*)
        part=${remaining%%/*}
        remaining=${remaining#*/}
        ;;
      *)
        part=$remaining
        remaining=''
        ;;
    esac
    path="$path/$part"
    if [ -L "$path" ]; then
      fail "release source path must not be a symlink: $rel"
    fi
  done
}

validate_source() {
  rel=$1
  check_no_symlink_component "$rel"
  src="$checkout_root/$rel"
  [ -f "$src" ] || fail "required release file is missing: $rel"
  other_digit="$(other_mode_digit "$src")"
  case "$other_digit" in
    2 | 3 | 6 | 7) fail "release source file must not be world-writable: $rel" ;;
  esac
}

for rel in $allowlist; do
  [ -n "$rel" ] || continue
  validate_source "$rel"
done

# Hashes the allowlisted paths as they currently sit under `root` -- never a
# stored manifest file's claims about them. Reused both for the checkout (to
# decide what a fresh stage would copy) and, when a release directory for
# this SHA already exists, for the release directory itself: comparing
# freshly-recomputed hashes on both sides is what catches a byte mutated
# directly in an already-staged release, not only drift in the checkout.
build_manifest() {
  root=$1
  for rel in $allowlist; do
    [ -n "$rel" ] || continue
    src="$root/$rel"
    if [ -f "$src" ] && [ ! -L "$src" ]; then
      printf '%s  %s\n' "$(sha256_of "$src")" "$rel"
    else
      printf '%s  %s\n' 'MISSING' "$rel"
    fi
  done
}

manifest_content="$(build_manifest "$checkout_root")"
release_dir="$releases_path/$sha"

# --- stage (or reuse) the release --------------------------------------------

work_dir=''
tmp_link=''
cleanup() {
  [ -n "$work_dir" ] && rm -rf "$work_dir"
  [ -n "$tmp_link" ] && rm -f "$tmp_link"
  return 0
}
trap cleanup EXIT HUP INT TERM

if [ -e "$release_dir" ]; then
  [ -d "$release_dir" ] || fail "release path exists and is not a directory: $sha"
  existing_manifest_content="$(build_manifest "$release_dir")"
  [ "$existing_manifest_content" = "$manifest_content" ] ||
    fail "release $sha already exists with different content"
else
  work_dir="$(mktemp -d "$releases_path/.stage.XXXXXX")"
  chmod 0750 "$work_dir"
  mkdir -p "$work_dir/scripts"
  for rel in $allowlist; do
    [ -n "$rel" ] || continue
    dest="$work_dir/$rel"
    cp -- "$checkout_root/$rel" "$dest"
    chmod "$(mode_for "$rel")" "$dest"
  done
  printf '%s' "$manifest_content" >"$work_dir/RELEASE_MANIFEST.sha256"
  chmod 0644 "$work_dir/RELEASE_MANIFEST.sha256"
  # A per-file fsync isn't reachable from POSIX sh without extra tooling; the
  # POSIX `sync` utility is the portable best-effort durability barrier before
  # the rename below makes the release visible under its SHA.
  sync
  mv -- "$work_dir" "$release_dir"
  work_dir=''
fi

# --- flip current atomically -------------------------------------------------
#
# A plain `mv tmp_link current_path` is unsafe once `current_path` already
# exists as a symlink to a directory: both GNU and BSD mv resolve an existing
# destination with stat(2) (which follows symlinks) to decide whether to
# rename onto it or move *into* it, so a second deploy would silently nest
# the new symlink inside the previous release directory instead of replacing
# `current`. `-T` (GNU, --no-target-directory) and `-h` (BSD, do not follow a
# destination symlink) both suppress that directory-target heuristic and make
# mv perform a direct rename(2), which is what actually replaces a symlink
# atomically.
#
# Which flag this `mv` accepts is probed once, against a disposable pair of
# paths, rather than tried-and-caught against the real flip: `mv -T` failing
# on the real current_path can mean the flag is unsupported (BSD), but it can
# just as easily mean a genuine, non-recoverable failure -- EACCES, ENOSPC,
# current having become a non-empty directory -- and chaining that attempt
# into `mv -h` as a blind fallback would discard mv's real stderr and replace
# it with "invalid option -- 'h'", a portability complaint that was never the
# actual problem. Probing first means the real flip below runs exactly once,
# with its own stderr intact.
mv_no_target_flag() {
  probe_dir="$(mktemp -d)"
  : >"$probe_dir/a"
  if mv -T -- "$probe_dir/a" "$probe_dir/b" >/dev/null 2>&1; then
    rm -rf "$probe_dir"
    printf -- '-T\n'
    return 0
  fi
  : >"$probe_dir/a"
  if mv -h -- "$probe_dir/a" "$probe_dir/b" >/dev/null 2>&1; then
    rm -rf "$probe_dir"
    printf -- '-h\n'
    return 0
  fi
  rm -rf "$probe_dir"
  return 1
}

no_target_flag="$(mv_no_target_flag)" ||
  fail "this system's mv supports neither -T (GNU --no-target-directory) nor -h (BSD); cannot atomically replace current"

tmp_link="$current_dir/.current.$$"
rm -f "$tmp_link"
ln -s "$release_dir" "$tmp_link"
mv "$no_target_flag" -- "$tmp_link" "$current_path"
tmp_link=''

printf 'staged release %s\n' "$sha"
