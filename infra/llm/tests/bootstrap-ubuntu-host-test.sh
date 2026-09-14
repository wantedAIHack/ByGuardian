#!/bin/sh
set -eu

# Fake-root harness for infra/llm/scripts/bootstrap-ubuntu-host.sh.
#
# The real script mutates an Ubuntu host as root. Nothing here touches the
# development machine: every privileged command (apt, install, useradd,
# systemctl, nvidia-*, docker, dpkg, id, uname, df) is a fake executable on a
# prepended PATH that logs its own argument vector, and every absolute path the
# script writes is relocated under NEXTVISIT_BOOTSTRAP_ROOT. The fakes are the
# only thing standing between this test and a real `apt-get install`, so each
# one exits non-zero on an argument shape the script is not supposed to use.

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
bootstrap="$repo_root/infra/llm/scripts/bootstrap-ubuntu-host.sh"

test_root="$(mktemp -d)"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM
bin="$test_root/bin"
mkdir -p "$bin"

token_sentinel="do-not-print-this-fake-bootstrap-tunnel-token-sentinel"

candidates="$test_root/candidates"
cat >"$candidates" <<'CANDIDATES'
containerd.io 1.7.27-1
docker-ce 5:28.3.2-1~ubuntu.24.04~noble
docker-ce-cli 5:28.3.2-1~ubuntu.24.04~noble
docker-compose-plugin 2.39.1-1~ubuntu.24.04~noble
libnvidia-container1 1.17.8-1
nvidia-container-toolkit 1.17.8-1
CANDIDATES

# --- fake privileged commands ---------------------------------------------

cat >"$bin/id" <<'FAKE'
#!/bin/sh
set -eu
case "${1:-}" in
  -u)
    if [ "$#" -ge 2 ]; then printf '4000\n'; else printf '%s\n' "${FAKE_UID:-0}"; fi
    ;;
  *) exit 2 ;;
esac
FAKE

cat >"$bin/uname" <<'FAKE'
#!/bin/sh
set -eu
case "${1:-}" in
  -m) printf '%s\n' "${FAKE_ARCH:-x86_64}" ;;
  -s) printf 'Linux\n' ;;
  *) exit 2 ;;
esac
FAKE

cat >"$bin/nvidia-smi" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "nvidia-smi $*" >>"$FAKE_LOG"
exit "${FAKE_NVIDIA_SMI_STATUS:-0}"
FAKE

cat >"$bin/df" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' 'Filesystem 1024-blocks Used Available Capacity Mounted on'
printf '/dev/fake 100 1 %s 1%% /fake\n' "${FAKE_AVAILABLE_KIB:-41943040}"
FAKE

cat >"$bin/dpkg" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "dpkg $*" >>"$FAKE_LOG"
case "${1:-}" in
  --print-architecture) printf 'amd64\n' ;;
  *) exit 2 ;;
esac
FAKE

cat >"$bin/dpkg-query" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "dpkg-query $*" >>"$FAKE_LOG"
shift 2
for pkg in "$@"; do
  version="$(awk -v p="$pkg" '$1 == p {print $2}' "$FAKE_CANDIDATES")"
  test -n "$version"
  printf '%s=%s\n' "$pkg" "$version"
done
FAKE

cat >"$bin/curl" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "curl $*" >>"$FAKE_LOG"
url=""
for arg in "$@"; do url="$arg"; done
case "$url" in
  https://download.docker.com/linux/ubuntu/gpg)
    printf '%s\n' '-----BEGIN PGP PUBLIC KEY BLOCK-----'
    printf '%s\n' 'ZmFrZS1kb2NrZXItcmVwb3NpdG9yeS1rZXk='
    printf '%s\n' '-----END PGP PUBLIC KEY BLOCK-----'
    ;;
  https://nvidia.github.io/libnvidia-container/gpgkey)
    case "${FAKE_CURL_NVIDIA_KEY:-ok}" in
      fail) exit 22 ;;
      empty) : ;;
      *)
        printf '%s\n' '-----BEGIN PGP PUBLIC KEY BLOCK-----'
        printf '%s\n' 'ZmFrZS1udmlkaWEtcmVwb3NpdG9yeS1rZXk='
        printf '%s\n' '-----END PGP PUBLIC KEY BLOCK-----'
        ;;
    esac
    ;;
  https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list)
    case "${FAKE_CURL_NVIDIA_LIST:-ok}" in
      fail) exit 22 ;;
      empty) : ;;
      *) printf '%s\n' 'deb https://nvidia.github.io/libnvidia-container/stable/deb/$(ARCH) /' ;;
    esac
    ;;
  *) exit 22 ;;
esac
FAKE

cat >"$bin/gpg" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "gpg $*" >>"$FAKE_LOG"
case "${1:-}" in
  --dearmor) printf 'FAKE-DEARMORED-KEYRING\n'; cat ;;
  *) exit 2 ;;
esac
FAKE

cat >"$bin/apt-get" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "apt-get $*" >>"$FAKE_LOG"
for arg in "$@"; do
  case "$arg" in
    update|install|-y|--yes|--no-install-recommends|*=*) ;;
    *) printf 'fake apt-get refuses unexpected argument: %s\n' "$arg" >&2; exit 100 ;;
  esac
done
FAKE

cat >"$bin/apt-cache" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "apt-cache $*" >>"$FAKE_LOG"
test "${1:-}" = policy
pkg="$2"
version="$(awk -v p="$pkg" '$1 == p {print $2}' "$FAKE_CANDIDATES")"
printf '%s:\n' "$pkg"
printf '  Installed: (none)\n'
if [ -n "$version" ]; then
  printf '  Candidate: %s\n' "$version"
else
  printf '  Candidate: (none)\n'
fi
FAKE

# `install` is the only writer the script uses for owned files, so the fake
# records the requested owner/group/mode separately: this development machine
# cannot chown to root, and the ownership log is what the assertions read.
cat >"$bin/install" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "install $*" >>"$FAKE_LOG"
directory=no
owner=""
group=""
mode=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -d) directory=yes; shift ;;
    -o) owner="$2"; shift 2 ;;
    -g) group="$2"; shift 2 ;;
    -m) mode="$2"; shift 2 ;;
    --) shift; break ;;
    -*) printf 'fake install refuses unexpected option: %s\n' "$1" >&2; exit 100 ;;
    *) break ;;
  esac
done
test -n "$owner"
test -n "$group"
test -n "$mode"
if [ "$directory" = yes ]; then
  for target in "$@"; do
    mkdir -p "$target"
    chmod "$mode" "$target"
    printf 'dir %s %s %s %s\n' "$target" "$owner" "$group" "$mode" >>"$FAKE_OWNERSHIP"
  done
else
  test "$#" -eq 2
  rm -f "$2"
  cp "$1" "$2"
  chmod "$mode" "$2"
  printf 'file %s %s %s %s\n' "$2" "$owner" "$group" "$mode" >>"$FAKE_OWNERSHIP"
fi
FAKE

# Masking really is a symlink to /dev/null under /etc/systemd/system, so the
# fake models it that way and the masked state becomes part of the compared
# filesystem state instead of a side channel.
cat >"$bin/systemctl" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "systemctl $*" >>"$FAKE_LOG"
unit_dir="${NEXTVISIT_BOOTSTRAP_ROOT}/etc/systemd/system"
case "${1:-}" in
  mask)
    shift
    mkdir -p "$unit_dir"
    for unit in "$@"; do ln -sf /dev/null "$unit_dir/$unit"; done
    ;;
  is-enabled)
    if [ -L "$unit_dir/$2" ]; then printf 'masked\n'; exit 1; fi
    printf 'static\n'
    ;;
  restart) ;;
  *) exit 2 ;;
esac
FAKE

cat >"$bin/groupadd" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "groupadd $*" >>"$FAKE_LOG"
test "$1" = --system
test "$2" = --gid
printf '%s:x:%s:\n' "$4" "$3" >>"${NEXTVISIT_BOOTSTRAP_ROOT}/etc/group"
FAKE

cat >"$bin/useradd" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "useradd $*" >>"$FAKE_LOG"
name=""
for arg in "$@"; do name="$arg"; done
printf '%s:x:4000:4000::/home/%s:/bin/bash\n' "$name" "$name" \
  >>"${NEXTVISIT_BOOTSTRAP_ROOT}/etc/passwd"
printf '%s:!:20000:0:99999:7:::\n' "$name" \
  >>"${NEXTVISIT_BOOTSTRAP_ROOT}/etc/shadow"
FAKE

cat >"$bin/usermod" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "usermod $*" >>"$FAKE_LOG"
group_file="${NEXTVISIT_BOOTSTRAP_ROOT}/etc/group"
shadow_file="${NEXTVISIT_BOOTSTRAP_ROOT}/etc/shadow"
case "${1:-}" in
  --lock)
    name="$2"
    awk -v n="$name" -F: 'BEGIN {OFS=":"} $1 == n {$2 = "!"} {print}' \
      "$shadow_file" >"$shadow_file.new"
    mv "$shadow_file.new" "$shadow_file"
    ;;
  --append)
    test "$2" = --groups
    target_group="$3"
    name="$4"
    awk -v g="$target_group" -v n="$name" -F: 'BEGIN {OFS=":"}
      $1 == g { $4 = ($4 == "" ? n : $4 "," n) }
      { print }' "$group_file" >"$group_file.new"
    mv "$group_file.new" "$group_file"
    ;;
  *) exit 2 ;;
esac
FAKE

cat >"$bin/passwd" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "passwd $*" >>"$FAKE_LOG"
test "${1:-}" = -S
awk -v n="$2" -F: '$1 == n {
  state = "P"
  if ($2 == "" ) state = "NP"
  else if ($2 ~ /^!/ || $2 == "*") state = "L"
  printf "%s %s 01/01/2026 0 99999 7 -1\n", n, state
}' "${NEXTVISIT_BOOTSTRAP_ROOT}/etc/shadow"
FAKE

cat >"$bin/nvidia-ctk" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "nvidia-ctk $*" >>"$FAKE_LOG"
test "$1" = runtime
test "$2" = configure
test "$3" = --runtime=docker
# Models the real daemon.json rewrite: docker only reports the nvidia
# runtime after this has run (and the harness's systemctl fake takes care of
# the "restart" half of catching up on a real host).
: >"${NEXTVISIT_BOOTSTRAP_ROOT}/var/lib/docker-nvidia-configured"
FAKE

cat >"$bin/docker" <<'FAKE'
#!/bin/sh
set -eu
printf '%s\n' "docker $*" >>"$FAKE_LOG"
runtimes="${FAKE_DOCKER_RUNTIMES:-}"
if [ -z "$runtimes" ]; then
  if [ -f "${NEXTVISIT_BOOTSTRAP_ROOT}/var/lib/docker-nvidia-configured" ]; then
    runtimes='{"nvidia":{},"runc":{}}'
  else
    runtimes='{"runc":{}}'
  fi
fi
case "$*" in
  "info --format {{json .Runtimes}}") printf '%s\n' "$runtimes" ;;
  *) exit 2 ;;
esac
FAKE

chmod +x "$bin"/*

# --- helpers ---------------------------------------------------------------

fake_root_banner='NEXTVISIT_BOOTSTRAP_ROOT is set: paths are relocated under a fake root; this must never happen on a real host'

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" | awk '{print $1}'
  else
    shasum -a 256 -- "$1" | awk '{print $1}'
  fi
}

mode_of() {
  if out="$(stat -c '%a' -- "$1" 2>/dev/null)"; then
    printf '%s\n' "$out"
    return 0
  fi
  stat -f '%Lp' -- "$1"
}

# A state manifest is content-and-permission based on purpose: re-running an
# idempotent bootstrap may legitimately rewrite a file with identical bytes, so
# comparing mtimes or inodes would report false drift while comparing only the
# path list would miss real content changes.
state_manifest() {
  manifest_root=$1
  manifest_out=$2
  (
    cd "$manifest_root" || exit 1
    find . \( -type f -o -type d -o -type l \) | LC_ALL=C sort | while IFS= read -r entry; do
      if [ -L "$entry" ]; then
        printf 'link %s -> %s\n' "$entry" "$(readlink "$entry")"
      elif [ -d "$entry" ]; then
        printf 'dir  %s %s\n' "$entry" "$(mode_of "$entry")"
      else
        printf 'file %s %s %s\n' "$entry" "$(mode_of "$entry")" "$(sha256_of "$entry")"
      fi
    done
  ) >"$manifest_out"
}

host_number=0
new_host() {
  # POSIX leaves the persistence of a variable-assignment prefix on a shell
  # FUNCTION call unspecified, and this repo's `sh` can keep it set for the rest
  # of the script. Reset every knob explicitly so one case's rejection reason
  # can never leak into the next case and make it pass for the wrong reason.
  unset FAKE_UID FAKE_ARCH FAKE_NVIDIA_SMI_STATUS FAKE_AVAILABLE_KIB \
    FAKE_DOCKER_RUNTIMES FAKE_CURL_NVIDIA_KEY FAKE_CURL_NVIDIA_LIST
  host_number=$((host_number + 1))
  host="$test_root/host-$host_number"
  mkdir -p "$host/etc/apt/sources.list.d" "$host/etc/systemd" \
    "$host/var/lib/docker" "$host/sys/class/power_supply/AC0" \
    "$host/sys/class/power_supply/BAT0"
  cat >"$host/etc/os-release" <<'OSRELEASE'
PRETTY_NAME="Ubuntu 24.04.3 LTS"
NAME="Ubuntu"
ID=ubuntu
VERSION_ID="24.04"
VERSION_CODENAME=noble
UBUNTU_CODENAME=noble
OSRELEASE
  printf 'Mains\n' >"$host/sys/class/power_supply/AC0/type"
  printf '1\n' >"$host/sys/class/power_supply/AC0/online"
  printf 'Battery\n' >"$host/sys/class/power_supply/BAT0/type"
  printf 'root:x:0:\n' >"$host/etc/passwd"
  printf 'root:x:0:\ndocker:x:998:\n' >"$host/etc/group"
  printf 'root:!:20000:0:99999:7:::\n' >"$host/etc/shadow"
  printf '[Login]\n#NAutoVTs=6\n' >"$host/etc/systemd/logind.conf"
  # IMPORTANT 3: the script refuses NEXTVISIT_BOOTSTRAP_ROOT unless the
  # relocated tree carries this marker; the harness is the only thing
  # allowed to create it.
  : >"$host/.nextvisit-fake-root"
  log_number=0
}

run_bootstrap() {
  log_number=$((log_number + 1))
  log="$test_root/log-$host_number-$log_number"
  ownership="$test_root/ownership-$host_number-$log_number"
  : >"$log"
  : >"$ownership"
  set +e
  bootstrap_output_raw="$(
    PATH="$bin:$PATH" \
      NEXTVISIT_BOOTSTRAP_ROOT="$host" \
      FAKE_LOG="$log" \
      FAKE_OWNERSHIP="$ownership" \
      FAKE_CANDIDATES="$candidates" \
      "$bootstrap" "$@" 2>&1
  )"
  bootstrap_status=$?
  set -e
  # Every case in this harness sets NEXTVISIT_BOOTSTRAP_ROOT, so the
  # IMPORTANT-3 banner is on every line of raw output. Strip it centrally so
  # the exact-message assertions below keep testing what they name; the
  # banner itself is asserted separately, from bootstrap_output_raw.
  bootstrap_output="$(printf '%s\n' "$bootstrap_output_raw" | grep -Fxv "$fake_root_banner" || true)"
}

expect_pass() {
  name=$1
  expected_last_line=$2
  if [ "$bootstrap_status" -ne 0 ]; then
    printf '%s: expected success but bootstrap failed (%s): %s\n' \
      "$name" "$bootstrap_status" "$bootstrap_output" >&2
    exit 1
  fi
  actual_last_line="$(printf '%s\n' "$bootstrap_output" | tail -n 1)"
  if [ "$actual_last_line" != "$expected_last_line" ]; then
    printf '%s: expected final line "%s" but got "%s"\n' \
      "$name" "$expected_last_line" "$actual_last_line" >&2
    exit 1
  fi
  assert_output_secret_free "$name"
}

# Asserting the exact rejection text (not merely a non-zero exit) is what makes
# each case a test of its own named rule: a fake that died on an unexpected
# argument, or a preflight check firing out of order, would otherwise still exit
# non-zero and print green while proving nothing.
expect_fail() {
  name=$1
  expected_message=$2
  if [ "$bootstrap_status" -eq 0 ]; then
    printf '%s: expected bootstrap to refuse but it succeeded\n' "$name" >&2
    exit 1
  fi
  actual_last_line="$(printf '%s\n' "$bootstrap_output" | tail -n 1)"
  if [ "$actual_last_line" != "$expected_message" ]; then
    printf '%s: expected rejection "%s" but got "%s"\n' \
      "$name" "$expected_message" "$bootstrap_output" >&2
    exit 1
  fi
  assert_output_secret_free "$name"
}

# For refusals that must happen before the script does any work at all, the
# rejection is the ONLY thing written: any progress output would mean the gate
# fired too late.
expect_fail_before_any_work() {
  expect_fail "$1" "$2"
  if [ "$bootstrap_output" != "$2" ]; then
    printf '%s: refusal must come before any other output but got "%s"\n' \
      "$1" "$bootstrap_output" >&2
    exit 1
  fi
}

assert_output_secret_free() {
  case "$bootstrap_output" in
    *"$token_sentinel"*)
      printf '%s: bootstrap output must never contain token content\n' "$1" >&2
      exit 1
      ;;
  esac
}

assert_log_has() {
  if ! grep -Fq -- "$2" "$log"; then
    printf '%s: expected command log to contain "%s"\n' "$1" "$2" >&2
    exit 1
  fi
}

assert_log_lacks() {
  if grep -Fq -- "$2" "$log"; then
    printf '%s: command log must not contain "%s"\n' "$1" "$2" >&2
    exit 1
  fi
}

# --- reusable policy assertions (also exercised by the mutation tests) ------

assert_no_package_mutation() {
  if grep -Eq '^apt-get (.* )?install( |$)' "$1"; then
    printf 'command log mutated packages: %s\n' "$1" >&2
    return 1
  fi
}

assert_no_distribution_upgrade() {
  if grep -Eq '^apt-get (.* )?(upgrade|dist-upgrade|full-upgrade)( |$)' "$1"; then
    printf 'command log ran a distribution upgrade: %s\n' "$1" >&2
    return 1
  fi
}

assert_no_driver_package() {
  if grep -Eq 'nvidia-driver|linux-headers|ubuntu-drivers|nvidia-dkms' "$1"; then
    printf 'command log touched an NVIDIA driver package: %s\n' "$1" >&2
    return 1
  fi
}

assert_no_secret_argument() {
  if grep -Eq '(^| )--?(password|passwd|token|secret)([ =]|$)' "$1"; then
    printf 'command log carried a secret-shaped argument: %s\n' "$1" >&2
    return 1
  fi
  if grep -Fq "$token_sentinel" "$1"; then
    printf 'command log carried token content: %s\n' "$1" >&2
    return 1
  fi
}

assert_no_duplicate_accounts() {
  group_file="$1/etc/group"
  passwd_file="$1/etc/passwd"
  for entry in nextvisit-cloudflared docker; do
    count="$(grep -c "^$entry:" "$group_file" || true)"
    if [ "$count" != 1 ]; then
      printf 'group %s appears %s times\n' "$entry" "$count" >&2
      return 1
    fi
    members="$(awk -v g="$entry" -F: '$1 == g {print $4}' "$group_file")"
    occurrences="$(printf '%s\n' "$members" | tr ',' '\n' | grep -c '^nextvisit-runner$' || true)"
    if [ "$occurrences" != 1 ]; then
      printf 'nextvisit-runner appears %s times in group %s\n' "$occurrences" "$entry" >&2
      return 1
    fi
  done
  user_count="$(grep -c '^nextvisit-runner:' "$passwd_file" || true)"
  if [ "$user_count" != 1 ]; then
    printf 'user nextvisit-runner appears %s times\n' "$user_count" >&2
    return 1
  fi
}

assert_no_duplicate_repositories() {
  for list in "$1"/etc/apt/sources.list.d/*.list; do
    [ -f "$list" ] || continue
    total="$(grep -c '^deb ' "$list" || true)"
    unique="$(grep '^deb ' "$list" | LC_ALL=C sort -u | wc -l | tr -d ' ')"
    if [ "$total" != "$unique" ]; then
      printf 'duplicate repository lines in %s\n' "$list" >&2
      return 1
    fi
  done
  count="$(find "$1/etc/apt/sources.list.d" -name '*.list' | wc -l | tr -d ' ')"
  if [ "$count" != 2 ]; then
    printf 'expected exactly 2 repository lists but found %s\n' "$count" >&2
    return 1
  fi
}

assert_single_logind_dropin() {
  count="$(find "$1/etc/systemd/logind.conf.d" -type f | wc -l | tr -d ' ')"
  if [ "$count" != 1 ]; then
    printf 'expected exactly 1 logind drop-in but found %s\n' "$count" >&2
    return 1
  fi
}

# --- Step 2/3: preflight refusals before any mutation ----------------------

new_host
FAKE_UID=1000 run_bootstrap prepare
expect_fail_before_any_work "non-root invocation" "bootstrap must run as root"

new_host
printf 'ID=debian\nVERSION_ID="12"\nVERSION_CODENAME=bookworm\n' >"$host/etc/os-release"
run_bootstrap prepare
expect_fail_before_any_work "non-Ubuntu distribution" "bootstrap requires Ubuntu 24.04"

new_host
printf 'ID=ubuntu\nVERSION_ID="22.04"\nVERSION_CODENAME=jammy\n' >"$host/etc/os-release"
run_bootstrap prepare
expect_fail_before_any_work "wrong Ubuntu release" "bootstrap requires Ubuntu 24.04"

new_host
FAKE_ARCH=aarch64 run_bootstrap prepare
expect_fail_before_any_work "non-x86_64 hardware" "bootstrap requires x86_64 hardware"

new_host
FAKE_NVIDIA_SMI_STATUS=9 run_bootstrap prepare
expect_fail_before_any_work "broken NVIDIA driver" \
  "existing NVIDIA driver is not working; bootstrap never installs a driver"

new_host
printf '0\n' >"$host/sys/class/power_supply/AC0/online"
run_bootstrap prepare
expect_fail_before_any_work "battery power" "bootstrap requires AC power"

new_host
FAKE_AVAILABLE_KIB=1048576 run_bootstrap prepare
expect_fail_before_any_work "insufficient disk" "bootstrap requires at least 20 GiB of free disk"

new_host
run_bootstrap
expect_fail_before_any_work "missing phase argument" \
  "Usage: bootstrap-ubuntu-host.sh prepare|apply APPROVED_LOCK_SHA256"

new_host
run_bootstrap bootstrap-everything
expect_fail_before_any_work "unknown phase" \
  "Usage: bootstrap-ubuntu-host.sh prepare|apply APPROVED_LOCK_SHA256"

# --- IMPORTANT 3: NEXTVISIT_BOOTSTRAP_ROOT requires the fake-root marker and
# always announces itself, so it can never quietly satisfy every gate from a
# fake tree while really mutating the host. --------------------------------

new_host
run_bootstrap prepare
case "$bootstrap_output_raw" in
  *"$fake_root_banner"*) ;;
  *)
    printf 'bootstrap must print the fake-root banner whenever NEXTVISIT_BOOTSTRAP_ROOT is set: %s\n' \
      "$bootstrap_output_raw" >&2
    exit 1
    ;;
esac

new_host
rm -f "$host/.nextvisit-fake-root"
run_bootstrap prepare
expect_fail_before_any_work "missing fake-root marker" \
  "NEXTVISIT_BOOTSTRAP_ROOT is set without a .nextvisit-fake-root marker; refusing to run"

# --- Step 3: prepare touches only the signed repository files and the lock --

new_host
prepare_host="$host"
before="$test_root/files-before"
after="$test_root/files-after"
(cd "$prepare_host" && find . -type f | LC_ALL=C sort) >"$before"
baseline="$test_root/baseline-manifest"
state_manifest "$prepare_host" "$baseline"

run_bootstrap prepare
expect_pass "prepare" "ubuntu host bootstrap prepare completed"
prepare_log="$log"

(cd "$prepare_host" && find . -type f | LC_ALL=C sort) >"$after"
created="$(LC_ALL=C comm -13 "$before" "$after")"
expected_created="./etc/apt/keyrings/docker.asc
./etc/apt/sources.list.d/docker.list
./etc/apt/sources.list.d/nvidia-container-toolkit.list
./etc/nextvisit/ubuntu-packages.lock
./usr/share/keyrings/nvidia-container-toolkit-keyring.gpg"
if [ "$created" != "$expected_created" ]; then
  printf 'prepare created an unexpected file set:\n%s\n' "$created" >&2
  exit 1
fi

# Nothing that existed before prepare may have changed.
while IFS= read -r line; do
  case "$line" in
    "file "*) ;;
    *) continue ;;
  esac
  path="$(printf '%s\n' "$line" | awk '{print $2}')"
  digest="$(printf '%s\n' "$line" | awk '{print $4}')"
  if [ "$(sha256_of "$prepare_host/$path")" != "$digest" ]; then
    printf 'prepare modified a pre-existing file: %s\n' "$path" >&2
    exit 1
  fi
done <"$baseline"

lock="$prepare_host/etc/nextvisit/ubuntu-packages.lock"
test -f "$lock"
if [ "$(mode_of "$lock")" != "444" ]; then
  printf 'lock file mode must be 0444 but is %s\n' "$(mode_of "$lock")" >&2
  exit 1
fi
grep -Fq 'dir '"$prepare_host"'/etc/nextvisit root root 0755' "$ownership"
grep -Fq 'file '"$lock"' root root 0444' "$ownership"

# Sorted package lines, one per resolved package, plus source fingerprints.
package_lines="$(grep '^package ' "$lock")"
if [ "$package_lines" != "$(printf '%s\n' "$package_lines" | LC_ALL=C sort)" ]; then
  printf 'lock package lines are not sorted\n' >&2
  exit 1
fi
source_lines="$(grep '^source ' "$lock")"
if [ "$source_lines" != "$(printf '%s\n' "$source_lines" | LC_ALL=C sort)" ]; then
  printf 'lock source lines are not sorted\n' >&2
  exit 1
fi
while read -r pkg version; do
  grep -Fqx "package $pkg=$version" "$lock" || {
    printf 'lock is missing package %s=%s\n' "$pkg" "$version" >&2
    exit 1
  }
done <"$candidates"
test "$(grep -c '^package ' "$lock")" -eq 6
test "$(grep -c '^source ' "$lock")" -eq 4
for signed in /etc/apt/keyrings/docker.asc \
  /etc/apt/sources.list.d/docker.list \
  /etc/apt/sources.list.d/nvidia-container-toolkit.list \
  /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg; do
  grep -Fq "source $signed sha256:" "$lock" || {
    printf 'lock is missing a source fingerprint for %s\n' "$signed" >&2
    exit 1
  }
done

lock_sha="$(sha256_of "$lock")"
printf '%s\n' "$bootstrap_output" | grep -Fqx "ubuntu-packages.lock sha256: $lock_sha" || {
  printf 'prepare must print the lock SHA-256 it wrote\n' >&2
  exit 1
}
printf '%s\n' "$bootstrap_output" | grep -Fqx "package docker-ce=5:28.3.2-1~ubuntu.24.04~noble" || {
  printf 'prepare must print the resolved non-secret package versions\n' >&2
  exit 1
}

# The signed repository list must pin the keyring, not trust the archive blindly.
grep -Fqx 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu noble stable' \
  "$prepare_host/etc/apt/sources.list.d/docker.list"
grep -Fq 'signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg' \
  "$prepare_host/etc/apt/sources.list.d/nvidia-container-toolkit.list"

assert_no_package_mutation "$prepare_log"
assert_no_distribution_upgrade "$prepare_log"
assert_no_driver_package "$prepare_log"
assert_no_secret_argument "$prepare_log"
assert_log_has "prepare" "apt-get update"
assert_log_lacks "prepare" "useradd"
assert_log_lacks "prepare" "groupadd"
assert_log_lacks "prepare" "systemctl"

# prepare is itself idempotent: a second prepare must reproduce the same lock.
prepare_manifest="$test_root/prepare-manifest-1"
state_manifest "$prepare_host" "$prepare_manifest"
run_bootstrap prepare
expect_pass "second prepare" "ubuntu host bootstrap prepare completed"
prepare_manifest_2="$test_root/prepare-manifest-2"
state_manifest "$prepare_host" "$prepare_manifest_2"
if ! cmp -s "$prepare_manifest" "$prepare_manifest_2"; then
  printf 'second prepare changed host state:\n' >&2
  diff "$prepare_manifest" "$prepare_manifest_2" >&2 || true
  exit 1
fi

# --- IMPORTANT 2: a failed or empty NVIDIA curl must never leave an empty
# trusted keyring/list installed. Each case must never even reach
# install_file for the NVIDIA key/list, so the created-file set stays exactly
# what a completely fresh host started with (no keyring, no list). ----------

new_host
nvidia_key_fail_host="$host"
FAKE_CURL_NVIDIA_KEY=fail run_bootstrap prepare
expect_fail "NVIDIA key download failure" "download of the NVIDIA repository key failed"
[ ! -e "$nvidia_key_fail_host/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg" ] || {
  printf 'a failed NVIDIA key download must never produce an installed keyring\n' >&2
  exit 1
}

new_host
nvidia_key_empty_host="$host"
FAKE_CURL_NVIDIA_KEY=empty run_bootstrap prepare
expect_fail "NVIDIA key download empty" "download of the NVIDIA repository key was empty"
[ ! -e "$nvidia_key_empty_host/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg" ] || {
  printf 'an empty NVIDIA key download must never produce an installed keyring\n' >&2
  exit 1
}

new_host
nvidia_list_fail_host="$host"
FAKE_CURL_NVIDIA_LIST=fail run_bootstrap prepare
expect_fail "NVIDIA list download failure" "download of the NVIDIA repository list failed"
[ ! -e "$nvidia_list_fail_host/etc/apt/sources.list.d/nvidia-container-toolkit.list" ] || {
  printf 'a failed NVIDIA list download must never produce an installed list\n' >&2
  exit 1
}

new_host
nvidia_list_empty_host="$host"
FAKE_CURL_NVIDIA_LIST=empty run_bootstrap prepare
expect_fail "NVIDIA list download empty" "download of the NVIDIA repository list was empty"
[ ! -e "$nvidia_list_empty_host/etc/apt/sources.list.d/nvidia-container-toolkit.list" ] || {
  printf 'an empty NVIDIA list download must never produce an installed list\n' >&2
  exit 1
}

# --- Step 3: apply gates on the approved lock SHA before mutating packages --

new_host
run_bootstrap apply
expect_fail_before_any_work "apply without an approved SHA" \
  "Usage: bootstrap-ubuntu-host.sh prepare|apply APPROVED_LOCK_SHA256"

new_host
run_bootstrap apply 0000000000000000000000000000000000000000000000000000000000000000
expect_fail_before_any_work "apply before prepare" \
  "run bootstrap-ubuntu-host.sh prepare before apply"
assert_no_package_mutation "$log"

new_host
gate_host="$host"
run_bootstrap prepare
expect_pass "prepare for the SHA gate" "ubuntu host bootstrap prepare completed"
gate_lock_sha="$(sha256_of "$gate_host/etc/nextvisit/ubuntu-packages.lock")"

run_bootstrap apply 1111111111111111111111111111111111111111111111111111111111111111
expect_fail_before_any_work "apply with a wrong approved SHA" \
  "approved lock SHA-256 does not match /etc/nextvisit/ubuntu-packages.lock"
assert_no_package_mutation "$log"
assert_log_lacks "wrong SHA" "useradd"

# Drift between prepare and apply must be refused even with a matching SHA.
drift_candidates="$test_root/candidates-drifted"
sed 's/^nvidia-container-toolkit .*/nvidia-container-toolkit 1.17.9-1/' \
  "$candidates" >"$drift_candidates"
saved_candidates="$candidates"
candidates="$drift_candidates"
run_bootstrap apply "$gate_lock_sha"
expect_fail "candidate drift after prepare" \
  "package candidate versions drifted since prepare; rerun prepare"
assert_no_package_mutation "$log"
candidates="$saved_candidates"

# IMPORTANT 2: the lock records source fingerprints but nothing checked them
# against disk before apt-get update runs against those archives. If a signed
# repository file changes between prepare and apply -- even with a matching
# lock SHA and no version drift -- apply must refuse rather than trust
# whatever archive the mutated file now points at.
new_host
source_host="$host"
run_bootstrap prepare
expect_pass "prepare before the source-fingerprint check" \
  "ubuntu host bootstrap prepare completed"
source_lock_sha="$(sha256_of "$source_host/etc/nextvisit/ubuntu-packages.lock")"
printf 'deb [arch=amd64 signed-by=/etc/apt/keyrings/docker.asc] https://mirror.invalid/ubuntu noble stable\n' \
  >"$source_host/etc/apt/sources.list.d/docker.list"
run_bootstrap apply "$source_lock_sha"
expect_fail "signed repository file changed after prepare" \
  "signed repository files changed since prepare; rerun prepare"
assert_no_package_mutation "$log"
assert_log_lacks "signed repository file changed after prepare" "useradd"

# A foreign owner of GID 65532 must be reported, never silently reused.
new_host
gid_host="$host"
printf 'legacy-team:x:65532:\n' >>"$gid_host/etc/group"
run_bootstrap prepare
expect_pass "prepare before the GID conflict" "ubuntu host bootstrap prepare completed"
gid_lock_sha="$(sha256_of "$gid_host/etc/nextvisit/ubuntu-packages.lock")"
run_bootstrap apply "$gid_lock_sha"
expect_fail "GID 65532 owned by another group" \
  "GID 65532 already belongs to group legacy-team"

# --- Step 4/6: the approved apply, run twice --------------------------------

new_host
apply_host="$host"
run_bootstrap prepare
expect_pass "prepare before apply" "ubuntu host bootstrap prepare completed"
apply_lock_sha="$(sha256_of "$apply_host/etc/nextvisit/ubuntu-packages.lock")"

# A token installed by the owner before apply must survive it untouched.
mkdir -p "$apply_host/etc/nextvisit"
token_file="$apply_host/etc/nextvisit/llm.token"
printf '%s\n' "$token_sentinel" >"$token_file"
chmod 440 "$token_file"
token_sha_before="$(sha256_of "$token_file")"

original_logind_sha="$(sha256_of "$apply_host/etc/systemd/logind.conf")"

run_bootstrap apply "$apply_lock_sha"
expect_pass "first apply" "ubuntu host bootstrap apply completed"
first_apply_log="$log"
first_apply_ownership="$ownership"

assert_no_distribution_upgrade "$first_apply_log"
assert_no_driver_package "$first_apply_log"
assert_no_secret_argument "$first_apply_log"
assert_no_duplicate_accounts "$apply_host"
assert_no_duplicate_repositories "$apply_host"
assert_single_logind_dropin "$apply_host"

# apt is invoked with explicit package=version arguments only.
apt_install_line="$(grep '^apt-get .*install' "$first_apply_log")"
while read -r pkg version; do
  case "$apt_install_line" in
    *" $pkg=$version"*) ;;
    *)
      printf 'apply did not pin %s=%s: %s\n' "$pkg" "$version" "$apt_install_line" >&2
      exit 1
      ;;
  esac
done <"$candidates"

assert_log_has "first apply" "nvidia-ctk runtime configure --runtime=docker"
assert_log_has "first apply" "systemctl restart docker"
assert_log_has "first apply" "groupadd --system --gid 65532 nextvisit-cloudflared"
assert_log_has "first apply" "useradd --create-home --home-dir /home/nextvisit-runner --shell /bin/bash nextvisit-runner"
assert_log_has "first apply" "usermod --append --groups docker nextvisit-runner"
assert_log_has "first apply" "usermod --append --groups nextvisit-cloudflared nextvisit-runner"
assert_log_has "first apply" "systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target"

# Directories and their requested ownership.
grep -Fqx "dir $apply_host/opt/nextvisit/llm/releases nextvisit-runner nextvisit-runner 0750" \
  "$first_apply_ownership" || {
  printf 'apply must create the release directory owned only by nextvisit-runner\n' >&2
  exit 1
}
# nextvisit-runner must own its parent too: creating or atomically replacing
# /opt/nextvisit/llm/current needs write access to /opt/nextvisit/llm itself,
# not merely to releases/ under it.
grep -Fqx "dir $apply_host/opt/nextvisit/llm nextvisit-runner nextvisit-runner 0750" \
  "$first_apply_ownership" || {
  printf 'apply must create /opt/nextvisit/llm owned by nextvisit-runner so it can flip current\n' >&2
  exit 1
}
test "$(mode_of "$apply_host/opt/nextvisit/llm")" = "750"
# Its own parent stays root-owned: only /opt/nextvisit/llm itself is loosened.
if grep -Fqx "dir $apply_host/opt/nextvisit nextvisit-runner nextvisit-runner 0755" "$first_apply_ownership"; then
  printf 'apply must not loosen /opt/nextvisit itself, only /opt/nextvisit/llm\n' >&2
  exit 1
fi
test "$(mode_of "$apply_host/opt/nextvisit")" = "755"
grep -Fqx "dir $apply_host/etc/nextvisit root 65532 0750" "$first_apply_ownership" || {
  printf 'apply must tighten /etc/nextvisit to root:65532 0750\n' >&2
  exit 1
}
test "$(mode_of "$apply_host/etc/nextvisit")" = "750"

# The pre-existing token is preserved byte for byte and never re-created.
test "$(sha256_of "$token_file")" = "$token_sha_before"
if grep -Fq "llm.token" "$first_apply_ownership"; then
  printf 'apply must never install over the tunnel token file\n' >&2
  exit 1
fi

# logind: one backup of the original file, one drop-in with the exact keys.
test "$(sha256_of "$apply_host/etc/systemd/logind.conf")" = "$original_logind_sha"
test "$(sha256_of "$apply_host/etc/systemd/logind.conf.nextvisit.bak")" = "$original_logind_sha"
dropin="$apply_host/etc/systemd/logind.conf.d/10-nextvisit-llm.conf"
grep -Fqx 'HandleLidSwitch=ignore' "$dropin"
grep -Fqx 'HandleLidSwitchExternalPower=ignore' "$dropin"
grep -Fqx 'IdleAction=ignore' "$dropin"
for target in sleep.target suspend.target hibernate.target hybrid-sleep.target; do
  test -L "$apply_host/etc/systemd/system/$target"
done

# Installed versions are reported so the owner can record them.
printf '%s\n' "$bootstrap_output" | grep -Fqx 'installed nvidia-container-toolkit=1.17.8-1' || {
  printf 'apply must report the installed versions for the owner checklist\n' >&2
  exit 1
}

first_manifest="$test_root/apply-manifest-1"
state_manifest "$apply_host" "$first_manifest"

run_bootstrap apply "$apply_lock_sha"
expect_pass "second apply" "ubuntu host bootstrap apply completed"
second_apply_log="$log"
second_apply_ownership="$ownership"

second_manifest="$test_root/apply-manifest-2"
state_manifest "$apply_host" "$second_manifest"
if ! cmp -s "$first_manifest" "$second_manifest"; then
  printf 'second apply changed host state:\n' >&2
  diff "$first_manifest" "$second_manifest" >&2 || true
  exit 1
fi
# The logind backup is taken once and only once, so the second run's ownership
# log must be the first run's with exactly that line dropped -- nothing else.
backup_line="file $apply_host/etc/systemd/logind.conf.nextvisit.bak root root 0644"
test "$(grep -Fxc "$backup_line" "$first_apply_ownership")" = 1
test "$(grep -Fxc "$backup_line" "$second_apply_ownership" || true)" = 0
grep -Fxv "$backup_line" "$first_apply_ownership" >"$test_root/ownership-expected"
if ! cmp -s "$test_root/ownership-expected" "$second_apply_ownership"; then
  printf 'second apply requested different ownership:\n' >&2
  diff "$test_root/ownership-expected" "$second_apply_ownership" >&2 || true
  exit 1
fi

log="$second_apply_log"
assert_log_lacks "second apply" "groupadd"
assert_log_lacks "second apply" "useradd"
assert_log_lacks "second apply" "usermod"
assert_log_lacks "second apply" "systemctl mask"
assert_no_distribution_upgrade "$second_apply_log"
assert_no_driver_package "$second_apply_log"
assert_no_secret_argument "$second_apply_log"
assert_no_duplicate_accounts "$apply_host"
assert_no_duplicate_repositories "$apply_host"
assert_single_logind_dropin "$apply_host"
test "$(sha256_of "$token_file")" = "$token_sha_before"

# IMPORTANT 6: once the nvidia runtime is already configured, apply must not
# reconfigure it or restart Docker again -- nvidia-ctk rewrites
# /etc/docker/daemon.json on a real host and a restart is itself a live
# mutation, so re-running both on every apply would falsify the README's
# "re-running either leaves host state unchanged" claim for the largest file
# this script rewrites.
assert_log_lacks "second apply" "nvidia-ctk"
assert_log_lacks "second apply" "systemctl restart docker"

# --- CRITICAL 1: prepare must never re-loosen /etc/nextvisit after apply
# tightened it to root:65532 0750. `install -d` applies owner/group/mode to
# an *existing* directory too, so an unconditional install_dir call here would
# revert the directory to root:root 0755 on every re-run of prepare, breaking
# verify-tunnel-token-file.sh's parent-directory check. -----------------------

run_bootstrap prepare
expect_pass "prepare after apply retightened /etc/nextvisit" \
  "ubuntu host bootstrap prepare completed"
if [ "$(mode_of "$apply_host/etc/nextvisit")" != "750" ]; then
  printf 'prepare must not revert /etc/nextvisit from the apply-tightened mode 0750 (now %s)\n' \
    "$(mode_of "$apply_host/etc/nextvisit")" >&2
  exit 1
fi
if grep -Fq "dir $apply_host/etc/nextvisit root root 0755" "$ownership"; then
  printf 'prepare must not re-request root:root 0755 ownership for /etc/nextvisit once apply has tightened it\n' >&2
  exit 1
fi

# --- Step 4: an unlocked pre-existing service account is locked -------------

new_host
lock_host="$host"
printf 'nextvisit-runner:x:4000:4000::/home/nextvisit-runner:/bin/bash\n' \
  >>"$lock_host/etc/passwd"
printf 'nextvisit-runner:unlocked-placeholder-hash:20000:0:99999:7:::\n' \
  >>"$lock_host/etc/shadow"
run_bootstrap prepare
expect_pass "prepare before the lock repair" "ubuntu host bootstrap prepare completed"
run_bootstrap apply "$(sha256_of "$lock_host/etc/nextvisit/ubuntu-packages.lock")"
expect_pass "apply locking an existing account" "ubuntu host bootstrap apply completed"
assert_log_has "lock repair" "usermod --lock nextvisit-runner"
assert_log_lacks "lock repair" "useradd"
grep -q '^nextvisit-runner:!:' "$lock_host/etc/shadow" || {
  printf 'apply must leave nextvisit-runner with no usable password\n' >&2
  exit 1
}

# --- Step 4: a missing Docker nvidia runtime fails closed -------------------

new_host
runtime_host="$host"
run_bootstrap prepare
expect_pass "prepare before the runtime check" "ubuntu host bootstrap prepare completed"
FAKE_DOCKER_RUNTIMES='{"runc":{}}' run_bootstrap apply \
  "$(sha256_of "$runtime_host/etc/nextvisit/ubuntu-packages.lock")"
expect_fail "missing Docker nvidia runtime" \
  "Docker nvidia runtime is not configured"

# --- Step 6: mutation tests — the policy assertions must be able to fail ----

mutation_log="$test_root/mutation-log"
printf '%s\n' 'apt-get install -y --no-install-recommends docker-ce=1' >"$mutation_log"
if assert_no_package_mutation "$mutation_log" 2>/dev/null; then
  printf 'mutation test failed: a package install must be detected\n' >&2
  exit 1
fi
printf '%s\n' 'apt-get dist-upgrade -y' >"$mutation_log"
if assert_no_distribution_upgrade "$mutation_log" 2>/dev/null; then
  printf 'mutation test failed: a distribution upgrade must be detected\n' >&2
  exit 1
fi
printf '%s\n' 'apt-get install -y nvidia-driver-580' >"$mutation_log"
if assert_no_driver_package "$mutation_log" 2>/dev/null; then
  printf 'mutation test failed: a driver package must be detected\n' >&2
  exit 1
fi
printf '%s\n' 'cloudflared tunnel run --token abc' >"$mutation_log"
if assert_no_secret_argument "$mutation_log" 2>/dev/null; then
  printf 'mutation test failed: a secret-shaped argument must be detected\n' >&2
  exit 1
fi
printf '%s\n' "some-command $token_sentinel" >"$mutation_log"
if assert_no_secret_argument "$mutation_log" 2>/dev/null; then
  printf 'mutation test failed: token content must be detected\n' >&2
  exit 1
fi

mutation_host="$test_root/mutation-host"
mkdir -p "$mutation_host/etc/apt/sources.list.d" "$mutation_host/etc/systemd/logind.conf.d"
printf 'root:x:0:\ndocker:x:998:nextvisit-runner\nnextvisit-cloudflared:x:65532:nextvisit-runner\nnextvisit-cloudflared:x:65532:nextvisit-runner\n' \
  >"$mutation_host/etc/group"
printf 'nextvisit-runner:x:4000:4000::/home/nextvisit-runner:/bin/bash\n' \
  >"$mutation_host/etc/passwd"
if assert_no_duplicate_accounts "$mutation_host" 2>/dev/null; then
  printf 'mutation test failed: a duplicated group must be detected\n' >&2
  exit 1
fi
printf 'deb https://example.invalid a b\ndeb https://example.invalid a b\n' \
  >"$mutation_host/etc/apt/sources.list.d/docker.list"
printf 'deb https://example.invalid c d\n' \
  >"$mutation_host/etc/apt/sources.list.d/nvidia-container-toolkit.list"
if assert_no_duplicate_repositories "$mutation_host" 2>/dev/null; then
  printf 'mutation test failed: a duplicated repository line must be detected\n' >&2
  exit 1
fi
printf '[Login]\n' >"$mutation_host/etc/systemd/logind.conf.d/10-nextvisit-llm.conf"
printf '[Login]\n' >"$mutation_host/etc/systemd/logind.conf.d/20-stale.conf"
if assert_single_logind_dropin "$mutation_host" 2>/dev/null; then
  printf 'mutation test failed: a duplicated logind drop-in must be detected\n' >&2
  exit 1
fi

# --- Static policy: the script may never trace its own commands -------------

if grep -Eq '(^|[^[:alnum:]_])set[[:space:]]+(-[A-Za-z]*x[A-Za-z]*|-o[[:space:]]+xtrace)([^[:alnum:]_-]|$)' "$bootstrap"; then
  printf '%s\n' "bootstrap-ubuntu-host.sh must never enable shell xtrace" >&2
  exit 1
fi

printf 'bootstrap-ubuntu-host tests passed (%s host fixtures)\n' "$host_number"
