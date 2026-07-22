#!/usr/bin/env bash
set -euo pipefail

echo "[01-ensure-mvnw] Ensuring mvnw exists and is executable"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
REPO_ROOT="$(cd "$ROOT/../.." && pwd)"

if [ -f "$REPO_ROOT/mvnw" ]; then
  chmod +x "$REPO_ROOT/mvnw" || true
  echo "[01-ensure-mvnw] mvnw is present and executable"
else
  echo "[01-ensure-mvnw] mvnw not present at repo root: $REPO_ROOT/mvnw"
fi
