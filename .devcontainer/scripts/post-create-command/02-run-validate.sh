#!/usr/bin/env bash
set -euo pipefail

# source helpers
LIB="$(cd "$(dirname "$0")/../common" && pwd)/lib.sh"
if [[ -f "$LIB" ]]; then
  # shellcheck source=/dev/null
  . "$LIB"
fi

info "02-run-validate: Running mvn validate (wrapper preferred, fallback to system mvn)"
REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"

safe_cd "$REPO_ROOT"

validate_with_wrapper() {
  if [[ ! -x "./mvnw" ]]; then
    warn "02-run-validate: mvnw not executable or missing"
    return 1
  fi
  info "02-run-validate: invoking ./mvnw -N -B validate"
  # Try twice with backoff in case of transient network issues
  retry 2 2 ./mvnw -N -B validate
}

validate_with_system() {
  if ! command -v mvn >/dev/null 2>&1; then
    warn "02-run-validate: system mvn not found"
    return 1
  fi
  info "02-run-validate: invoking system mvn -N -B validate"
  run_cmd mvn -N -B validate
}

# install_dependencies: populate local repo and (optionally) build the project
# Controlled by POST_CREATE_INSTALL_DEPS (default: true). Set to 'false' to skip.
install_dependencies() {
  local install_flag=${POST_CREATE_INSTALL_DEPS:-true}
  if [[ "$install_flag" == "false" ]]; then
    info "02-run-validate: POST_CREATE_INSTALL_DEPS=false; skipping dependency prefetch/install"
    return 0
  fi

  if [[ -x "./mvnw" ]]; then
    info "02-run-validate: prefetching maven dependencies (dependency:go-offline)"
    retry 2 5 ./mvnw -B -DskipTests dependency:go-offline

    info "02-run-validate: running mvn install (skip tests) to ensure local repo populated"
    # install can fail in some environments; retry once
    retry 2 5 ./mvnw -B -DskipTests install
    return $?
  fi

  if command -v mvn >/dev/null 2>&1; then
    info "02-run-validate: prefetching maven dependencies (system mvn dependency:go-offline)"
    retry 2 5 mvn -B -DskipTests dependency:go-offline

    info "02-run-validate: running system mvn install (skip tests)"
    retry 2 5 mvn -B -DskipTests install
    return $?
  fi

  warn "02-run-validate: no mvn wrapper or system mvn available; cannot install dependencies"
  return 1
}

main() {
  if validate_with_wrapper; then
    info "02-run-validate: ./mvnw validate succeeded"
  else
    warn "02-run-validate: ./mvnw validate failed — will try system mvn"
    if validate_with_system; then
      info "02-run-validate: system mvn validate succeeded"
    else
      warn "02-run-validate: system mvn validate failed or not present"
    fi
  fi

  # Attempt to prefetch/install dependencies unless explicitly disabled
  if install_dependencies; then
    info "02-run-validate: dependency install step completed"
  else
    warn "02-run-validate: dependency install step failed or was skipped"
  fi
}

main "$@"
