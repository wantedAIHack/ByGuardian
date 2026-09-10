#!/bin/sh
set -eu

test "$(tr -d '[:space:]' < frontend/.nvmrc)" = 22.22.2
node_engine="$(node -e 'process.stdout.write(require("./frontend/package.json").engines.node)')"
test "$node_engine" = 22.22.2
test "$(node --version)" = v22.22.2
