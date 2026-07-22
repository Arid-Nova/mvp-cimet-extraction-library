#!/bin/sh
# POSIX-safe helper library for devcontainer scripts
set -eu

log() {
  # Timestamped log
  TS=$(date -u '+%Y-%m-%dT%H:%M:%SZ')
  printf '[%s] %s\n' "$TS" "$*"
}

info()  { log "INFO: $*"; }
warn()  { log "WARN: $*"; }
err()   { log "ERROR: $*"; }

run_cmd() {
  info "RUN: $*"
  # Execute the command and capture exit code; do not force script exit here
  "$@"
  rc=$?
  if [ "$rc" -ne 0 ]; then
    err "command failed: $* (exit $rc)"
  fi
  return $rc
}

safe_cd() {
  if [ $# -eq 0 ]; then
    err "safe_cd requires a path"
    return 1
  fi
  dir=$1
  if [ -d "$dir" ]; then
    cd "$dir" || return 1
  else
    err "directory not found: $dir"
    return 1
  fi
}
