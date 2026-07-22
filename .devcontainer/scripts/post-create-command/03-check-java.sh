#!/bin/sh
set -eu

# source helpers
LIB="$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
if [ -f "$LIB" ]; then
  # shellcheck source=/dev/null
  . "$LIB"
fi

info "[03-check-java] Reporting java -version and javac -version (if available)"
if command -v java >/dev/null 2>&1; then
  run_cmd java -version
else
  warn "[03-check-java] java not found on PATH"
fi

if command -v javac >/dev/null 2>&1; then
  run_cmd javac -version
else
  warn "[03-check-java] javac not found on PATH"
fi
