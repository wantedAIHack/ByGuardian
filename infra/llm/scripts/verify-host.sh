#!/bin/sh
set -eu

mode="${1:-cpu}"
case "$mode" in
  cpu|gpu) ;;
  *)
    printf '%s\n' "Usage: $0 cpu|gpu" >&2
    exit 2
    ;;
esac

command -v docker >/dev/null 2>&1 || {
  printf '%s\n' "docker is required" >&2
  exit 1
}
command -v curl >/dev/null 2>&1 || {
  printf '%s\n' "curl is required" >&2
  exit 1
}
command -v jq >/dev/null 2>&1 || {
  printf '%s\n' "jq is required" >&2
  exit 1
}
docker info >/dev/null
compose_version="$(docker compose version --short | sed 's/^v//; s/-.*$//')"
version_at_least() {
  awk -v current="$1" -v required="$2" 'BEGIN {
    split(current, c, "."); split(required, r, ".")
    for (i = 1; i <= 3; i++) {
      if ((c[i] + 0) > (r[i] + 0)) exit 0
      if ((c[i] + 0) < (r[i] + 0)) exit 1
    }
    exit 0
  }'
}
if ! version_at_least "$compose_version" "2.24.4"; then
  printf '%s\n' "Docker Compose v2.24.4 or later is required" >&2
  exit 1
fi

if [ -n "${NEXTVISIT_LLM_DATA_PATH:-}" ]; then
  data_path="$NEXTVISIT_LLM_DATA_PATH"
else
  case "$(uname -s)" in
    Linux)
      data_path=/var/lib/docker
      [ -d "$data_path" ] || data_path=/
      ;;
    Darwin)
      data_path=.
      printf '%s\n' "warning: verify Docker Desktop disk image capacity manually" >&2
      ;;
    *) data_path=. ;;
  esac
fi
available_kib="$(df -Pk "$data_path" | awk 'NR == 2 {print $4}')"
case "$available_kib" in
  ''|*[!0-9]*)
    printf '%s\n' "unable to determine available disk space" >&2
    exit 1
    ;;
esac
minimum_kib=20971520
if [ "$available_kib" -lt "$minimum_kib" ]; then
  printf '%s\n' "at least 20 GiB of free disk is required" >&2
  exit 1
fi

if [ "$mode" = "gpu" ]; then
  command -v nvidia-smi >/dev/null 2>&1 || {
    printf '%s\n' "nvidia-smi is required for gpu mode" >&2
    exit 1
  }
  command -v nvidia-ctk >/dev/null 2>&1 || {
    printf '%s\n' "nvidia-ctk is required for gpu mode" >&2
    exit 1
  }
  nvidia-smi >/dev/null
  docker info --format '{{json .Runtimes}}' | grep -q '"nvidia"' || {
    printf '%s\n' "Docker nvidia runtime is not configured" >&2
    exit 1
  }

  # The rest of gpu mode checks what bootstrap-ubuntu-host.sh established. It
  # deliberately says nothing about the Tunnel token file: gpu mode has to pass
  # before the owner installs that token (OWNER_CHECKLIST.md section 3), and
  # verify-tunnel-token-file.sh is what validates it afterwards.
  command -v passwd >/dev/null 2>&1 || {
    printf '%s\n' "passwd is required for gpu mode" >&2
    exit 1
  }
  command -v ss >/dev/null 2>&1 || {
    printf '%s\n' "ss is required for gpu mode" >&2
    exit 1
  }

  runner_user=nextvisit-runner
  password_state="$(passwd -S "$runner_user" 2>/dev/null | awk 'NR == 1 {print $2}')"
  case "$password_state" in
    L|LK) ;;
    '')
      printf '%s\n' "unable to read the $runner_user password state; create the account first, then run gpu mode as $runner_user or root" >&2
      exit 1
      ;;
    *)
      printf '%s\n' "$runner_user must have no usable password" >&2
      exit 1
      ;;
  esac

  releases_path="${NEXTVISIT_LLM_RELEASES_PATH:-/opt/nextvisit/llm/releases}"
  if [ -L "$releases_path" ] || [ ! -d "$releases_path" ]; then
    printf '%s\n' "$releases_path must be a directory" >&2
    exit 1
  fi
  releases_meta="$(stat -c '%U %a' -- "$releases_path" 2>/dev/null ||
    stat -f '%Su %Lp' -- "$releases_path")"
  if [ "$releases_meta" != "$runner_user 750" ]; then
    printf '%s\n' "$releases_path must be owned by $runner_user with mode 0750" >&2
    exit 1
  fi

  for target in sleep.target suspend.target hibernate.target hybrid-sleep.target; do
    target_state="$(systemctl is-enabled "$target" 2>/dev/null || true)"
    if [ "$target_state" != "masked" ]; then
      printf '%s\n' "$target must be masked on this dedicated server host" >&2
      exit 1
    fi
  done

  # Ollama must be reachable only through the Cloudflare Tunnel, so nothing may
  # listen on 11434 outside loopback. Every other check in this file fails
  # closed, so an `ss` that errors (netlink blocked, a hardened container, a
  # broken binary) must not be silently treated as "nothing is listening".
  if ! listeners="$(ss -H -ltn 2>/dev/null)"; then
    printf '%s\n' "unable to enumerate listening sockets" >&2
    exit 1
  fi
  public_listener="$(printf '%s\n' "$listeners" |
    awk '{print $4}' |
    grep -E ':11434$' |
    grep -vE '^(127\.0\.0\.1|\[::1\]):11434$' || true)"
  if [ -n "$public_listener" ]; then
    printf '%s\n' "port 11434 must not be published outside loopback" >&2
    exit 1
  fi
fi

printf '%s\n' "host verification passed for $mode mode"
