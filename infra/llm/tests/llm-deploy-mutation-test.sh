#!/bin/sh
# The laptop runner is not ephemeral: /opt/nextvisit/llm/current and the
# Cloudflare Tunnel credential it applies persist across jobs, so
# .github/workflows/llm-deploy.yml must be the only door a PR, a fork, or an
# unreviewed workflow edit can ever reach it through. Every case below mutates
# a disposable copy of the deploy workflow and proves
# infra/llm/tests/workflows-test.sh's static policy rejects it -- the same
# check the real llm-deploy.yml must keep passing on every future edit.
#
# These Perl mutation programs must receive literal workflow shell variables.
# shellcheck disable=SC2016
set -eu

script_dir="$(CDPATH="" cd -- "$(dirname -- "$0")" && pwd)"
repo_root="$(CDPATH="" cd -- "$script_dir/../../.." && pwd)"
fixture_root="$(mktemp -d "${TMPDIR:-/tmp}/nextvisit-llm-deploy-mutation.XXXXXX")"
fixture_number=0

# The pristine policy must pass before any rejection counts as a useful
# mutation -- otherwise every case below would "pass" for the wrong reason.
sh "$repo_root/infra/llm/tests/workflows-test.sh"

cleanup() {
  rm -rf "$fixture_root"
}
trap cleanup EXIT HUP INT TERM

prepare_fixture() {
  fixture_number=$((fixture_number + 1))
  fixture="$fixture_root/$fixture_number"
  mkdir -p "$fixture/.github/workflows" "$fixture/infra/llm/tests"
  cp "$repo_root/.github/workflows/ci.yml" "$fixture/.github/workflows/ci.yml"
  cp "$repo_root/.github/workflows/llm-deploy.yml" "$fixture/.github/workflows/llm-deploy.yml"
  cp "$repo_root/infra/llm/tests/workflows-test.sh" "$fixture/infra/llm/tests/workflows-test.sh"
}

expect_rejected() {
  name=$1
  if sh "$fixture/infra/llm/tests/workflows-test.sh" >/dev/null 2>&1; then
    printf '%s\n' "$name must be rejected by the workflow policy" >&2
    exit 1
  fi
}

mutate_deploy() {
  prepare_fixture
  perl -0pi -e "$1" "$fixture/.github/workflows/llm-deploy.yml"
  expect_rejected "$2"
}

# --- main/variable gate -------------------------------------------------

mutate_deploy \
  's{    if: \$\{\{ github\.ref == \x27refs/heads/main\x27 && vars\.LLM_DEPLOY_ENABLED == \x27true\x27 \}\}\n}{}' \
  'deploy missing the main/variable gate entirely'
mutate_deploy \
  's{vars\.LLM_DEPLOY_ENABLED == \x27true\x27}{vars.LLM_DEPLOY_ENABLED == \x27false\x27}' \
  'deploy gate checking the wrong LLM_DEPLOY_ENABLED value'
mutate_deploy \
  's{github\.ref == \x27refs/heads/main\x27}{github.ref == \x27refs/heads/feature\x27}' \
  'deploy gate checking a branch other than main'

# --- checkout: exact commit, no stored credentials -----------------------

mutate_deploy \
  's{ref: \$\{\{ github\.sha \}\}}{ref: main}' \
  'deploy checkout tracking moving main instead of github.sha'
mutate_deploy \
  's{persist-credentials: false}{persist-credentials: true}' \
  'deploy checkout persisting credentials'

# --- no secrets context, no token env/command -----------------------------

mutate_deploy \
  's{(      - name: Verify Ubuntu GPU host\n)}{$1        env:\n          LEAKED: \$\{\{ secrets.ANY_SECRET \}\}\n}' \
  'deploy step consuming the secrets context'
mutate_deploy \
  's{(      - name: Verify Ubuntu GPU host\n)}{$1        env:\n          TUNNEL_TOKEN: reintroduced\n}' \
  'deploy step reintroducing a raw TUNNEL_TOKEN environment variable'

# --- work only from the staged, immutable release -------------------------

mutate_deploy \
  's{working-directory: /opt/nextvisit/llm/current}{working-directory: \$GITHUB_WORKSPACE/infra/llm}' \
  'deploy operating directly from the Actions checkout workspace instead of the staged release'
mutate_deploy \
  's{(      - name: Stage immutable release\n        run: infra/llm/scripts/stage-runtime\.sh "\$GITHUB_SHA" "\$GITHUB_WORKSPACE"\n\n)}{$1      - name: Regression direct workspace build\n        working-directory: \$GITHUB_WORKSPACE/infra/llm\n        run: docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml build ollama\n\n}' \
  'deploy adding an extra step that builds directly from the Actions checkout workspace'
mutate_deploy \
  's{      - name: Stage immutable release\n        run: infra/llm/scripts/stage-runtime\.sh "\$GITHUB_SHA" "\$GITHUB_WORKSPACE"\n\n}{}' \
  'deploy missing the immutable stable-release staging step'

# --- no host port, no volume-destroying stop -------------------------------

mutate_deploy \
  's{          jq -e \x27\.services\.ollama\.ports == null\x27 "\$rendered" >/dev/null\n}{}' \
  'deploy missing the rendered no-host-port assertion'
mutate_deploy \
  's{umask 077}{umask 077\n          docker compose --project-name nextvisit-llm -f compose.yml -f compose.gpu.yml -f compose.tunnel.yml down --volumes}' \
  'deploy destroying the persistent model volume'

# --- no token leak into Env/Cmd/Labels -------------------------------------

mutate_deploy \
  's{          jq -e \x27\.services\.cloudflared\.environment == null\x27 "\$rendered" >/dev/null\n}{}' \
  'deploy missing the cloudflared no-environment-leak assertion'
mutate_deploy \
  's{          jq -e \x27\.services\.cloudflared\.command == \[[^]]*\]\x27 "\$rendered" >/dev/null\n}{}' \
  'deploy missing the exact rendered cloudflared command assertion'
mutate_deploy \
  's{--token-file}{--token}' \
  'deploy reverting cloudflared to a bare --token argument'

# --- exactly one deploy job, no broader permissions -------------------------

mutate_deploy \
  's{(jobs:\n  deploy:\n)}{${1}  extra:\n    runs-on: [self-hosted, linux, llm]\n    steps:\n      - run: echo hi\n}' \
  'deploy workflow gaining a second job'
mutate_deploy \
  's{contents: read}{contents: write}' \
  'deploy workflow granting broader repository permissions'

# --- the reboot-recovery unit must stay enabled and active, checked only
#     with unprivileged reads --------------------------------------------

mutate_deploy \
  's{      - name: Verify recovery unit remains enabled and active\n        run: \|\n          test "\$\(systemctl is-enabled nextvisit-llm\.service\)" = enabled\n          test "\$\(systemctl is-active nextvisit-llm\.service\)" = active\n}{}' \
  'deploy missing the recovery-unit enabled/active verification'
mutate_deploy \
  's{(          test "\$\(systemctl is-active nextvisit-llm\.service\)" = active\n)}{$1          systemctl restart nextvisit-llm.service\n}' \
  'deploy calling a privileged systemctl subcommand'

# --- the offline test suite must actually run on this checkout -------------

mutate_deploy \
  's{      - name: Run offline infra test suite\n        run: \|\n          sh infra/llm/tests/workflows-test\.sh\n          sh infra/llm/tests/workflows-runner-group-test\.sh\n          sh infra/llm/tests/llm-deploy-mutation-test\.sh\n          for test in infra/llm/tests/\*\.sh; do\n            sh "\$test"\n          done\n\n}{}' \
  'deploy missing the offline infra test suite step'
mutate_deploy \
  's{          sh infra/llm/tests/workflows-test\.sh\n          sh infra/llm/tests/workflows-runner-group-test\.sh\n          sh infra/llm/tests/llm-deploy-mutation-test\.sh\n          for test in infra/llm/tests/\*\.sh; do\n}{          for test in infra/llm/tests/*.sh; do\n}' \
  'deploy offline test suite dropping the explicit named-first ordering, leaving only the arbitrary glob loop'
mutate_deploy \
  's{          sh infra/llm/tests/workflows-test\.sh\n          sh infra/llm/tests/workflows-runner-group-test\.sh\n          sh infra/llm/tests/llm-deploy-mutation-test\.sh\n          for test in infra/llm/tests/\*\.sh; do\n            sh "\$test"\n          done\n}{          for test in infra/llm/tests/*.sh; do\n            sh "$test"\n          done\n          sh infra/llm/tests/workflows-test.sh\n          sh infra/llm/tests/workflows-runner-group-test.sh\n          sh infra/llm/tests/llm-deploy-mutation-test.sh\n}' \
  'deploy offline test suite running the named policy suites after the arbitrary glob loop instead of before it'

printf 'llm-deploy mutation policy: %s mutations rejected\n' "$fixture_number"
