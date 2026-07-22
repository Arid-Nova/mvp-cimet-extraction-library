
#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SCRIPTS_DIR="$ROOT_DIR/post-create-command"

# Source shared lib if present
LIB="$ROOT_DIR/lib.sh"
if [ -f "$LIB" ]; then
  # shellcheck source=/dev/null
  . "$LIB"
else
  echo "[devcontainer] warning: helper library not found at $LIB"
fi

echo "[devcontainer] post-create: launching modular post-create scripts from $SCRIPTS_DIR"

if [ ! -d "$SCRIPTS_DIR" ]; then
  echo "[devcontainer] no modular scripts directory found at $SCRIPTS_DIR — nothing to run"
  exit 0
fi

# Portable iteration over files; avoid bash-only shopt/arrays to work with /bin/sh
found=0
for script in "$SCRIPTS_DIR"/*; do
  if [ ! -e "$script" ]; then
    # no matches - break
    break
  fi
  found=1
  base=$(basename "$script")
  if [ -f "$script" ] && [ -x "$script" ]; then
    echo "[devcontainer] running $base"
    "$script"
  else
    case "$base" in
      *.sh)
        echo "[devcontainer] running (shell) $base"
        sh "$script"
        ;;
      *)
        echo "[devcontainer] skipping non-shell or non-executable file: $base"
        ;;
    esac
  fi
done

if [ "$found" -eq 0 ]; then
  echo "[devcontainer] no scripts found in $SCRIPTS_DIR"
fi

echo "[devcontainer] post-create: completed all modular scripts"
