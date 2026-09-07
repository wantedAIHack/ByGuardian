#!/bin/sh
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
ci="$repo_root/.github/workflows/llm-ci.yml"
deploy="$repo_root/.github/workflows/llm-deploy.yml"

require_line() {
  expected=$1
  file=$2
  message=$3
  if ! grep -F -x "$expected" "$file" >/dev/null; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

require_line_count() {
  expected_count=$1
  expected=$2
  file=$3
  message=$4
  actual_count="$(grep -F -x -c "$expected" "$file" || true)"
  if [ "$actual_count" -ne "$expected_count" ]; then
    printf '%s (expected %s active lines, found %s)\n' \
      "$message" "$expected_count" "$actual_count" >&2
    exit 1
  fi
}

require_ere_count() {
  expected_count=$1
  pattern=$2
  file=$3
  message=$4
  actual_count="$(grep -E -c "$pattern" "$file" || true)"
  if [ "$actual_count" -ne "$expected_count" ]; then
    printf '%s (expected %s active lines, found %s)\n' \
      "$message" "$expected_count" "$actual_count" >&2
    exit 1
  fi
}

forbid_ere() {
  pattern=$1
  file=$2
  message=$3
  if awk '
    BEGIN {
      pattern = ARGV[1]
      ARGV[1] = ""
    }
    /^[[:space:]]*#/ {
      next
    }
    $0 ~ pattern {
      found = 1
      exit
    }
    END {
      exit found ? 0 : 1
    }
  ' "$pattern" "$file"; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

require_file() {
  file=$1
  workflow_name=$2
  if [ ! -f "$file" ]; then
    printf '%s workflow is missing: %s\n' "$workflow_name" "$file" >&2
    exit 1
  fi
}

assert_exact_permissions() {
  file=$1
  workflow_name=$2
  if ! awk '
    BEGIN {
      top_blocks = 0
      contents_read = 0
      invalid = 0
      in_top_permissions = 0
      single_quote = sprintf("%c", 39)
    }
    /^permissions:[[:space:]]*$/ {
      top_blocks++
      in_top_permissions = 1
      next
    }
    /^[^[:space:]#]/ {
      key = $0
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key == "permissions" || key == "\"permissions\"" ||
          key == single_quote "permissions" single_quote) {
        invalid = 1
      }
      in_top_permissions = 0
    }
    /^    [^#]/ {
      key = substr($0, 5)
      sub(/[[:space:]]*:.*/, "", key)
      if (key == "permissions" || key == "\"permissions\"" ||
          key == single_quote "permissions" single_quote) {
        invalid = 1
      }
    }
    in_top_permissions && $0 !~ /^[[:space:]]*(#|$)/ {
      if ($0 == "  contents: read") {
        contents_read++
      } else {
        invalid = 1
      }
    }
    END {
      if (top_blocks != 1 || contents_read != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$file"; then
    printf '%s\n' "$workflow_name permissions must be exactly top-level contents: read with no job-level override" >&2
    exit 1
  fi
}

assert_only_deploy_job() {
  if ! awk '
    /^jobs:[[:space:]]*$/ {
      job_sections++
      in_jobs = 1
      next
    }
    /^[^[:space:]#]/ {
      in_jobs = 0
    }
    in_jobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
      jobs++
      if ($0 == "  deploy:") {
        deploy_jobs++
      }
    }
    END {
      if (job_sections != 1 || jobs != 1 || deploy_jobs != 1) {
        exit 1
      }
    }
  ' "$deploy"; then
    printf '%s\n' "deploy workflow must contain exactly one jobs.deploy block" >&2
    exit 1
  fi
}

require_deploy_property() {
  expected=$1
  message=$2
  if ! awk -v expected="$expected" '
    /^jobs:[[:space:]]*$/ {
      job_sections++
      in_jobs = 1
      in_deploy = 0
      next
    }
    /^[^[:space:]#]/ {
      in_jobs = 0
      in_deploy = 0
    }
    in_jobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
      in_deploy = ($0 == "  deploy:")
      if (in_deploy) {
        deploy_jobs++
      }
      next
    }
    in_deploy && $0 == expected {
      matches++
    }
    END {
      if (job_sections != 1 || deploy_jobs != 1 || matches != 1) {
        exit 1
      }
    }
  ' "$deploy"; then
    printf '%s\n' "$message" >&2
    exit 1
  fi
}

require_file "$ci" "CI"
require_file "$deploy" "deploy"

require_line '  push:' "$ci" "CI must run on pushes"
require_line '  pull_request:' "$ci" "CI must run on pull requests"
require_line '    branches: [main, feat/llm-server]' "$ci" "CI push branches are not constrained"
assert_exact_permissions "$ci" "CI"
assert_exact_permissions "$deploy" "deploy"
require_ere_count 2 '^        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262([[:space:]]+#.*)?$' "$ci" "CI checkout actions must use the approved immutable SHA"
require_ere_count 1 '^        uses: actions/checkout@11d5960a326750d5838078e36cf38b85af677262([[:space:]]+#.*)?$' "$deploy" "deploy checkout action must use the approved immutable SHA"
require_ere_count 1 '^        uses: actions/setup-java@cf277c60eb25467037889841efdb72551f06f6c3([[:space:]]+#.*)?$' "$ci" "Java setup action must use the approved immutable SHA"

require_ere_count 2 '^    runs-on:' "$ci" "CI must contain exactly two jobs"
require_line_count 2 '    runs-on: ubuntu-latest' "$ci" "every CI job must use a GitHub-hosted Ubuntu runner"
forbid_ere '^[[:space:]]+runs-on:.*self-hosted' "$ci" "CI must not use the laptop runner"
forbid_ere '^  pull_request_target:' "$ci" "pull_request_target is forbidden"
forbid_ere '^  pull_request_target:' "$deploy" "pull_request_target is forbidden"
forbid_ere '^  workflow_dispatch:' "$ci" "CI must use only push and pull_request events"
forbid_ere '^[[:space:]]+[^#].*model-init' "$ci" "CI must not pull or initialize the model"
forbid_ere '^[[:space:]]+[^#].*ollama pull' "$ci" "CI must not pull the model"

require_line_count 2 '          persist-credentials: false' "$ci" "each CI checkout must disable stored credentials"
require_line_count 1 '          persist-credentials: false' "$deploy" "deploy checkout must disable stored credentials"

if grep -E '^[[:space:]]*uses:' "$ci" "$deploy" \
  | grep -Ev 'uses: actions/(checkout@11d5960a326750d5838078e36cf38b85af677262|setup-java@cf277c60eb25467037889841efdb72551f06f6c3)([[:space:]]|$)' >/dev/null; then
  printf '%s\n' "every action must use an approved immutable SHA" >&2
  exit 1
fi

require_line '    branches: [main]' "$deploy" "deploy pushes must be limited to main"
require_line '  workflow_dispatch:' "$deploy" "deploy must allow an explicit manual dispatch"
assert_only_deploy_job
require_deploy_property "    if: \${{ github.ref == 'refs/heads/main' && vars.LLM_DEPLOY_ENABLED == 'true' }}" "deploy gate must be an active property of jobs.deploy"
require_deploy_property '    runs-on: [self-hosted, linux, llm]' "deploy runner labels must be an active property of jobs.deploy"
require_deploy_property '    environment: llm-laptop' "deploy environment must be an active property of jobs.deploy"
require_deploy_property '    timeout-minutes: 90' "deploy timeout must be an active property of jobs.deploy"
require_line '  group: llm-laptop-deploy' "$deploy" "deploy concurrency group is missing"
require_line '  cancel-in-progress: false' "$deploy" "deploy concurrency must not cancel an active deployment"
require_line "          ref: \${{ github.sha }}" "$deploy" "deploy must check out the triggering commit"
forbid_ere '^  pull_request:' "$deploy" "deploy must not accept pull requests"
forbid_ere '^[[:space:]]+ref: main[[:space:]]*$' "$deploy" "deploy must not check out moving main"

require_line '          test -r /etc/nextvisit/llm.env' "$deploy" "deploy must require the host-owned environment file"
require_line "          test \"\$(stat -c '%a' /etc/nextvisit/llm.env)\" = 600" "$deploy" "deploy must verify environment file permissions"
require_line '          NEXTVISIT_LLM_ENV_FILE: /etc/nextvisit/llm.env' "$deploy" "deploy must pass only the host-owned environment file"
forbid_ere '^[[:space:]]+[^#].*secrets\.' "$deploy" "deploy must not consume repository secret content"
forbid_ere '^[[:space:]]+[^#].*set -x' "$deploy" "deploy must not enable shell tracing"
forbid_ere '(docker( compose)?[[:space:]].*logs|(^|[[:space:]])(cat|head|tail|printenv)[[:space:]].*llm\.env)' "$deploy" "deploy must not dump container logs or environment contents"

require_line '          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init' "$deploy" "model initialization must stay inside the gated deploy job"
forbid_ere '(docker[[:space:]].*prune|docker[[:space:]]+volume[[:space:]]+rm|docker[[:space:]]+compose.*down.*(--volumes|-v)|rm[[:space:]].*ollama-data)' "$deploy" "deploy must preserve the model volume"

require_line '          umask 077' "$ci" "CI temporary configuration files must default to private permissions"
require_line "          tunnel_env=\"\$(mktemp \"\$RUNNER_TEMP/llm-tunnel.XXXXXX\")\"" "$ci" "CI Tunnel environment file must use a unique path"
require_line "          rendered=\"\$(mktemp \"\$RUNNER_TEMP/llm-compose.XXXXXX\")\"" "$ci" "CI rendered configuration must use a unique path"
require_line '          umask 077' "$deploy" "deploy temporary configuration files must default to private permissions"
require_line "          rendered=\"\$(mktemp \"\$RUNNER_TEMP/llm-compose.XXXXXX\")\"" "$deploy" "deploy rendered configuration must use a unique path"
require_line "          trap 'rm -f \"\$tunnel_env\" \"\$rendered\"' EXIT" "$ci" "CI rendered configuration must be deleted"
require_line "          chmod 600 \"\$tunnel_env\" \"\$rendered\"" "$ci" "CI rendered configuration must stay private"
require_line "          trap 'rm -f \"\$rendered\"' EXIT" "$deploy" "deploy rendered configuration must be deleted"
require_line "          chmod 600 \"\$rendered\"" "$deploy" "deploy rendered configuration must stay private"
require_line "          jq -e '.services.ollama.ports == null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify the rendered no-port configuration"
require_line "          test \"\$(docker inspect -f '{{len .HostConfig.PortBindings}}' \"\$ollama_id\")\" = 0" "$deploy" "deploy must inspect the final runtime port bindings"

printf '%s\n' "workflow security tests passed"
