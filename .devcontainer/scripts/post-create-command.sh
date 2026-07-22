
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$ROOT_DIR/post-create-command"

# load helpers
LIB="$ROOT_DIR/lib.sh"
if [[ -f "$LIB" ]]; then
  # shellcheck source=/dev/null
  . "$LIB"
else
  echo "[devcontainer] warning: helper library not found at $LIB"
fi

info "post-create: launching modular post-create scripts from $SCRIPTS_DIR"

if [[ ! -d "$SCRIPTS_DIR" ]]; then
  warn "no modular scripts directory found at $SCRIPTS_DIR — nothing to run"
  exit 0
fi

# control behavior: continue on subscript failure by default for devcontainers
CONTINUE_ON_ERROR=${CONTINUE_ON_ERROR:-1}

# iterate and execute
shopt -s nullglob
for script in "$SCRIPTS_DIR"/*; do
  base=$(basename "$script")
  if [[ -f "$script" ]]; then
    info "found script: $base"
    if [[ $CONTINUE_ON_ERROR -eq 1 ]]; then
      run_script --continue-on-error "$script" || warn "script $base failed but continuing"
    else
      run_script "$script"
    fi
  else
    warn "skipping non-regular file: $base"
  fi
done

info "post-create: completed all modular scripts"
