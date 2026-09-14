#!/bin/sh
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
ci="$repo_root/.github/workflows/ci.yml"
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
      if (key != "branches") { invalid = 1 }
      if (key == "branches") {
        branch_properties++
        if (event == "push" &&
            $0 == "    branches: [main]") {
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
    printf '%s\n' "CI triggers must be canonical push/pull_request events with push branches limited to main without path filters" >&2
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

require_ci_property() {
  job_name=$1 expected=$2
  if ! awk -v job_name="$job_name" -v expected="$expected" '
    /^  [^[:space:]#]/ { in_job = ($0 == "  " job_name ":") }
    in_job && $0 == expected { matches++ }
    END { exit matches != 1 }
  ' "$ci"; then
    printf 'CI job %s must contain its own property: %s\n' "$job_name" "$expected" >&2
    exit 1
  fi
}

require_secret_gate_property() {
  if ! awk -v expected="$1" '
    /^      - name:/ { in_gate = ($0 == "      - name: Scan every secret status with exact historical occurrence checks") }
    in_gate && $0 == expected { matches++ }
    END { exit matches != 1 }
  ' "$ci"; then
    printf '%s\n' "secret candidate gate must bind its own fail-closed range and command" >&2
    exit 1
  fi
}

assert_ci_jobs() {
  if ! awk '
    /^jobs:$/ { in_jobs = 1; next }
    /^[^[:space:]#]/ { in_jobs = 0 }
    in_jobs && /^  [^[:space:]#]/ {
      job = substr($0, 3); sub(/:$/, "", job)
      if (job !~ /^(changes|frontend|backend|infra|integration|security|ci-gate)$/ || seen[job]++) invalid = 1
      jobs++; next
    }
    in_jobs && /^    [^[:space:]#]/ {
      key = substr($0, 5); sub(/:.*/, "", key)
      if (key !~ /^(runs-on|steps|needs|if|outputs|timeout-minutes)$/) invalid = 1
      if (key == "runs-on") {
        if ($0 != "    runs-on: ubuntu-latest" || runners[job]++) invalid = 1
        total_runners++
      }
    }
    in_jobs && /^      (group|labels):/ { invalid = 1 }
    END { exit jobs != 7 || total_runners != 7 || invalid }
  ' "$ci"; then
    printf '%s\n' "CI must have exactly seven canonical jobs on hosted Ubuntu runners" >&2
    exit 1
  fi
  for area in frontend backend infra integration; do
    require_ci_property "$area" "    if: \${{ needs.changes.outputs.$area == 'true' }}"
    require_ci_property "$area" '    needs: changes'
  done
  require_ci_property security '    needs: changes'
  require_ci_property security "    if: \${{ always() }}"
  require_ci_property ci-gate "    if: \${{ always() }}"
  require_ci_property ci-gate '    needs: [changes, frontend, backend, infra, integration, security]'
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
      job = $0
      next
    }
    in_jobs && /^    [^[:space:]#]/ {
      in_steps = ($0 == "    steps:")
      next
    }
    in_steps && /^      - [^[:space:]]/ {
      step = $0
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
          key != "run" && key != "env" && key != "id" && key != "shell" && key != "if") {
        invalid = 1
      }
      if (key == "if" && (mode != "ci" || job != "  security:" ||
          step != "      - name: Scan every secret status with exact historical occurrence checks" ||
          $0 != "        if: ${{ always() }}")) invalid = 1
      if (key == "uses") {
        actions++
        if ($0 ~ /^        uses: actions\/checkout@11d5960a326750d5838078e36cf38b85af677262([[:space:]]+#.*)?$/) {
          checkouts++
        } else if ($0 ~ /^        uses: actions\/setup-java@cf277c60eb25467037889841efdb72551f06f6c3([[:space:]]+#.*)?$/) {
          java_setups++
        } else if ($0 ~ /^        uses: actions\/setup-node@49933ea5288caeca8642d1e84afbd3f7d6820020([[:space:]]+#.*)?$/) {
          node_setups++
        } else if ($0 ~ /^        uses: trufflesecurity\/trufflehog@363923b901c911a9164f50b6c423f47c15372b1c([[:space:]]+#.*)?$/) {
          scanners++
        } else {
          invalid = 1
        }
      }
    }
    END {
      if (mode == "ci") {
        if (actions != 12 || checkouts != 6 || java_setups != 2 || node_setups != 3 || scanners != 1) {
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
        if (checkout_refs != 1 || (mode == "ci" && full_history != 1)) {
          invalid = 1
        }
      }
      step_open = 0
      checkout = 0
      in_with = 0
      with_blocks = 0
      persist_credentials = 0
      checkout_refs = 0
      full_history = 0
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
      } else if ($0 == "          fetch-depth: 0") {
        full_history++
      }
    }
    END {
      finish_step()
      expected_checkouts = (mode == "ci" ? 6 : 1)
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

assert_deploy_runner_group() {
  if ! awk '
    /^jobs:[[:space:]]*$/ {
      job_sections++
      in_jobs = 1
      in_deploy = 0
      in_runner = 0
      next
    }
    /^[^[:space:]#]/ {
      in_jobs = 0
      in_deploy = 0
      in_runner = 0
    }
    in_jobs && /^  [A-Za-z0-9_-]+:[[:space:]]*$/ {
      in_deploy = ($0 == "  deploy:")
      in_runner = 0
      if (in_deploy) {
        deploy_jobs++
      }
      next
    }
    in_deploy && /^    runs-on:/ {
      runner_properties++
      if ($0 == "    runs-on:") {
        in_runner = 1
      } else {
        invalid = 1
        in_runner = 0
      }
      next
    }
    in_deploy && /^    [^[:space:]#]/ {
      in_runner = 0
      next
    }
    in_runner && /^      [^[:space:]#]/ {
      if ($0 == "      group: llm-production") {
        groups++
      } else if ($0 == "      labels: [self-hosted, linux, llm]") {
        labels++
      } else {
        invalid = 1
      }
      next
    }
    END {
      if (job_sections != 1 || deploy_jobs != 1 || runner_properties != 1 ||
          groups != 1 || labels != 1 || invalid != 0) {
        exit 1
      }
    }
  ' "$deploy"; then
    printf '%s\n' "deploy runner must use only group llm-production with labels [self-hosted, linux, llm]" >&2
    exit 1
  fi
}

require_file "$ci" "CI"
workflow_count=0
for workflow in "$repo_root"/.github/workflows/*.yml "$repo_root"/.github/workflows/*.yaml; do
  [ -e "$workflow" ] || continue
  case "$workflow" in "$ci"|"$deploy") ;; *) printf '%s\n' "unapproved workflow" >&2; exit 1 ;; esac
  workflow_count=$((workflow_count + 1))
done
require_file "$ci" "CI"
test "$workflow_count" -eq 2
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
forbid_ere 'pull_request_target' "$ci" "pull_request_target is forbidden"
forbid_ere 'pull_request_target' "$deploy" "pull_request_target is forbidden"
forbid_ere 'self-hosted' "$ci" "CI must never use self-hosted runners"
assert_ci_jobs
assert_only_deploy_job
assert_actions "$ci" "CI" "ci"
assert_actions "$deploy" "deploy" "deploy"
assert_checkout_controls "$ci" "CI" "ci"
assert_checkout_controls "$deploy" "deploy" "deploy"

forbid_ere '^[[:space:]]+[^#].*model-init' "$ci" "CI must not pull or initialize the model"
forbid_ere '^[[:space:]]+[^#].*ollama pull' "$ci" "CI must not pull the model"

require_deploy_property "    if: \${{ github.ref == 'refs/heads/main' && vars.LLM_DEPLOY_ENABLED == 'true' }}" "deploy gate must be an active property of jobs.deploy"
assert_deploy_runner_group
require_deploy_property '    environment: llm-laptop' "deploy environment must be an active property of jobs.deploy"
require_deploy_property '    timeout-minutes: 90' "deploy timeout must be an active property of jobs.deploy"
require_line '  group: llm-laptop-deploy' "$deploy" "deploy concurrency group is missing"
require_line '  cancel-in-progress: false' "$deploy" "deploy concurrency must not cancel an active deployment"
forbid_ere '^[[:space:]]+ref: main[[:space:]]*$' "$deploy" "deploy must not check out moving main"

require_line '      - name: Verify local Tunnel token file' "$deploy" "deploy must verify the tunnel token file before use"
require_line '        run: infra/llm/scripts/verify-tunnel-token-file.sh /etc/nextvisit/llm.token' "$deploy" "deploy must verify the production tunnel token file at its exact path"
require_line '          NEXTVISIT_LLM_TOKEN_FILE: /etc/nextvisit/llm.token' "$deploy" "deploy must pass only the host-owned raw token file"
forbid_ere '(docker( compose)?[[:space:]].*logs|(^|[[:space:]])(cat|head|tail|printenv)[[:space:]].*llm\.(env|token))' "$deploy" "deploy must not dump container logs or token file contents"
forbid_ere 'env_file' "$ci" "CI must not consume an env_file"
forbid_ere 'env_file' "$deploy" "deploy must not consume an env_file"
forbid_ere 'TUNNEL_TOKEN' "$ci" "CI must not reference the legacy TUNNEL_TOKEN literal"
forbid_ere 'TUNNEL_TOKEN' "$deploy" "deploy must not reference the legacy TUNNEL_TOKEN literal"
forbid_ere 'NEXTVISIT_LLM_ENV_FILE' "$ci" "CI must not reference the legacy NEXTVISIT_LLM_ENV_FILE variable"
forbid_ere 'NEXTVISIT_LLM_ENV_FILE' "$deploy" "deploy must not reference the legacy NEXTVISIT_LLM_ENV_FILE variable"
forbid_ere '/etc/nextvisit/llm\.env' "$ci" "CI must not reference the legacy Tunnel environment file path"
forbid_ere '/etc/nextvisit/llm\.env' "$deploy" "deploy must not reference the legacy Tunnel environment file path"
forbid_ere '(^|[^-])--token([^-[:alnum:]_]|$)' "$ci" "CI must never pass a bare --token argument lacking -file"
forbid_ere '(^|[^-])--token([^-[:alnum:]_]|$)' "$deploy" "deploy must never pass a bare --token argument lacking -file"

require_line '          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml run --rm model-init' "$deploy" "model initialization must stay inside the gated deploy job"
forbid_ere '(docker[[:space:]].*prune|docker[[:space:]]+volume[[:space:]]+rm|docker[[:space:]]+compose.*down.*(--volumes|-v)|rm[[:space:]].*ollama-data)' "$deploy" "deploy must preserve the model volume"

require_line "          umask 077" "$ci" "CI temporary configuration files must default to private permissions"
require_line "          token_file=\"\$(mktemp \"\$RUNNER_TEMP/llm-tunnel-token.XXXXXX\")\"" "$ci" "CI Tunnel token file must use a unique path"
require_line "          rendered=\"\$(mktemp \"\$RUNNER_TEMP/llm-compose.XXXXXX\")\"" "$ci" "CI rendered configuration must use a unique path"
require_line '          umask 077' "$deploy" "deploy temporary configuration files must default to private permissions"
require_line "          rendered=\"\$(mktemp \"\$RUNNER_TEMP/llm-compose.XXXXXX\")\"" "$deploy" "deploy rendered configuration must use a unique path"
require_line "          trap 'rm -f \"\$token_file\" \"\$rendered\"' EXIT" "$ci" "CI rendered configuration must be deleted"
require_line "          chmod 600 \"\$token_file\" \"\$rendered\"" "$ci" "CI rendered configuration must stay private"
require_line "          ! jq -e '[.services[] | ((.environment // {}) | to_entries[]?.value), ((.command // [])[]), ((.labels // {}) | to_entries[]?.value)] | any(. == \"ci-render-only-token-sentinel\")' \"\$rendered\" >/dev/null" "$ci" "CI must structurally verify the raw token never reaches Env/Cmd/Labels"
require_line "          trap 'rm -f \"\$rendered\"' EXIT" "$deploy" "deploy rendered configuration must be deleted"
require_line "          chmod 600 \"\$rendered\"" "$deploy" "deploy rendered configuration must stay private"
require_line "          jq -e '.services.ollama.ports == null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify the rendered no-port configuration"
require_line "          test \"\$(docker inspect -f '{{len .HostConfig.PortBindings}}' \"\$ollama_id\")\" = 0" "$deploy" "deploy must inspect the final runtime port bindings"

# The deploy job re-runs the same offline policy/unit test suite on the exact
# commit it just checked out -- defense in depth against a compromise between
# CI's check of a different ref and this run's actual, privileged execution.
require_line '      - name: Run offline infra test suite' "$deploy" "deploy must re-run the offline infra test suite on the checked-out commit"
require_line '          for test in infra/llm/tests/*.sh; do' "$deploy" "deploy offline test suite must iterate every infra/llm test"
require_line "            sh \"\$test\"" "$deploy" "deploy offline test suite must execute each test with sh"
require_line '          done' "$deploy" "deploy offline test suite loop must be closed"

# The glob loop above runs whatever file a commit places under
# infra/llm/tests/, in glob order, as the root-equivalent runner -- including
# a same-commit test file with an arbitrary run body. The three policy suites
# must therefore run first, by explicit name, so they always execute before
# any such file could pre-empt them.
require_line '          sh infra/llm/tests/workflows-test.sh' "$deploy" "deploy offline test suite must run workflows-test.sh by explicit name before the glob loop"
require_line '          sh infra/llm/tests/workflows-runner-group-test.sh' "$deploy" "deploy offline test suite must run workflows-runner-group-test.sh by explicit name before the glob loop"
require_line '          sh infra/llm/tests/llm-deploy-mutation-test.sh' "$deploy" "deploy offline test suite must run llm-deploy-mutation-test.sh by explicit name before the glob loop"

glob_loop_line="$(grep -Fnx '          for test in infra/llm/tests/*.sh; do' "$deploy" | head -1 | cut -d: -f1)"
for named_line in \
  '          sh infra/llm/tests/workflows-test.sh' \
  '          sh infra/llm/tests/workflows-runner-group-test.sh' \
  '          sh infra/llm/tests/llm-deploy-mutation-test.sh'; do
  named_suite_line="$(grep -Fnx "$named_line" "$deploy" | head -1 | cut -d: -f1)"
  if [ "$named_suite_line" -ge "$glob_loop_line" ]; then
    printf '%s\n' "deploy must run the named policy test suites before the arbitrary infra/llm/tests/*.sh glob loop" >&2
    exit 1
  fi
done

require_line "        run: infra/llm/scripts/stage-runtime.sh \"\$GITHUB_SHA\" \"\$GITHUB_WORKSPACE\"" "$deploy" "deploy must stage the exact checked-out commit before operating on /opt/nextvisit/llm/current"
if [ "$(grep -Fxc '        working-directory: /opt/nextvisit/llm/current' "$deploy")" -ne 2 ]; then
  printf '%s\n' "deploy compose steps must operate only inside the staged /opt/nextvisit/llm/current release" >&2
  exit 1
fi
forbid_ere 'working-directory:[[:space:]]*([$][{][{][[:space:]]*github\.workspace|[$]GITHUB_WORKSPACE)' "$deploy" "deploy must never operate directly from the Actions checkout workspace"

# The Tunnel token is a Compose file-secret; these lines prove -- structurally,
# on the exact rendered final configuration -- that it can never be sourced
# from (or leak into) any service's environment, command, or labels.
require_line "          jq -e '.secrets.tunnel_token.file != null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify the Tunnel secret is file-sourced"
require_line "          jq -e '.secrets.tunnel_token.environment == null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify the Tunnel secret is never environment-sourced"
require_line "          jq -e '.services.cloudflared.environment == null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify cloudflared defines no environment map"
require_line "          jq -e '.services.cloudflared.labels == null' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify cloudflared defines no custom labels"
require_line "          jq -e '.services.cloudflared.command == [\"tunnel\",\"--no-autoupdate\",\"run\",\"--token-file\",\"/run/secrets/tunnel_token\"]' \"\$rendered\" >/dev/null" "$deploy" "deploy must verify the exact rendered cloudflared command"
require_line "          cloudflared_id=\"\$(docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml ps -q cloudflared)\"" "$deploy" "deploy must resolve the running cloudflared container id"
require_line "          test -n \"\$cloudflared_id\"" "$deploy" "deploy must confirm the cloudflared container exists"
require_line "          test \"\$(docker inspect -f '{{json .Config.Cmd}}' \"\$cloudflared_id\")\" = '[\"tunnel\",\"--no-autoupdate\",\"run\",\"--token-file\",\"/run/secrets/tunnel_token\"]'" "$deploy" "deploy must inspect the final runtime cloudflared command"

# The reboot-recovery unit is installed and enabled by the repository owner,
# never by this job (which must stay unprivileged); the job only proves that
# state was not disturbed, using the unprivileged read subcommands.
require_line '      - name: Verify recovery unit remains enabled and active' "$deploy" "deploy must verify the recovery unit remains enabled and active"
require_line "          test \"\$(systemctl is-enabled nextvisit-llm.service)\" = enabled" "$deploy" "deploy must verify the recovery unit is enabled"
require_line "          test \"\$(systemctl is-active nextvisit-llm.service)\" = active" "$deploy" "deploy must verify the recovery unit is active"
forbid_ere '(^|[[:space:]])systemctl[[:space:]]+(start|stop|restart|reload|enable|disable|mask|unmask|daemon-reload|edit|set-property|kill)([[:space:]]|$)' "$deploy" "deploy must never call a privileged systemctl subcommand"

# Scanner references, input binding, and privacy are part of the deployment boundary.
require_line "            ghcr.io/google/osv-scanner:v2.5.1@sha256:1547b7c2783d4f266b24fe86ab4dfc18d058588244c58384ac9f56dddb304511 \\" "$ci" "OSV scanner image must match the audited digest"
require_line "            scan source --recursive /src" "$ci" "OSV must scan the full dependency inventory"
require_line "          docker run --rm --platform linux/amd64 --cap-drop ALL \\" "$ci" "OSV must drop capabilities on the audited platform"
require_line "            --security-opt no-new-privileges --read-only \\" "$ci" "OSV must prevent privilege escalation and writes"
require_line "            --tmpfs /tmp:rw,noexec,nosuid,size=256m --env XDG_CACHE_HOME=/tmp/osv-cache \\" "$ci" "OSV cache must be bounded and ephemeral"
require_line "            --volume \"\$GITHUB_WORKSPACE:/src:ro\" \\" "$ci" "OSV checkout must be read-only"
require_line "          version: 3.97.4@sha256:d366c22dadaeaf5ce5686035028deb97d365233cd7c9955f424dac4612c3ef25" "$ci" "TruffleHog version must match the audited digest"
# `--fail` is NOT in extra_args: the pinned action appends its own, and
# trufflehog aborts on a repeated flag, which failed every hosted run.
# The blocking property is still asserted -- by the action supplying it --
# so what this pins is that nobody re-adds the duplicate or drops
# --results=verified / --fail-on-scan-errors, which are what make the scan
# gate rather than merely report.
require_line "          extra_args: --results=verified --no-update --fail-on-scan-errors --log-level=-1" "$ci" "the scan must report only verified results and fail closed on scan errors"
forbid_ere 'extra_args:.*[[:space:]]--fail([[:space:]]|$)' "$ci" "extra_args must not repeat the action's own --fail flag"
require_ci_property security '        run: node --test scripts/ci/secret-findings-test.mjs scripts/ci/secret-findings-mutations-test.mjs'
require_secret_gate_property '        run: node scripts/ci/secret-findings-gate.mjs'
require_secret_gate_property "        if: \${{ always() }}"
require_secret_gate_property "          BASE_SHA: \${{ needs.changes.outputs.scan_base }}"
require_secret_gate_property "          HEAD_SHA: \${{ needs.changes.outputs.scan_head }}"
require_line "          base: \${{ needs.changes.outputs.scan_base }}" "$ci" "TruffleHog must use the validated event base"
require_line "          head: \${{ needs.changes.outputs.scan_head }}" "$ci" "TruffleHog must use an explicit validated head"
require_line "          PR_BASE: \${{ github.event.pull_request.base.sha }}" "$ci" "PR base SHA required"
require_line "          PR_HEAD: \${{ github.event.pull_request.head.sha }}" "$ci" "PR head SHA required"
require_line "          PUSH_BEFORE: \${{ github.event.before }}" "$ci" "push before SHA required"
require_line "          PUSH_AFTER: \${{ github.event.after }}" "$ci" "push after SHA required"
require_line "            0000000000000000000000000000000000000000)" "$ci" "initial pushes require explicit full-head handling"
require_line "              base=; diff_base=\$(git hash-object -t tree /dev/null) ;;" "$ci" "initial push must scan reachable head and classify its tree"
require_line "          test -n \"\$head\"" "$ci" "empty scan head must fail"
require_line "          test \"\$base\" != \"\$head\"" "$ci" "empty/equal scan range must fail"
require_ci_property changes "              merge_base=\$(git merge-base \"\$base\" \"\$head\")"
require_ci_property changes "              if [ \"\$merge_base\" = \"\$head\" ]; then base=; fi ;;"
require_line "          git cat-file -e \"\$head^{commit}\"" "$ci" "scan head must resolve"
forbid_ere '(--only-verified|--exclude|--skip|--no-verification|--json|--no-github-actions|continue-on-error|dependency-verification[ =]+(off|lenient)|--severity|--ignore)' "$ci" "scans and verification must not be weakened"
test "$(grep -c -- '--results=' "$ci")" -eq 1
require_line "          sh scripts/ci/dependency-contract-test.sh" "$ci" "dependency reproducibility contract must run"
require_ci_property changes '          sh scripts/ci/changed-areas-test.sh'
require_line "          sh scripts/ci/dependency-contract-test.sh --diff-stdin <\"\$changed\"" "$ci" "changed dependencies must be paired with their locks"
require_line "    needs: [changes, frontend, backend, infra, integration, security]" "$ci" "ci-gate must depend on every verification job"
require_line "    if: \${{ always() }}" "$ci" "ci-gate must always run"
require_line "          CHANGES_RESULT: \${{ needs.changes.result }}" "$ci" "gate must check changes result"
require_line "          SECURITY_RESULT: \${{ needs.security.result }}" "$ci" "gate must check security result"
require_line "          test \"\$CHANGES_RESULT\" = success" "$ci" "changes failures must block the gate"
require_line "          test \"\$SECURITY_RESULT\" = success" "$ci" "security failures must block the gate"
require_line "              true:success|false:skipped) ;;" "$ci" "gate must enforce selected success and unselected skip"
for area in FRONTEND BACKEND INFRA INTEGRATION; do
  expected=$(printf '          check_job "$%s_SELECTED" "$%s_RESULT"' "$area" "$area")
  require_ci_property ci-gate "$expected"
  lower=$(printf '%s' "$area" | tr '[:upper:]' '[:lower:]')
  require_ci_property ci-gate "          ${area}_SELECTED: \${{ needs.changes.outputs.$lower }}"
  require_ci_property ci-gate "          ${area}_RESULT: \${{ needs.$lower.result }}"
done
printf '%s\n' "workflow security tests passed"
