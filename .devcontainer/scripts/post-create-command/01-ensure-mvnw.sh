#!/bin/sh
set -eu

# source helpers
LIB="$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
if [ -f "$LIB" ]; then
  # shellcheck source=/dev/null
  . "$LIB"
fi

info "[01-ensure-mvnw] Ensuring mvnw exists and is executable"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

if [ -f "$REPO_ROOT/mvnw" ]; then
  run_cmd chmod +x "$REPO_ROOT/mvnw" || true
  info "[01-ensure-mvnw] mvnw is present and executable"
else
  warn "[01-ensure-mvnw] mvnw not present at repo root: $REPO_ROOT/mvnw"
fi
