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
fi

printf '%s\n' "host verification passed for $mode mode"
