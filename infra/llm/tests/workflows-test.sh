#!/bin/sh
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
ci="$repo_root/.github/workflows/llm-ci.yml"
deploy="$repo_root/.github/workflows/llm-deploy.yml"

require_fixed() {
  needle=$1
  file=$2
  message=$3
  if ! grep -F "$needle" "$file" >/dev/null; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

forbid_fixed() {
  needle=$1
  file=$2
  message=$3
  if grep -F "$needle" "$file" >/dev/null; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

test -f "$ci"
test -f "$deploy"

require_fixed '  push:' "$ci" "CI must run on pushes"
require_fixed '  pull_request:' "$ci" "CI must run on pull requests"
require_fixed '    branches: [main, feat/llm-server]' "$ci" "CI push branches are not constrained"
require_fixed '  contents: read' "$ci" "CI permissions must be read-only"
require_fixed '  contents: read' "$deploy" "deploy permissions must be read-only"
require_fixed 'uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262' "$ci" "CI checkout action must use the approved immutable SHA"
require_fixed 'uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262' "$deploy" "deploy checkout action must use the approved immutable SHA"
require_fixed 'uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3' "$ci" "Java setup action must use the approved immutable SHA"

ci_jobs="$(grep -Ec '^[[:space:]]+runs-on:' "$ci")"
ci_hosted_jobs="$(grep -Ec '^[[:space:]]+runs-on: ubuntu-latest[[:space:]]*$' "$ci")"
test "$ci_jobs" -eq 2
test "$ci_hosted_jobs" -eq "$ci_jobs"
forbid_fixed 'self-hosted' "$ci" "CI must not use the laptop runner"
forbid_fixed 'pull_request_target' "$ci" "pull_request_target is forbidden"
forbid_fixed 'pull_request_target' "$deploy" "pull_request_target is forbidden"
forbid_fixed '  workflow_dispatch:' "$ci" "CI must use only push and pull_request events"
forbid_fixed 'model-init' "$ci" "CI must not pull or initialize the model"
forbid_fixed 'ollama pull' "$ci" "CI must not pull the model"

ci_checkouts="$(grep -Fc 'uses: actions/checkout@' "$ci")"
ci_no_credentials="$(grep -Fc 'persist-credentials: false' "$ci")"
test "$ci_checkouts" -eq 2
test "$ci_no_credentials" -eq "$ci_checkouts"
deploy_checkouts="$(grep -Fc 'uses: actions/checkout@' "$deploy")"
deploy_no_credentials="$(grep -Fc 'persist-credentials: false' "$deploy")"
test "$deploy_checkouts" -eq 1
test "$deploy_no_credentials" -eq "$deploy_checkouts"

if grep -E '^[[:space:]]*uses:' "$ci" "$deploy" \
  | grep -Ev 'uses: actions/(checkout@11d5960a326750d5838078e36cf38b85af677262|setup-java@cf277c60eb25467037889841efdb72551f06f6c3)([[:space:]]|$)' >/dev/null; then
  printf '%s\n' "every action must use an approved immutable SHA" >&2
  exit 1
fi

require_fixed '    branches: [main]' "$deploy" "deploy pushes must be limited to main"
require_fixed '  workflow_dispatch:' "$deploy" "deploy must allow an explicit manual dispatch"
require_fixed "    if: \${{ github.ref == 'refs/heads/main' && vars.LLM_DEPLOY_ENABLED == 'true' }}" "$deploy" "deploy gate must be a job-level main and repository-variable check"
require_fixed '    runs-on: [self-hosted, linux, llm]' "$deploy" "deploy runner labels are incomplete"
require_fixed '    environment: llm-laptop' "$deploy" "deploy environment is missing"
require_fixed '    timeout-minutes: 90' "$deploy" "deploy timeout must be 90 minutes"
require_fixed '  group: llm-laptop-deploy' "$deploy" "deploy concurrency group is missing"
require_fixed '  cancel-in-progress: false' "$deploy" "deploy concurrency must not cancel an active deployment"
require_fixed "          ref: \${{ github.sha }}" "$deploy" "deploy must check out the triggering commit"
forbid_fixed 'pull_request:' "$deploy" "deploy must not accept pull requests"
forbid_fixed 'ref: main' "$deploy" "deploy must not check out moving main"

require_fixed 'test -r /etc/nextvisit/llm.env' "$deploy" "deploy must require the host-owned environment file"
require_fixed "stat -c '%a' /etc/nextvisit/llm.env" "$deploy" "deploy must verify environment file permissions"
require_fixed 'NEXTVISIT_LLM_ENV_FILE: /etc/nextvisit/llm.env' "$deploy" "deploy must pass only the host-owned environment file"
forbid_fixed 'secrets.' "$deploy" "deploy must not consume repository secret content"
forbid_fixed 'set -x' "$deploy" "deploy must not enable shell tracing"
if grep -E '(docker( compose)?[[:space:]].*logs|(^|[[:space:]])(cat|head|tail|printenv)[[:space:]].*llm\.env)' "$deploy" >/dev/null; then
  printf '%s\n' "deploy must not dump container logs or environment contents" >&2
  exit 1
fi

require_fixed 'run --rm model-init' "$deploy" "model initialization must stay inside the gated deploy job"
if grep -E '(docker[[:space:]].*prune|docker[[:space:]]+volume[[:space:]]+rm|docker[[:space:]]+compose.*down.*(--volumes|-v)|rm[[:space:]].*ollama-data)' "$deploy" >/dev/null; then
  printf '%s\n' "deploy must preserve the model volume" >&2
  exit 1
fi

require_fixed 'umask 077' "$ci" "CI temporary configuration files must default to private permissions"
require_fixed "tunnel_env=\"\$(mktemp \"\$RUNNER_TEMP/llm-tunnel.XXXXXX\")\"" "$ci" "CI Tunnel environment file must use a unique path"
require_fixed "rendered=\"\$(mktemp \"\$RUNNER_TEMP/llm-compose.XXXXXX\")\"" "$ci" "CI rendered configuration must use a unique path"
require_fixed 'umask 077' "$deploy" "deploy temporary configuration files must default to private permissions"
require_fixed "rendered=\"\$(mktemp \"\$RUNNER_TEMP/llm-compose.XXXXXX\")\"" "$deploy" "deploy rendered configuration must use a unique path"
require_fixed "trap 'rm -f \"\$tunnel_env\" \"\$rendered\"' EXIT" "$ci" "CI rendered configuration must be deleted"
require_fixed "chmod 600 \"\$tunnel_env\" \"\$rendered\"" "$ci" "CI rendered configuration must stay private"
require_fixed "trap 'rm -f \"\$rendered\"' EXIT" "$deploy" "deploy rendered configuration must be deleted"
require_fixed "chmod 600 \"\$rendered\"" "$deploy" "deploy rendered configuration must stay private"
require_fixed "jq -e '.services.ollama.ports == null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify the rendered no-port configuration"
require_fixed "docker inspect -f '{{len .HostConfig.PortBindings}}' \"\$ollama_id\"" "$deploy" "deploy must inspect the final runtime port bindings"

printf '%s\n' "workflow security tests passed"
