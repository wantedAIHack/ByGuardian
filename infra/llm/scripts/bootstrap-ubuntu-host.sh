#!/bin/sh
set -eu

# Idempotent Ubuntu 24.04 bootstrap for the self-hosted LLM origin host.
#
#   bootstrap-ubuntu-host.sh prepare
#   bootstrap-ubuntu-host.sh apply APPROVED_LOCK_SHA256
#
# `prepare` installs nothing. It writes only the official signed Docker and
# NVIDIA repository key/list files, resolves the candidate versions of the
# packages this host needs, and records them with source fingerprints in the
# non-secret lock file /etc/nextvisit/ubuntu-packages.lock (mode 0444). The
# owner reviews the printed values and the printed SHA-256, then passes that
# SHA-256 back to `apply`, which refuses to touch any package until it matches
# and until a fresh candidate resolution still agrees with the lock.
#
# This script never installs, upgrades, or otherwise touches an NVIDIA driver
# package: it fails closed unless nvidia-smi already works. It never runs a
# general distribution upgrade, never reboots, and never handles a secret --
# the Tunnel token is installed separately (OWNER_CHECKLIST.md section 3) and
# an existing token file is left byte for byte untouched.
#
# NEXTVISIT_BOOTSTRAP_ROOT relocates the paths this script READS and the files
# it writes through `install`, but it does NOT relocate apt, useradd, usermod,
# groupadd, nvidia-ctk, or systemctl -- those always hit the real host. Running
# as root with this set would therefore satisfy every gate from a fake tree
# while really installing packages and really mutating accounts, so it is
# refused unless the tree carries a `.nextvisit-fake-root` marker that only the
# test harness creates, and it announces itself on stderr whenever it is set.
# It exists for the fake-root test harness; leave it unset on a real host.

root="${NEXTVISIT_BOOTSTRAP_ROOT:-}"

runner_user=nextvisit-runner
cloudflared_group=nextvisit-cloudflared
cloudflared_gid=65532

nextvisit_dir=/etc/nextvisit
lock_path="$nextvisit_dir/ubuntu-packages.lock"
token_path="$nextvisit_dir/llm.token"
releases_dir=/opt/nextvisit/llm/releases

docker_key=/etc/apt/keyrings/docker.asc
docker_list=/etc/apt/sources.list.d/docker.list
nvidia_key=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
nvidia_list=/etc/apt/sources.list.d/nvidia-container-toolkit.list

# Docker Engine/CLI/containerd/Compose plus the NVIDIA container runtime.
# The NVIDIA *driver* is deliberately absent from this list.
# docker-buildx-plugin is not optional here: infra/llm/Dockerfile uses
# `COPY --chmod=`, which only the BuildKit builder understands. Without buildx
# the CLI silently falls back to the legacy builder and every `docker compose
# build` on this host fails with "the --chmod option requires BuildKit" --
# which is exactly what happened on the first real GPU host.
packages='containerd.io
docker-buildx-plugin
docker-ce
docker-ce-cli
docker-compose-plugin
libnvidia-container1
nvidia-container-toolkit'

sleep_targets='sleep.target suspend.target hibernate.target hybrid-sleep.target'

usage='Usage: bootstrap-ubuntu-host.sh prepare|apply APPROVED_LOCK_SHA256'

fake_root_banner='NEXTVISIT_BOOTSTRAP_ROOT is set: paths are relocated under a fake root; this must never happen on a real host'

fail() {
  printf '%s\n' "$1" >&2
  exit 1
}

# NEXTVISIT_BOOTSTRAP_ROOT relocates paths only; it must never let a real
# invocation mutate the real host while satisfying every gate from a fake
# tree. Refuse to honour it unless the relocated tree carries a marker only
# the test harness creates, and announce it unmissably every time it is set.
if [ -n "$root" ]; then
  printf '%s\n' "$fake_root_banner" >&2
  [ -f "$root/.nextvisit-fake-root" ] ||
    fail "NEXTVISIT_BOOTSTRAP_ROOT is set without a .nextvisit-fake-root marker; refusing to run"
fi

# Absolute host path relocated under the optional fake root.
hostpath() {
  printf '%s%s\n' "$root" "$1"
}

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" | awk '{print $1}'
  else
    shasum -a 256 -- "$1" | awk '{print $1}'
  fi
}

os_release_value() {
  sed -n "s/^$1=//p" "$2" | head -n 1 | sed 's/^"//; s/"$//'
}

work_dir=""
cleanup() {
  [ -n "$work_dir" ] && rm -rf "$work_dir"
  return 0
}
trap cleanup EXIT HUP INT TERM

# --- preflight -------------------------------------------------------------

preflight() {
  [ "$(id -u)" = 0 ] || fail "bootstrap must run as root"

  os_release="$(hostpath /etc/os-release)"
  [ -f "$os_release" ] || fail "bootstrap requires Ubuntu 24.04"
  [ "$(os_release_value ID "$os_release")" = ubuntu ] ||
    fail "bootstrap requires Ubuntu 24.04"
  [ "$(os_release_value VERSION_ID "$os_release")" = 24.04 ] ||
    fail "bootstrap requires Ubuntu 24.04"
  codename="$(os_release_value VERSION_CODENAME "$os_release")"
  [ -n "$codename" ] || fail "bootstrap requires Ubuntu 24.04"

  [ "$(uname -m)" = x86_64 ] || fail "bootstrap requires x86_64 hardware"

  # Fail closed on the driver: this script must keep whatever driver the owner
  # already installed, so a non-working driver is a stop, never a repair.
  nvidia-smi >/dev/null 2>&1 ||
    fail "existing NVIDIA driver is not working; bootstrap never installs a driver"

  ac_online=no
  for supply in "$(hostpath /sys/class/power_supply)"/*; do
    [ -f "$supply/type" ] || continue
    [ "$(cat "$supply/type")" = Mains ] || continue
    [ -f "$supply/online" ] || continue
    [ "$(cat "$supply/online")" = 1 ] || continue
    ac_online=yes
  done
  [ "$ac_online" = yes ] || fail "bootstrap requires AC power"

  data_path="$(hostpath /var/lib/docker)"
  [ -d "$data_path" ] || data_path="$(hostpath /)"
  available_kib="$(df -Pk "$data_path" | awk 'NR == 2 {print $4}')"
  case "$available_kib" in
    ''|*[!0-9]*) fail "unable to determine available disk space" ;;
  esac
  [ "$available_kib" -ge 20971520 ] ||
    fail "bootstrap requires at least 20 GiB of free disk"

  work_dir="$(mktemp -d)"
}

# --- signed repository configuration --------------------------------------

# Always writes through `install` so owner, group, and mode are asserted on
# every run rather than only on the run that created the file.
install_file() {
  install -o root -g root -m "$3" -- "$1" "$(hostpath "$2")"
}

install_dir() {
  install -d -o "$2" -g "$3" -m "$4" -- "$(hostpath "$1")"
}

write_repositories() {
  install_dir /etc/apt/keyrings root root 0755
  install_dir /etc/apt/sources.list.d root root 0755
  install_dir /usr/share/keyrings root root 0755

  curl -fsSL https://download.docker.com/linux/ubuntu/gpg >"$work_dir/docker.asc"
  install_file "$work_dir/docker.asc" "$docker_key" 0644

  architecture="$(dpkg --print-architecture)"
  printf 'deb [arch=%s signed-by=%s] https://download.docker.com/linux/ubuntu %s stable\n' \
    "$architecture" "$docker_key" "$codename" >"$work_dir/docker.list"
  install_file "$work_dir/docker.list" "$docker_list" 0644

  # Downloaded to a plain file first, never straight into a pipe: with
  # `set -eu` and no `pipefail` (dash has none), `curl | gpg --dearmor` would
  # let a 5xx, DNS failure, or captive portal leave gpg's output empty while
  # the pipeline's last-command exit status (gpg's) still looked like
  # success, and `install_file` would then happily install that empty file
  # as a *trusted* keyring. Checking curl's own exit status and the
  # downloaded bytes before transforming them closes both holes.
  if ! curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
    >"$work_dir/nvidia.gpgkey"; then
    fail "download of the NVIDIA repository key failed"
  fi
  [ -s "$work_dir/nvidia.gpgkey" ] ||
    fail "download of the NVIDIA repository key was empty"
  gpg --dearmor <"$work_dir/nvidia.gpgkey" >"$work_dir/nvidia.gpg"
  [ -s "$work_dir/nvidia.gpg" ] ||
    fail "NVIDIA repository key failed to dearmor into a non-empty keyring"
  install_file "$work_dir/nvidia.gpg" "$nvidia_key" 0644

  if ! curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
    >"$work_dir/nvidia.list.raw"; then
    fail "download of the NVIDIA repository list failed"
  fi
  [ -s "$work_dir/nvidia.list.raw" ] ||
    fail "download of the NVIDIA repository list was empty"
  sed "s#deb https://#deb [signed-by=$nvidia_key] https://#g" \
    <"$work_dir/nvidia.list.raw" >"$work_dir/nvidia.list"
  [ -s "$work_dir/nvidia.list" ] ||
    fail "NVIDIA repository list transform produced empty output"
  install_file "$work_dir/nvidia.list" "$nvidia_list" 0644
}

# Sorted `package name=version` lines for the resolved apt candidates.
# Written to a file rather than piped so an unresolvable package aborts the
# whole script instead of a pipeline subshell.
resolve_candidates() {
  apt-get update >/dev/null
  : >"$work_dir/candidates"
  for package in $packages; do
    candidate="$(apt-cache policy "$package" | sed -n 's/^ *Candidate: *//p' | head -n 1)"
    case "$candidate" in
      ''|'(none)') fail "unable to resolve a candidate version for $package" ;;
    esac
    printf 'package %s=%s\n' "$package" "$candidate" >>"$work_dir/candidates"
  done
  LC_ALL=C sort "$work_dir/candidates"
}

write_lock() {
  resolve_candidates >"$work_dir/lock"
  for signed in "$docker_key" "$docker_list" "$nvidia_key" "$nvidia_list"; do
    printf 'source %s sha256:%s\n' "$signed" "$(sha256_of "$(hostpath "$signed")")"
  done | LC_ALL=C sort >>"$work_dir/lock"

  # Create only when absent: `install -d` applies owner/group/mode to an
  # existing directory too, so calling this unconditionally on a host that
  # already went through `apply` would revert /etc/nextvisit from the
  # apply-tightened root:65532 0750 back to root:root 0755 on every re-run of
  # `prepare`, breaking verify-tunnel-token-file.sh's parent-directory check.
  [ -d "$(hostpath "$nextvisit_dir")" ] || install_dir "$nextvisit_dir" root root 0755
  install_file "$work_dir/lock" "$lock_path" 0444
}

# --- phases ----------------------------------------------------------------

phase_prepare() {
  write_repositories
  write_lock

  lock_file="$(hostpath "$lock_path")"
  cat "$lock_file"
  printf 'ubuntu-packages.lock sha256: %s\n' "$(sha256_of "$lock_file")"
  printf 'review the values above, then run: bootstrap-ubuntu-host.sh apply <sha256>\n'
  printf 'ubuntu host bootstrap prepare completed\n'
}

ensure_group() {
  group_file="$(hostpath /etc/group)"
  existing="$(awk -v gid="$cloudflared_gid" -F: '$3 == gid {print $1}' "$group_file" | head -n 1)"
  if [ -n "$existing" ]; then
    [ "$existing" = "$cloudflared_group" ] ||
      fail "GID $cloudflared_gid already belongs to group $existing"
    return 0
  fi
  # A same-named group on a different GID would silently break the token file's
  # numeric group, so refuse rather than adopt it.
  if awk -v name="$cloudflared_group" -F: '$1 == name {found = 1} END {exit !found}' "$group_file"; then
    fail "group $cloudflared_group exists with a GID other than $cloudflared_gid"
  fi
  groupadd --system --gid "$cloudflared_gid" "$cloudflared_group"
}

group_has_member() {
  awk -v group="$1" -v member="$2" -F: '
    $1 == group {
      n = split($4, members, ",")
      for (i = 1; i <= n; i++) if (members[i] == member) found = 1
    }
    END { exit !found }
  ' "$(hostpath /etc/group)"
}

ensure_runner_account() {
  passwd_file="$(hostpath /etc/passwd)"
  if ! awk -v name="$runner_user" -F: '$1 == name {found = 1} END {exit !found}' "$passwd_file"; then
    useradd --create-home --home-dir "/home/$runner_user" --shell /bin/bash "$runner_user"
  fi

  password_state="$(passwd -S "$runner_user" | awk '{print $2}')"
  [ -n "$password_state" ] || fail "unable to read the $runner_user password state"
  case "$password_state" in
    L|LK) ;;
    *) usermod --lock "$runner_user" ;;
  esac

  for group in docker "$cloudflared_group"; do
    if ! awk -v name="$group" -F: '$1 == name {found = 1} END {exit !found}' \
      "$(hostpath /etc/group)"; then
      fail "group $group is missing; install Docker Engine before creating the account"
    fi
    group_has_member "$group" "$runner_user" ||
      usermod --append --groups "$group" "$runner_user"
  done
}

ensure_directories() {
  install_dir /opt/nextvisit root root 0755
  # nextvisit-runner (not root) must be able to create and atomically replace
  # /opt/nextvisit/llm/current: creating or renaming a directory entry needs
  # write access to its parent, so this directory has to be runner-writable,
  # not merely traversable. 0750 keeps it as tight as releases/ below; only
  # root and nextvisit-runner itself can enter it. nextvisit-runner is already
  # in the docker group (root-equivalent for this host) and already owns
  # everything under releases/, so this grants no new capability today; that
  # reasoning breaks if this account is ever de-escalated from docker-group
  # root-equivalence (e.g. rootless Docker, a socket proxy).
  install_dir /opt/nextvisit/llm "$runner_user" "$runner_user" 0750
  install_dir "$releases_dir" "$runner_user" "$runner_user" 0750

  # Tightened before any token can exist: only root, cloudflared's numeric
  # group, and the dedicated deployment account may traverse this directory.
  install_dir "$nextvisit_dir" root "$cloudflared_gid" 0750

  token_file="$(hostpath "$token_path")"
  if [ -e "$token_file" ] || [ -L "$token_file" ]; then
    printf 'preserving the existing tunnel token file at %s\n' "$token_path"
  else
    printf 'no tunnel token file yet at %s; install it per OWNER_CHECKLIST.md section 3\n' \
      "$token_path"
  fi
}

ensure_power_settings() {
  logind_conf="$(hostpath /etc/systemd/logind.conf)"
  logind_backup="$logind_conf.nextvisit.bak"
  if [ -f "$logind_conf" ] && [ ! -f "$logind_backup" ]; then
    install -o root -g root -m 0644 -- "$logind_conf" "$logind_backup"
  fi

  install_dir /etc/systemd/logind.conf.d root root 0755
  cat >"$work_dir/logind-dropin" <<'DROPIN'
# Managed by infra/llm/scripts/bootstrap-ubuntu-host.sh.
# This laptop is a dedicated server host: the lid stays closed and idle must
# never suspend the Ollama origin or its Cloudflare Tunnel.
[Login]
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
IdleAction=ignore
DROPIN
  install_file "$work_dir/logind-dropin" \
    /etc/systemd/logind.conf.d/10-nextvisit-llm.conf 0644

  unmasked=""
  for target in $sleep_targets; do
    state="$(systemctl is-enabled "$target" 2>/dev/null || true)"
    if [ "$state" != masked ]; then
      unmasked="$unmasked $target"
    fi
  done
  if [ -n "$unmasked" ]; then
    # Unquoted on purpose: the masked targets are a word list, not one word.
    # shellcheck disable=SC2086
    systemctl mask $unmasked
  fi
}

phase_apply() {
  approved_sha="$1"
  lock_file="$(hostpath "$lock_path")"
  [ -f "$lock_file" ] || fail "run bootstrap-ubuntu-host.sh prepare before apply"
  [ "$(sha256_of "$lock_file")" = "$approved_sha" ] ||
    fail "approved lock SHA-256 does not match $lock_path"

  # Re-fingerprint the signed repository files before resolve_candidates runs
  # `apt-get update` against them: the lock records these fingerprints but
  # nothing checked them against disk, so anyone who could rewrite the
  # signed key/list files between prepare and apply could point apply at a
  # different archive and pick whatever version strings they liked.
  for signed in "$docker_key" "$docker_list" "$nvidia_key" "$nvidia_list"; do
    printf 'source %s sha256:%s\n' "$signed" "$(sha256_of "$(hostpath "$signed")")"
  done | LC_ALL=C sort >"$work_dir/current_sources"
  grep '^source ' "$lock_file" | LC_ALL=C sort >"$work_dir/approved_sources"
  cmp -s "$work_dir/approved_sources" "$work_dir/current_sources" ||
    fail "signed repository files changed since prepare; rerun prepare"

  # Re-resolve before mutating anything: an approved lock that no longer
  # matches the archive would install versions the owner never reviewed.
  resolve_candidates >"$work_dir/current"
  grep '^package ' "$lock_file" >"$work_dir/approved"
  cmp -s "$work_dir/approved" "$work_dir/current" ||
    fail "package candidate versions drifted since prepare; rerun prepare"

  ensure_group

  # Explicit package=version arguments only; never `upgrade` or `dist-upgrade`.
  pinned="$(sed 's/^package //' "$work_dir/approved" | tr '\n' ' ')"
  # shellcheck disable=SC2086
  DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends $pinned

  ensure_runner_account
  ensure_directories
  ensure_power_settings

  # Only reconfigure and restart Docker when the nvidia runtime is not already
  # present: nvidia-ctk rewrites /etc/docker/daemon.json on a real host and a
  # restart is itself a live mutation, so running both on every apply would
  # make the README's "re-running either leaves host state unchanged" claim
  # false for the largest file this script rewrites.
  if docker info --format '{{json .Runtimes}}' | grep -q '"nvidia"'; then
    :
  else
    nvidia-ctk runtime configure --runtime=docker
    systemctl restart docker
    docker info --format '{{json .Runtimes}}' | grep -q '"nvidia"' ||
      fail "Docker nvidia runtime is not configured"
  fi

  # Not piped straight into sort: a POSIX pipeline reports only its last
  # command's status, which would hide a failed version query.
  # shellcheck disable=SC2086
  dpkg-query --show --showformat='${Package}=${Version}\n' $packages \
    >"$work_dir/installed"
  printf 'record these versions in OWNER_CHECKLIST.md section 2:\n'
  LC_ALL=C sort "$work_dir/installed" | sed 's/^/installed /'
  printf 'ubuntu host bootstrap apply completed\n'
}

# --- entry point -----------------------------------------------------------

phase="${1:-}"
case "$phase" in
  prepare)
    [ "$#" -eq 1 ] || fail "$usage"
    preflight
    phase_prepare
    ;;
  apply)
    [ "$#" -eq 2 ] || fail "$usage"
    preflight
    phase_apply "$2"
    ;;
  *) fail "$usage" ;;
esac
