Devcontainer Implementation Plan — iterative, fail-fast approach

Goal

- Create a devcontainer that reliably reproduces a developer environment for this Maven/Java (Spring-style) project, enabling full local validation (compile, unit/integration tests, packaging) and close parity with CI.
- Approach: iterative tasks; start with an empty devcontainer scaffold, verify, then add one feature at a time and validate each step.

Principles

- Small, verifiable steps: each task is a single unit of work with an acceptance criteria and a test.
- Fail early / fail fast: run lightweight checks before heavier installs.
- CI parity: prefer the same JDK and Maven wrapper versions used in CI.
- Safety: do not bake secrets into the container; document how to inject them when needed.

Location

- This plan lives under `.josesosa/devcontainer/` alongside the devcontainer artifacts we will add.

Task 1 — Add empty devcontainer scaffold
Action:

- Create `.devcontainer/devcontainer.json` and `.devcontainer/Dockerfile` with minimal comments and placeholders; no heavy tooling installed yet.
Acceptance criteria:
- Files exist and container can be built (docker build) from the Dockerfile without installing project-specific tools.
Test / Validation:
- Run `docker build -t mvp-devcontainer .devcontainer` — build completes.

Task 2 — Start container and smoke-check base OS tools
Action:

- Launch a container from the scaffold, open a shell, verify basic tools exist (sh, apt or yum depending on base image).
Acceptance criteria:
- Container runs and `uname -a` and `cat /etc/os-release` return sensible output; exit code 0.
Test / Validation:
- `docker run --rm -it mvp-devcontainer sh -c 'uname -a && cat /etc/os-release'`.

Task 3 — Add JDK 21 to Dockerfile
Action:

- Update Dockerfile to install JDK 21 (use official Microsoft/AdoptOpenJDK/Temurin base or install OpenJDK 21). Set JAVA_HOME.
Acceptance criteria:
- `java -version` inside the container reports Java 21.
Test / Validation:
- Build the container and run `docker run --rm mvp-devcontainer java -version` expecting Java 21.

Task 4 — Ensure Maven wrapper is present and runnable
Action:

- Ensure the project contains `.mvn/wrapper` and add `mvnw` to the repo root (it exists in this repo). Ensure the Dockerfile sets `MAVEN_OPTS` reasonably and provides a minimal `mvn` or lets the wrapper download Maven.
Acceptance criteria:
- Inside the container `./mvnw -v` prints Maven info and uses the wrapper distribution.
Test / Validation:
- `docker run --rm -v "$PWD":/workspace -w /workspace mvp-devcontainer ./mvnw -v` should show Maven version.

Task 5 — Add git, curl, unzip, and basic dev tools
Action:

- Install git, curl, unzip, and any packages needed by mvn wrapper to download Maven (ca-certificates, wget if needed).
Acceptance criteria:
- `git --version`, `curl --version` succeed inside container.
Test / Validation:
- `docker run --rm mvp-devcontainer sh -c 'git --version && curl --version'`.

Task 6 — Run lightweight repo validation (mvn validate)
Action:

- Mount the repo into the container and run `./mvnw -N -B validate` to confirm reactor recognition and POM validity.
Acceptance criteria:
- `./mvnw -N -B validate` finishes successfully inside container.
Test / Validation:
- `docker run --rm -v "$PWD":/workspace -w /workspace mvp-devcontainer ./mvnw -N -B validate` completes with BUILD SUCCESS.

Task 7 — Add build tools for running tests (optional: Node/NPM if scripts required)
Action:

- Add Node/npm if project scripts under src/main/resources/scripts require it; otherwise skip.
Acceptance criteria:
- If installed, `node -v` and `npm -v` succeed.
Test / Validation:
- Run `docker run --rm mvp-devcontainer node -v`.

Task 8 — Run full reactor build (compile + tests) inside container
Action:

- Run `./mvnw -B -DskipTests=false verify` inside the devcontainer (this requires JDK 21 and network access to Maven Central).
Acceptance criteria:
- Build completes with `BUILD SUCCESS` (or fails for legitimate test failures that need fixes); jacoco reports produced.
Test / Validation:
- `docker run --rm -v "$PWD":/workspace -w /workspace mvp-devcontainer ./mvnw -B -DskipTests=false verify`.

Task 9 — Add VSCode devcontainer config (IDE integration)
Action:

- Add `.devcontainer/devcontainer.json` settings for recommended extensions (vscjava.vscode-java-pack, vscjava.vscode-maven, redhat.java, GitLens). Configure forwardPorts, workspaceMount and postCreateCommand to run `./mvnw -N -B validate`.
Acceptance criteria:
- Opening the repo in VSCode with Remote - Containers picks up the devcontainer and installs recommended extensions; postCreateCommand runs successfully.
Test / Validation:
- Open in VSCode Remote-Containers (or run `devcontainer build` if available) and ensure the container builds and extensions are suggested/installed.

Task 10 — Add Maven settings templating support for CI and local testing (no secrets in repo)
Action:

- Add an optional `.devcontainer/post-create.sh` that writes a `~/.m2/settings.xml` from secrets injected at runtime (document how to mount a secrets file in devcontainer). Do NOT commit secrets.
Acceptance criteria:
- Developer can provide a local settings.xml via bind-mount or env var and `./mvnw -s ~/.m2/settings.xml deploy` will use it.
Test / Validation:
- Document and test that `docker run -v /path/to/settings.xml:/root/.m2/settings.xml ... ./mvnw -f .release/distribution/github-packages/pom.xml deploy -DskipTests` runs and fails only due to remote permissions (not local config).

Task 11 — Add Java remote debug configuration
Action:

- Add an example launch configuration to `.vscode/launch.json` for remote debugging (agent port 5005) and add container options to allow that port and start the app with `-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005` when appropriate.
Acceptance criteria:
- You can attach a debugger from VSCode to a process running inside the container.
Test / Validation:
- Start a sample run in container with debug flags and attach from local VSCode Remote session.

Task 12 — Add devcontainer CI parity notes and maintenance
Action:

- Document the devcontainer image, tags, and required steps in `.josesosa/devcontainer/README.md` so CI can use the same base image and settings.
Acceptance criteria:
- README contains exact dockerfile references and commands used to build/test the project inside the devcontainer.
Test / Validation:
- Rebuild container from README and run validation commands to confirm parity.

Task 13 — Optional: Add Docker-in-Docker (DinD) or Docker socket binding for integration tests that require containers
Action:

- If integration tests require Docker (e.g., Testcontainers), document how to enable Docker by bind-mounting the host docker socket or enabling DinD in the devcontainer.
Acceptance criteria:
- Tests that use Docker can run inside the devcontainer when configured.
Test / Validation:
- Enable socket mount and run failing/running tests that require docker; they should pass or produce expected results.

Task 14 — Finalize and commit devcontainer files
Action:

- Commit `.devcontainer/devcontainer.json`, Dockerfile, post-create scripts and `.vscode/launch.json` sample onto the devcontainer branch.
Acceptance criteria:
- Files are present in the new branch and building the devcontainer reproduces the validation steps.
Test / Validation:
- Open devcontainer in VSCode or run `devcontainer build` and run the validation commands above.

Notes on testing methodology

- Each task is intentionally small to isolate failures and learn quickly.
- Run the lightest checks first (build container, run `java -version`) before running the expensive `mvn verify` step.
- Use a forked repo and test package deploys against a test package registry to avoid impacting production packages.

If you want, I will now implement Task 1 (create empty devcontainer.json and Dockerfile) and run the container build, then report back. Tell me to proceed and I will commit the scaffold under `.devcontainer` on your devcontainer branch.
