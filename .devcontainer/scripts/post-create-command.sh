
#!/usr/bin/env bash
set -euo pipefail

echo "[devcontainer] post-create: starting workspace validation"

# Ensure mvnw exists and is executable
if [ -f "./mvnw" ]; then
  chmod +x ./mvnw || true
else
  echo "[devcontainer] warning: mvnw not found in repo root"
fi

echo "[devcontainer] Attempting to run './mvnw -N -B validate' to download wrapper and validate reactor"
if command -v ./mvnw >/dev/null 2>&1; then
  if ./mvnw -N -B validate; then
    echo "[devcontainer] mvnw validate succeeded"
  else
    echo "[devcontainer] mvnw validate failed, attempting system 'mvn' as fallback"
    if command -v mvn >/dev/null 2>&1; then
      mvn -N -B validate && echo "[devcontainer] system mvn validate succeeded" || echo "[devcontainer] system mvn validate failed"
    else
      echo "[devcontainer] no system mvn available; skipping validate"
    fi
  fi
else
  echo "[devcontainer] mvnw not runnable; trying system 'mvn'"
  if command -v mvn >/dev/null 2>&1; then
    mvn -N -B validate && echo "[devcontainer] system mvn validate succeeded" || echo "[devcontainer] system mvn validate failed"
  else
    echo "[devcontainer] no mvn available; cannot perform validate"
  fi
fi

echo "[devcontainer] post-create: finished"
