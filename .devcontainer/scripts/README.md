Devcontainer Scripts
====================

Purpose
-------
This directory contains modular scripts run by the devcontainer post-create step. The design goals are:
- Single Responsibility: each script does one thing
- DRY: shared helpers are in common/lib.sh
- Readability & maintainability: use small functions and consistent logging

Layout
------
.devcontainer/
  scripts/
    post-create-command.sh      # Root launcher: discovers and runs modular scripts
    common/
      lib.sh                    # Shared helper functions (logging, run_cmd, retry, etc.)
    post-create-command/        # Modular scripts executed by the root launcher
      01-ensure-mvnw.sh         # Ensure mvnw exists and is executable
      02-run-validate.sh        # Run ./mvnw -N -B validate (wrapper preferred)
      03-check-java.sh          # Print java/javac versions

How it works
------------
1. devcontainer.json's `postCreateCommand` invokes the root launcher:

   bash -lc 'chmod +x .devcontainer/scripts/post-create-command.sh && .devcontainer/scripts/post-create-command.sh'

2. The root launcher loads the shared helpers from `common/lib.sh` and executes every file
   in `post-create-command/` in lexicographic order.

3. Each modular script is expected to be a small, focused bash script. The launcher calls
   them using `run_script` from `common/lib.sh`, which provides consistent logging.

Adding a new script
-------------------
1. Create a new file in `.devcontainer/scripts/post-create-command/` with a numeric prefix
   to control ordering, e.g. `04-install-node.sh`.
2. Make it executable and commit the executable bit:

   chmod +x .devcontainer/scripts/post-create-command/04-install-node.sh
   git add .devcontainer/scripts/post-create-command/04-install-node.sh
   git update-index --add --chmod=+x .devcontainer/scripts/post-create-command/04-install-node.sh
   git commit -m "Add devcontainer post-create script: install node"

3. Keep the script focused. Use helpers from `../common/lib.sh` by sourcing it:

   #!/usr/bin/env bash
   set -euo pipefail
   LIB="$(cd "$(dirname "$0")/../common" && pwd)/lib.sh"
   . "$LIB"

   main() {
     info "04-install-node: installing node"
     run_cmd sudo apt-get update
     run_cmd sudo apt-get install -y nodejs npm
   }

   main "$@"

Helper functions (common/lib.sh)
-------------------------------
- info/warn/error: consistent timestamped logging
- run_cmd [--no-fail] ...: run a command with logging; returns exit code
- retry <count> <delay> ...: retry a command multiple times (used for network operations)
- safe_cd <dir>: cd with helpful error message
- ensure_executable <file>: mark a file executable
- run_script [--continue-on-error] <script>: execute a script and optionally continue on failure

Behavior notes
--------------
- The post-create launcher runs scripts with bash. Use bash features in subscripts.
- By default the launcher will continue on script failures (CONTINUE_ON_ERROR=1). To make the
  launcher fail-fast, set CONTINUE_ON_ERROR=0 in the environment or devcontainer.json.

Manual testing
--------------
Run the root launcher locally (useful for iterating quickly):

  bash .devcontainer/scripts/post-create-command.sh

Or run an individual modular script directly for faster feedback:

  bash .devcontainer/scripts/post-create-command/02-run-validate.sh

Security and best practices
---------------------------
- Do not store secrets in these scripts. If you need credentials, read them from mounted
  secrets or environment variables injected by your CI/devcontainer runtime.
- Keep scripts small and idempotent where possible.
