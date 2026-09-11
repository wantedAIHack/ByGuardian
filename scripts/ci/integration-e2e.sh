#!/bin/sh
set -eu

root=$(CDPATH='' cd -- "$(dirname -- "$0")/../.." && pwd)
logs=$(mktemp -d "${TMPDIR:-/tmp}/nextvisit-integration.XXXXXX")
api_pid=
fe_pid=
active_pid=
component=setup

# Signal only PIDs recorded immediately after this script launches a child.
stop_children() {
  roots="$api_pid $fe_pid $active_pid"
  for pid in $roots; do kill -TERM "$pid" 2>/dev/null || :; done
  remaining=5
  while [ "$remaining" -gt 0 ]; do
    live=0
    for pid in $roots; do if kill -0 "$pid" 2>/dev/null; then live=1; fi; done
    [ "$live" -eq 1 ] || break
    sleep 1
    remaining=$((remaining - 1))
  done
  for pid in $roots; do
    if kill -0 "$pid" 2>/dev/null; then kill -KILL "$pid" 2>/dev/null || :; fi
  done
  for pid in $roots; do wait "$pid" 2>/dev/null || :; done
  api_pid='' fe_pid='' active_pid=''
}

# Bound image pulls, builds, browser runs and cleanup as well as readiness checks.
# Pass external commands directly: a backgrounded shell function would make $!
# identify its wrapper rather than the worker that cleanup must terminate.
run_bounded() {
  limit=$1
  output=$2
  shift 2
  "$@" >"$logs/$output.log" 2>&1 &
  active_pid=$!
  elapsed=0
  while kill -0 "$active_pid" 2>/dev/null; do
    [ "$elapsed" -lt "$limit" ] || return 124
    sleep 1
    elapsed=$((elapsed + 1))
  done
  bounded_result=0
  wait "$active_pid" || bounded_result=$?
  active_pid=
  return "$bounded_result"
}

cleanup() {
  result=$?
  trap - EXIT HUP INT TERM
  stop_children
  if ! run_bounded 45 cleanup docker compose --project-name nextvisit-integration -f "$root/infra/local/compose.integration.yml" down --volumes --timeout 10; then
    stop_children
    result=1
    printf '%s\n' 'integration: cleanup failed'
  fi
  if ! run_bounded 10 remaining-containers docker ps -aq --filter label=com.docker.compose.project=nextvisit-integration; then
    stop_children
    result=1
  elif [ -s "$logs/remaining-containers.log" ]; then
    result=1
    printf '%s\n' 'integration: project containers remain'
  fi
  if [ "$result" -ne 0 ]; then
    printf 'integration: %s failed (status %s); response bodies withheld\n' "$component" "$result"
  else
    printf '%s\n' 'integration: browser journey passed; scoped cleanup complete'
  fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

cd "$root"
export NEXTVISIT_LLM_ENABLED=false
# Defense in depth: even an accidental model call can only reach a closed local port.
export NEXTVISIT_LLM_BASE_URL=http://127.0.0.1:9/v1
export NEXTVISIT_DB_URL=jdbc:postgresql://127.0.0.1:55432/nextvisit_integration
export NEXTVISIT_DB_USER=nextvisit_integration
export NEXTVISIT_DB_PASSWORD=nextvisit_integration
export NEXTVISIT_CORS_ORIGINS=http://127.0.0.1:14173
export NEXTVISIT_DEMO_ENABLED=true
export SERVER_ADDRESS=127.0.0.1
export SERVER_PORT=18080
export VITE_API_BASE=http://127.0.0.1:18080

component=toolchain
[ "$(node --version)" = v22.22.2 ]
java -version >"$logs/java.log" 2>&1
awk '/version "21[.]/ { ok=1 } END { exit !ok }' "$logs/java.log"

component=ports
# Fail before starting services if any fixed integration port is already occupied.
node --input-type=module -e '
  import net from "node:net";
  for (const port of [55432, 18080, 14173]) {
    await new Promise((resolve, reject) => {
      const server = net.createServer();
      server.once("error", reject);
      server.listen(port, "127.0.0.1", () => server.close(resolve));
    });
  }
' >"$logs/ports.log" 2>&1

component=postgres
run_bounded 240 postgres docker compose --project-name nextvisit-integration -f "$root/infra/local/compose.integration.yml" up --detach --wait --wait-timeout 60

component=api
(cd "$root/backend" && exec ./gradlew --no-daemon --console=plain :api:bootRun) >"$logs/api.log" 2>&1 &
api_pid=$!
ready=0
deadline=$(($(date +%s) + 60))
while [ "$(date +%s)" -lt "$deadline" ]; do
  kill -0 "$api_pid" 2>/dev/null || break
  if curl --fail --silent --max-time 1 --output /dev/null http://127.0.0.1:18080/health; then ready=1; break; fi
  sleep 1
done
[ "$ready" -eq 1 ]
printf '%s\n' 'integration: PostgreSQL and LLM-disabled API ready'

component=frontend-build
run_bounded 120 frontend-build npm --prefix frontend run build
component=frontend
node "$root/frontend/node_modules/vite/bin/vite.js" preview "$root/frontend" --host 127.0.0.1 --port 14173 --strictPort >"$logs/frontend.log" 2>&1 &
fe_pid=$!
ready=0
deadline=$(($(date +%s) + 30))
while [ "$(date +%s)" -lt "$deadline" ]; do
  kill -0 "$fe_pid" 2>/dev/null || break
  if curl --fail --silent --max-time 1 --output /dev/null http://127.0.0.1:14173/demo; then ready=1; break; fi
  sleep 1
done
[ "$ready" -eq 1 ]

component=browser
run_bounded 120 browser npm --prefix frontend run test:e2e
