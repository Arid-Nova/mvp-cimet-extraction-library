## Release Implementation Tasks — Multiple Distribution Modules

This is the task list for the release work, now stored under .josesosa/release-work. See `.josesosa/release-work/release-work-record.md` for the work record and validation results.

Task 1 — Confirm multi-module architecture decision
Action: Confirm the committed decision file `.josesosa/release-work/release-architecture.md`.
Acceptance criteria:
- File exists and contains the decision.
Test / Validation:
- Reviewer confirms the file is present.

Task 2 — Convert root pom to an aggregator (packaging=pom)
Action: Root `pom.xml` has been converted to `packaging=pom` and modules added (see release-work-record).
Acceptance criteria:
- Root `pom.xml` is packaging pom and lists `.release/core` and distribution modules.
Test / Validation:
- `./mvnw -N -B validate` succeeds.

Task 3 — Add distribution module directories
Action: Distribution module POMs under `.release/distribution` exist (`jitpack`, `github-packages`) and depend on `.release/core`.
Acceptance criteria:
- Both `.release/distribution/github-packages/pom.xml` and `.release/distribution/jitpack/pom.xml` exist and declare artifactIds.
Test / Validation:
- `./mvnw -f .release/distribution/jitpack/pom.xml -B package` produces the JAR.

Task 4 — Ensure tests still run from root CI
Action: CI should run reactor verify at root.
Acceptance criteria:
- `./mvnw -B -DskipTests=false verify` succeeds on an environment with JDK 21.
Test / Validation:
- Run verify in devcontainer (see devcontainer plan).

Task 5 — Move distributionManagement to the GitHub Packages distribution
Action: Add `distributionManagement` to `.release/distribution/github-packages/pom.xml`.
Acceptance criteria:
- Distribution module contains the distributionManagement entry pointing to the GitHub Packages URL.
Test / Validation:
- Deploy to a fork/test repo using PAT or GITHUB_TOKEN in fork secrets.

Task 5a — Spike credentials (investigate credential strategy)
Action: Produce `.josesosa/credential-spike.md` describing whether to use GITHUB_TOKEN or a PAT (`MAVEN_PUBLISH_TOKEN`) and sample settings.xml.
Acceptance criteria:
- `.josesosa/credential-spike.md` exists with recommendation and snippet.
Test / Validation:
- Reviewer confirms the spike doc and sample settings.xml.

Task 5b — Implement credential decision from spike
Action: Implement settings.xml templating in workflow and document required secrets.
Acceptance criteria:
- Workflow uses secret and creates settings.xml; README documents secrets.
Test / Validation:
- Run deploy step in fork to validate.

Task 6 — Keep root source layout unchanged (no file moves)
Action: `.release/core` points to repository sources via relative paths. No source or test files are moved.
Acceptance criteria:
- `src/main/java` and `src/test/java` remain at repo root.
Test / Validation:
- Module builds succeed using relative paths (needs JDK 21 to compile).

Task 7 — Update README and docs
Action: README updated with the distribution examples and build commands (already done). Add RELEASE_PROCESS.md with flow.
Acceptance criteria:
- README contains distribution sample coordinates and build commands.
Test / Validation:
- Consumer sample can resolve artifacts after publishing.

Task 8 — Update GitHub Actions workflow to deploy distribution
Action: Publish workflow should deploy only `.release/distribution/github-packages` on stable tags.
Acceptance criteria:
- Workflow uses `-pl .release/distribution/github-packages -am` for deploy and is guarded by tag conditions.
Test / Validation:
- Run workflow in fork to validate deploy step executes against correct module.

Task 9 — Document how to add additional distributions
Action: Add `.release/distribution/README.md` covering how to add new distribution modules.
Acceptance criteria:
- README exists with sample pom snippet and steps.
Test / Validation:
- Developer follows README and successfully builds a new distribution module.

Task 10 — Ensure JitPack builds distribution
Action: Confirm JitPack will build the distribution module and produce mvp-cimet-extraction artifact for tags.
Acceptance criteria:
- JitPack builds success on preview tag for fork.
Test / Validation:
- Create preview tag in fork and validate on jitpack.io.

Other tasks: credential spike, release process doc, maintenance notes — see release-work-record for details.
