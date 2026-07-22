#!/bin/sh
set -eu

# source helpers
LIB="$(cd "$(dirname "$0")/.." && pwd)/lib.sh"
if [ -f "$LIB" ]; then
  # shellcheck source=/dev/null
  . "$LIB"
fi

info "[02-run-validate] Running mvn validate (wrapper preferred, fallback to system mvn)"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

safe_cd "$REPO_ROOT"

validate_with_wrapper() {
  [ -x "./mvnw" ] || return 1
  info "[02-run-validate] invoking ./mvnw -N -B validate"
  run_cmd ./mvnw -N -B validate
}

validate_with_system() {
  command -v mvn >/dev/null 2>&1 || return 1
  info "[02-run-validate] invoking system mvn -N -B validate"
  run_cmd mvn -N -B validate
}

main() {
  if validate_with_wrapper; then
    info "[02-run-validate] ./mvnw validate succeeded"
    return 0
  fi
  warn "[02-run-validate] ./mvnw validate failed — will try system mvn"

  if validate_with_system; then
    info "[02-run-validate] system mvn validate succeeded"
  else
    warn "[02-run-validate] system mvn validate failed or not present"
  fi
}

main "$@"
