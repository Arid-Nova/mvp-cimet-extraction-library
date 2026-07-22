#!/usr/bin/env bash
set -euo pipefail

echo "[03-check-java] Reporting java -version and javac -version (if available)"
if command -v java >/dev/null 2>&1; then
  java -version || true
else
  echo "[03-check-java] java not found on PATH"
fi

if command -v javac >/dev/null 2>&1; then
  javac -version || true
else
  echo "[03-check-java] javac not found on PATH"
fi
