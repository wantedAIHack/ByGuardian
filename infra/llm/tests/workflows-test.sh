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

assert_top_level_profile() {
  file=$1
  workflow_name=$2
  if ! awk '
    /^[[:space:]]*#/ || /^[[:space:]]*$/ {
      next
    }
    /^[^[:space:]]/ {
      if ($0 ~ /^name:[[:space:]]+[^[:space:]]/) {
        names++
      } else if ($0 == "on:") {
        triggers++
      } else if ($0 == "permissions:") {
        permissions++
      } else if ($0 == "concurrency:") {
        concurrency++
      } else if ($0 == "jobs:") {
        jobs++
      } else {
        invalid = 1
      }
    }
    END {
      if (names != 1 || triggers != 1 || permissions != 1 ||
          concurrency != 1 || jobs != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$file"; then
    printf '%s\n' "$workflow_name must use the canonical top-level workflow keys exactly once" >&2
    exit 1
  fi
}

assert_ci_triggers() {
  if ! awk '
    /^on:[[:space:]]*$/ {
      on_blocks++
      in_on = 1
      event = ""
      next
    }
    /^[^[:space:]#]/ {
      in_on = 0
      event = ""
    }
    in_on && /^  [^[:space:]#]/ {
      if ($0 == "  push:") {
        pushes++
        event = "push"
      } else if ($0 == "  pull_request:") {
        pull_requests++
        event = "pull_request"
      } else {
        invalid = 1
        event = ""
      }
      next
    }
    in_on && /^    [^[:space:]#]/ {
      key = substr($0, 5)
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key !~ /^[A-Za-z0-9_-]+$/) {
        invalid = 1
      }
      if (key == "branches") {
        branch_properties++
        if (event == "push" &&
            $0 == "    branches: [main, feat/llm-server]") {
          push_branches++
        } else {
          invalid = 1
        }
      }
    }
    END {
      if (on_blocks != 1 || pushes != 1 || pull_requests != 1 ||
          branch_properties != 1 || push_branches != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$ci"; then
    printf '%s\n' "CI triggers must be canonical push/pull_request events with push branches limited to main and feat/llm-server" >&2
    exit 1
  fi
}

assert_deploy_triggers() {
  if ! awk '
    /^on:[[:space:]]*$/ {
      on_blocks++
      in_on = 1
      event = ""
      next
    }
    /^[^[:space:]#]/ {
      in_on = 0
      event = ""
    }
    in_on && /^  [^[:space:]#]/ {
      if ($0 == "  push:") {
        pushes++
        event = "push"
      } else if ($0 == "  workflow_dispatch:") {
        dispatches++
        event = "workflow_dispatch"
      } else {
        invalid = 1
        event = ""
      }
      next
    }
    in_on && /^    [^[:space:]#]/ {
      key = substr($0, 5)
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key !~ /^[A-Za-z0-9_-]+$/) {
        invalid = 1
      }
      if (key == "branches") {
        branch_properties++
        if (event == "push" && $0 == "    branches: [main]") {
          push_branches++
        } else {
          invalid = 1
        }
      }
    }
    END {
      if (on_blocks != 1 || pushes != 1 || dispatches != 1 ||
          branch_properties != 1 || push_branches != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$deploy"; then
    printf '%s\n' "deploy triggers must be canonical main-only push and workflow_dispatch events" >&2
    exit 1
  fi
}

assert_ci_jobs() {
  if ! awk '
    /^jobs:[[:space:]]*$/ {
      job_sections++
      in_jobs = 1
      job = ""
      next
    }
    /^[^[:space:]#]/ {
      in_jobs = 0
      job = ""
    }
    in_jobs && /^  [^[:space:]#]/ {
      if ($0 == "  backend:") {
        backend_jobs++
        job = "backend"
      } else if ($0 == "  container:") {
        container_jobs++
        job = "container"
      } else {
        invalid = 1
        job = ""
      }
      next
    }
    in_jobs && /^    [^[:space:]#]/ {
      key = substr($0, 5)
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key != "runs-on" && key != "steps") {
        invalid = 1
      }
      if ($0 == "    runs-on: ubuntu-latest") {
        if (job == "backend") {
          backend_runners++
        } else if (job == "container") {
          container_runners++
        } else {
          invalid = 1
        }
      } else if (key == "runs-on") {
        invalid = 1
      }
    }
    END {
      if (job_sections != 1 || backend_jobs != 1 || container_jobs != 1 ||
          backend_runners != 1 || container_runners != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$ci"; then
    printf '%s\n' "CI jobs must be exactly canonical backend/container jobs, each on ubuntu-latest with canonical job keys" >&2
    exit 1
  fi
}

assert_only_deploy_job() {
  if ! awk '
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
    in_jobs && /^  [^[:space:]#]/ {
      if ($0 == "  deploy:") {
        deploy_jobs++
        in_deploy = 1
      } else {
        invalid = 1
        in_deploy = 0
      }
      next
    }
    in_jobs && /^    [^[:space:]#]/ {
      key = substr($0, 5)
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (!in_deploy || (key != "if" && key != "runs-on" &&
          key != "environment" && key != "timeout-minutes" &&
          key != "steps")) {
        invalid = 1
      }
    }
    END {
      if (job_sections != 1 || deploy_jobs != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$deploy"; then
    printf '%s\n' "deploy workflow must contain only one canonical jobs.deploy block with canonical job keys" >&2
    exit 1
  fi
}

assert_actions() {
  file=$1
  workflow_name=$2
  mode=$3
  if ! awk -v mode="$mode" '
    /^jobs:[[:space:]]*$/ {
      in_jobs = 1
      in_steps = 0
      next
    }
    /^[^[:space:]#]/ {
      in_jobs = 0
      in_steps = 0
    }
    in_jobs && /^  [^[:space:]#]/ {
      in_steps = 0
      next
    }
    in_jobs && /^    [^[:space:]#]/ {
      in_steps = ($0 == "    steps:")
      next
    }
    in_steps && /^      - [^[:space:]]/ {
      if ($0 !~ /^      - name:[[:space:]]+[^[:space:]]/) {
        invalid = 1
      }
      next
    }
    in_steps && /^        [^[:space:]#]/ {
      key = substr($0, 9)
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key != "uses" && key != "with" && key != "working-directory" &&
          key != "run" && key != "env") {
        invalid = 1
      }
      if (key == "uses") {
        actions++
        if ($0 ~ /^        uses: actions\/checkout@11d5960a326750d5838078e36cf38b85af677262([[:space:]]+#.*)?$/) {
          checkouts++
        } else if ($0 ~ /^        uses: actions\/setup-java@cf277c60eb25467037889841efdb72551f06f6c3([[:space:]]+#.*)?$/) {
          java_setups++
        } else {
          invalid = 1
        }
      }
    }
    END {
      if (mode == "ci") {
        if (actions != 3 || checkouts != 2 || java_setups != 1) {
          invalid = 1
        }
      } else if (actions != 1 || checkouts != 1 || java_setups != 0) {
        invalid = 1
      }
      if (invalid != 0) {
        exit 1
      }
    }
  ' "$file"; then
    printf '%s\n' "$workflow_name action steps must use canonical keys and only approved immutable action SHAs" >&2
    exit 1
  fi
}

assert_checkout_controls() {
  file=$1
  workflow_name=$2
  mode=$3
  if ! awk -v mode="$mode" '
    function finish_step() {
      if (step_open && checkout) {
        checkout_steps++
        if (with_blocks != 1 || persist_credentials != 1) {
          invalid = 1
        }
        if (mode == "deploy" && checkout_refs != 1) {
          invalid = 1
        }
      }
      step_open = 0
      checkout = 0
      in_with = 0
      with_blocks = 0
      persist_credentials = 0
      checkout_refs = 0
    }
    /^jobs:[[:space:]]*$/ {
      finish_step()
      in_jobs = 1
      in_steps = 0
      next
    }
    /^[^[:space:]#]/ {
      finish_step()
      in_jobs = 0
      in_steps = 0
    }
    in_jobs && /^  [^[:space:]#]/ {
      finish_step()
      in_steps = 0
      next
    }
    in_jobs && /^    [^[:space:]#]/ {
      finish_step()
      in_steps = ($0 == "    steps:")
      next
    }
    in_steps && /^      - [^[:space:]]/ {
      finish_step()
      step_open = 1
      next
    }
    step_open && /^        [^[:space:]#]/ {
      in_with = 0
      if ($0 ~ /^        uses: actions\/checkout@11d5960a326750d5838078e36cf38b85af677262([[:space:]]+#.*)?$/) {
        checkout = 1
      }
      if ($0 == "        with:") {
        in_with = 1
        with_blocks++
      }
      next
    }
    step_open && in_with && /^          [^[:space:]#]/ {
      key = substr($0, 11)
      sub(/:.*/, "", key)
      sub(/[[:space:]]+$/, "", key)
      if (key !~ /^[A-Za-z0-9_-]+$/) {
        invalid = 1
      }
      if ($0 == "          persist-credentials: false") {
        persist_credentials++
      } else if ($0 == "          ref: ${{ github.sha }}") {
        checkout_refs++
      }
    }
    END {
      finish_step()
      expected_checkouts = (mode == "ci" ? 2 : 1)
      if (checkout_steps != expected_checkouts || invalid != 0) {
        exit 1
      }
    }
  ' "$file"; then
    if [ "$mode" = "deploy" ]; then
      printf '%s\n' "$workflow_name checkout must bind canonical with.persist-credentials: false and ref: github.sha in its own step" >&2
    else
      printf '%s\n' "$workflow_name checkout steps must each bind canonical with.persist-credentials: false in their own step" >&2
    fi
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

assert_top_level_profile "$ci" "CI"
assert_top_level_profile "$deploy" "deploy"
assert_ci_triggers
assert_deploy_triggers
assert_exact_permissions "$ci" "CI"
assert_exact_permissions "$deploy" "deploy"
forbid_ere '[$][{][{][^}]*secrets([^[:alnum:]_]|$)' "$ci" "CI must not consume any secrets context"
forbid_ere '[$][{][{][^}]*secrets([^[:alnum:]_]|$)' "$deploy" "deploy must not consume any secrets context"
forbid_ere '(^|[;&|[:space:]])set[[:space:]]+(-[A-Za-z]*x[A-Za-z]*|-o[[:space:]]+xtrace)([;&|[:space:]]|$)' "$ci" "CI must not enable shell xtrace"
forbid_ere '(^|[;&|[:space:]])set[[:space:]]+(-[A-Za-z]*x[A-Za-z]*|-o[[:space:]]+xtrace)([;&|[:space:]]|$)' "$deploy" "deploy must not enable shell xtrace"
assert_ci_jobs
assert_only_deploy_job
assert_actions "$ci" "CI" "ci"
assert_actions "$deploy" "deploy" "deploy"
assert_checkout_controls "$ci" "CI" "ci"
assert_checkout_controls "$deploy" "deploy" "deploy"

forbid_ere '^[[:space:]]+[^#].*model-init' "$ci" "CI must not pull or initialize the model"
forbid_ere '^[[:space:]]+[^#].*ollama pull' "$ci" "CI must not pull the model"

require_deploy_property "    if: \${{ github.ref == 'refs/heads/main' && vars.LLM_DEPLOY_ENABLED == 'true' }}" "deploy gate must be an active property of jobs.deploy"
require_deploy_property '    runs-on: [self-hosted, linux, llm]' "deploy runner labels must be an active property of jobs.deploy"
require_deploy_property '    environment: llm-laptop' "deploy environment must be an active property of jobs.deploy"
require_deploy_property '    timeout-minutes: 90' "deploy timeout must be an active property of jobs.deploy"
require_line '  group: llm-laptop-deploy' "$deploy" "deploy concurrency group is missing"
require_line '  cancel-in-progress: false' "$deploy" "deploy concurrency must not cancel an active deployment"
forbid_ere '^[[:space:]]+ref: main[[:space:]]*$' "$deploy" "deploy must not check out moving main"

require_line '          test -r /etc/nextvisit/llm.env' "$deploy" "deploy must require the host-owned environment file"
require_line "          test \"\$(stat -c '%a' /etc/nextvisit/llm.env)\" = 600" "$deploy" "deploy must verify environment file permissions"
require_line '          NEXTVISIT_LLM_ENV_FILE: /etc/nextvisit/llm.env' "$deploy" "deploy must pass only the host-owned environment file"
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
