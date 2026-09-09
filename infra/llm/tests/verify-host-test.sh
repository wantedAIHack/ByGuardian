#!/usr/bin/env bash
set -euo pipefail

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM
mkdir -p "$test_root/bin"

cat >"$test_root/bin/docker" <<'FAKE'
#!/bin/sh
set -eu
case "$*" in
  info) exit 0 ;;
  "compose version --short") printf '%s\n' '2.24.4' ;;
  *) exit 2 ;;
esac
FAKE

cat >"$test_root/bin/curl" <<'FAKE'
#!/bin/sh
exit 0
FAKE

cat >"$test_root/bin/jq" <<'FAKE'
#!/bin/sh
exit 0
FAKE

cat >"$test_root/bin/df" <<'FAKE'
#!/bin/sh
printf '%s\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on'
printf '/dev/fake 100 1 %s 1%% /fake\n' "${FAKE_AVAILABLE_KIB:-unknown}"
FAKE

chmod +x "$test_root/bin/docker" "$test_root/bin/curl" \
  "$test_root/bin/jq" "$test_root/bin/df"

set +e
output="$(
  PATH="$test_root/bin:$PATH" \
    NEXTVISIT_LLM_DATA_PATH=. \
    "$script_dir/../scripts/verify-host.sh" cpu 2>&1
)"
status=$?
set -e

if [ "$status" -eq 0 ]; then
  printf '%s\n' "verify-host accepted non-numeric disk availability" >&2
  exit 1
fi
test "$output" = "unable to determine available disk space"

output="$(
  PATH="$test_root/bin:$PATH" \
    NEXTVISIT_LLM_DATA_PATH=. \
    FAKE_AVAILABLE_KIB=20971520 \
    "$script_dir/../scripts/verify-host.sh" cpu
)"
test "$output" = "host verification passed for cpu mode"

printf '%s\n' "verify-host tests passed"
