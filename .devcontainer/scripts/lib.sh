#!/usr/bin/env bash
set -euo pipefail

# lib.sh - Shared helper functions for devcontainer post-create scripts
# Goals: readable, testable, safe defaults, helpful logging

readonly DEFAULT_RETRY_COUNT=3
readonly DEFAULT_RETRY_DELAY=2

timestamp() { printf '%s' "$(date -u '+%Y-%m-%dT%H:%M:%SZ')"; }

log() {
  local level="$1"; shift
  printf '[%s] %s: %s\n' "$(timestamp)" "$level" "$*"
}

info()  { log "INFO" "$*"; }
warn()  { log "WARN" "$*"; }
error() { log "ERROR" "$*"; }

# run_cmd: runs a command and logs execution. Returns the command exit code.
# Usage: run_cmd [--no-fail] cmd...
run_cmd() {
  local no_fail=0
  if [[ "$1" == "--no-fail" ]]; then
    no_fail=1; shift
  fi
  info "RUN: $*"
  if "$@"; then
    return 0
  else
    local rc=$?
    error "Command failed with exit $rc: $*"
    if [[ $no_fail -eq 1 ]]; then
      return $rc
    fi
    return $rc
  fi
}

# retry: retry a command N times with delay. Returns 0 on success, non-zero on final failure.
# Usage: retry <count> <delay> cmd...
retry() {
  local count=${1:-$DEFAULT_RETRY_COUNT}; shift
  local delay=${1:-$DEFAULT_RETRY_DELAY}; shift
  local i=0
  until "$@"; do
    rc=$?
    i=$((i+1))
    if (( i >= count )); then
      error "Retry exhausted ($count) for: $*"
      return $rc
    fi
    warn "Command failed (exit $rc). Retrying in ${delay}s... ($i/$count)"
    sleep $delay
  done
  return 0
}

# safe_cd: change directory or log error
safe_cd() {
  if [[ -z "${1-}" ]]; then
    error "safe_cd requires a target directory"
    return 1
  fi
  if [[ -d "$1" ]]; then
    cd "$1"
  else
    error "Directory not found: $1"
    return 1
  fi
}

# ensure_executable: ensure file exists and is executable (sets mode)
ensure_executable() {
  if [[ -f "$1" ]]; then
    chmod +x "$1" || true
    info "Marked executable: $1"
    return 0
  else
    warn "File not found: $1"
    return 1
  fi
}

# run_script: run a given script path. Accepts --continue-on-error to not abort.
run_script() {
  local continue_on_error=0
  if [[ "$1" == "--continue-on-error" ]]; then
    continue_on_error=1; shift
  fi
  local script="$1"
  info "Executing script: $script"
  if [[ -x "$script" ]]; then
    bash "$script" || rc=$?
  else
    bash "$script" || rc=$?
  fi
  if [[ ${rc-0} -ne 0 ]]; then
    error "Script failed ($rc): $script"
    if [[ $continue_on_error -eq 1 ]]; then
      warn "Continuing despite failure of $script"
      return $rc
    else
      return $rc
    fi
  fi
  return 0
}
