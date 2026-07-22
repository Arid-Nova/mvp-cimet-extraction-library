Devcontainer: Deep Dive
=======================

This document explains the devcontainer design, the post-create modular scripts, and how to maintain the container.

Purpose
-------
The devcontainer provides a reproducible development environment for contributors and CI parity. It installs the required JDK, Maven, Docker features, recommended editor extensions, and runs validation scripts on container creation.

How it is organized
-------------------
- `.devcontainer/devcontainer.json` — container configuration (image, features, extensions, postCreateCommand).
- `.devcontainer/Dockerfile` — extra OS-level packages and base image.
- `.devcontainer/scripts/` — modular post-create scripts; a small library at `.devcontainer/scripts/common/lib.sh` and modular steps at `.devcontainer/scripts/post-create-command/`.

Post-create script design
-------------------------
The root script `.devcontainer/scripts/post-create-command.sh` discovers and executes every script in `.devcontainer/scripts/post-create-command/` in lexicographic order. Each subscript is named with a leading numeric prefix to control order (01-ensure-mvnw.sh, 02-run-validate.sh...).

Helper library
--------------
Common utilities are centralized in `.devcontainer/scripts/common/lib.sh`:
- Logging: info(), warn(), error()
- run_cmd(): execute commands with consistent logging
- retry(): retry behavior for network operations
- safe_cd(), ensure_executable(), run_script()

Why modular scripts
-------------------
- Single Responsibility: each step is focused and easy to reason about
- Extensible: add new numbered script files to extend behavior
- Testable: run each script independently during maintenance

Maintenance
-----------
- To add a new step, create `.devcontainer/scripts/post-create-command/XX-name.sh`, make it executable and commit the executable bit.
- To change behavior, edit `common/lib.sh` for shared helpers rather than duplicating code.

Debugging
---------
- Rebuild the devcontainer and inspect the post-create output in the Dev Containers log.
- To run scripts manually inside container: `bash .devcontainer/scripts/post-create-command.sh`
