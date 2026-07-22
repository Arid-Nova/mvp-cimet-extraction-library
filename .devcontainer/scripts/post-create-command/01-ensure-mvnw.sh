#!/usr/bin/env bash
set -euo pipefail

# source helpers
LIB="$(cd "$(dirname "$0")/../common" && pwd)/lib.sh"
if [[ -f "$LIB" ]]; then
  # shellcheck source=/dev/null
  . "$LIB"
fi

info "01-ensure-mvnw: Ensuring mvnw exists and is executable"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

if [[ -f "$REPO_ROOT/mvnw" ]]; then
  if ensure_executable "$REPO_ROOT/mvnw"; then
    info "01-ensure-mvnw: mvnw is present and executable"
  fi
else
  warn "01-ensure-mvnw: mvnw not present at repo root: $REPO_ROOT/mvnw"
fi
