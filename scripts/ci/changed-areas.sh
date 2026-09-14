#!/bin/sh
set -eu

frontend=false backend=false infra=false integration=false
while IFS= read -r path || [ -n "$path" ]; do
  case "$path" in
    '') ;;
    README.md|*/README.md|docs/*) ;;
    frontend/*) frontend=true; integration=true ;;
    backend/*) backend=true; integration=true ;;
    infra/llm/*|.github/workflows/*) infra=true ;;
    infra/local/*|scripts/ci/*) integration=true ;;
    *) frontend=true; backend=true; infra=true; integration=true ;;
  esac
done

if [ -n "${GITHUB_OUTPUT:-}" ]; then
  exec >>"$GITHUB_OUTPUT"
fi
printf 'frontend=%s\nbackend=%s\ninfra=%s\nintegration=%s\nsecurity=true\n' \
  "$frontend" "$backend" "$infra" "$integration"
