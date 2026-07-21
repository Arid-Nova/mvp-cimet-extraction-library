Devcontainer Plan — environment for validating release build

Goal
- Provide a reproducible devcontainer that contains JDK 21, the Maven wrapper, and necessary tools so we can run the full validation (compile, tests, package, jitpack smoke) inside an isolated container.

Contents (high level)
- base image: mcr.microsoft.com/devcontainers/java:0-21 (or another official Java 21 devcontainer image)
- Tools to install:
  - Maven (the project uses the bundled maven-wrapper, but having mvn available is convenient)
  - git, curl
  - JDK 21 (primary requirement)
  - Node/npm (optional for the scripts under src/main/resources/scripts if you need to run them)

Devcontainer tasks
1) Create `.devcontainer/devcontainer.json` and Dockerfile that installs JDK 21 and required tools.
2) Mount the workspace and run initial setup: `./mvnw -N -B validate` to download wrapper and verify reactor.
3) Run full build: `./mvnw -B -DskipTests=false verify` (this is the main validation; must succeed to move forward).
4) Run distribution module package: `./mvnw -f .release/distribution/jitpack/pom.xml -B package`.

Validation checklist (inside devcontainer)
- java -version reports Java 21
- ./mvnw -N -B validate → success
- ./mvnw -B -DskipTests=false verify → success
- ./mvnw -f .release/distribution/jitpack/pom.xml -B package → artifact produced

Security & secrets
- Do not place PATs or secrets in the devcontainer config. For deploy runs, use GitHub Actions or inject secrets into the container runtime as environment variables manually for interactive testing.

Notes
- This devcontainer is for validation only. CI will run in GitHub Actions; ensure the CI workflow uses the same JDK 21 and mvn wrapper.
