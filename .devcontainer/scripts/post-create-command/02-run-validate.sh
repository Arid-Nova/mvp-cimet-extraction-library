#!/usr/bin/env bash
set -euo pipefail

echo "[02-run-validate] Running mvn validate (wrapper preferred, fallback to system mvn)"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

cd "$REPO_ROOT"

if [ -x "./mvnw" ]; then
  echo "[02-run-validate] invoking ./mvnw -N -B validate"
  if ./mvnw -N -B validate; then
    echo "[02-run-validate] ./mvnw validate succeeded"
    exit 0
  else
    echo "[02-run-validate] ./mvnw validate failed — will try system mvn"
  fi
fi

if command -v mvn >/dev/null 2>&1; then
  echo "[02-run-validate] invoking system mvn -N -B validate"
  if mvn -N -B validate; then
    echo "[02-run-validate] system mvn validate succeeded"
  else
    echo "[02-run-validate] system mvn validate failed"
  fi
else
  echo "[02-run-validate] no mvn available on PATH"
fi
