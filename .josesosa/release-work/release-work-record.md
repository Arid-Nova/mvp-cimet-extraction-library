Release Work Record — multi-module / JitPack + GitHub Packages

Purpose
- Record the decisions, changes, validations and next steps for the Option B release work (canonical multi-module + distribution modules) so work can be resumed later.

High-level decision
- Adopt Option B: canonical multi-module release layout, but: per constraint, DO NOT MOVE source or test files. Instead we created module POMs under `.release/` that reference the repository sources by relative path.

What I changed (files added/modified)
- Root POM (pom.xml): converted to `packaging=pom`, modules added:
  - `.release/core`
  - `.release/distribution/jitpack`
  - `.release/distribution/github-packages`
  - artifactId of parent changed to `cimet-extract-lib-parent` to avoid reactor duplication.
- Added `.release/core/pom.xml` — module POM for core that points its sourceDirectory/testSourceDirectory to the repository root (no source move).
- Added `.release/distribution/jitpack/pom.xml` — distribution module (artifactId = mvp-cimet-extraction) depends on `.release/core`.
- Added `.release/distribution/github-packages/pom.xml` — distribution module (artifactId = cimet-extract-lib) depends on `.release/core`; distributionManagement to be added in Task 5.
- Added `.josesosa/release-architecture.md` — records the chosen architecture decision.
- Updated `.josesosa/release-to-jitpack-tasks.md` — task list now references `.release` layout and added credential spike tasks (5a/5b).
- Updated README.md — Java version set to 21, docs path fixed to `.docs`, and Release modules section added.
- Added a minimal mvnw launcher so validations can run locally via `./mvnw` (wrapper jar exists in `.mvn/wrapper`).

Why the layout and no source moves
- You requested no movement of Java sources or tests. The standard multi-module pattern normally places sources inside module/ directories; since you explicitly rejected moving files, the chosen compromise is: module POMs exist under `.release/` and point at the root source directories.
- This is slightly non-standard but keeps the repository layout intact while enabling canonical module POMs for release and distribution.

Validation performed here (automated runs executed)
1) Maven wrapper availability
  - Action: added a small `mvnw` launcher to call .mvn/wrapper/maven-wrapper.jar
  - Result: ./mvnw works and reports Maven 3.6.3 (wrapper set to 3.6.3)

2) Reactor recognition
  - Command: ./mvnw -N -B validate
  - Result: BUILD SUCCESS. Root POM and modules are syntactically valid and recognized by Maven.

3) Effective POM generation for JitPack distribution
  - Command: ./mvnw -pl .release/distribution/jitpack help:effective-pom -Doutput=.release/distribution/jitpack/effective-pom.xml
  - Result: BUILD SUCCESS; effective POM written (parent inheritance looks correct).

4) Attempted full compile for core module
  - Command: ./mvnw -pl .release/core -am -B package
  - Result: initial failure due to Maven version mismatch, updated wrapper to 3.6.3; after that, fatal compile error: invalid target release: 21
  - Cause: local JVM used by the wrapper was Java 17; project is configured to target Java 21. Java 21 is required to compile code locally.
  - Resolution: must run validation on a machine/CI that has JDK 21.

What passed vs what requires environment changes
- Passed (no environment dependency): module layout, effective POM generation, root validate.
- Requires JDK 21 (environment change): full compilation and tests (mvn verify) and packaging of core/distribution modules.

Commands to validate locally (how you can confirm everything works)
Prerequisite: a JDK 21 installation and JAVA_HOME pointing to it.

1) Ensure JAVA_HOME and java -version show JDK 21
   - echo $JAVA_HOME
   - java -version

2) Validate reactor and modules
   - ./mvnw -N -B validate

3) Full reactor build including tests
   - ./mvnw -B -DskipTests=false verify
     - Expected: BUILD SUCCESS. Surefire + Failsafe run, jacoco reports generated.

4) Package core only (fast check)
   - ./mvnw -pl .release/core -am -B package
   - Expected: .release/core/target/*.jar exists.

5) Package JitPack distribution module
   - ./mvnw -f .release/distribution/jitpack/pom.xml -B package
   - Expected: .release/distribution/jitpack/target/*.jar exists.

6) Effective POM checks (optional)
   - ./mvnw -pl .release/distribution/github-packages help:effective-pom -Doutput=.release/distribution/github-packages/effective-pom.xml

7) JitPack preview build (fork-based)
   - Create a preview tag in a fork (v1.2.2-preview.1). On jitpack.io, Look Up the fork and trigger build for that tag.
   - Expected: JitPack builds successfully and exposes mvp-cimet-extraction artifact for the tag.

8) GitHub Packages deploy test (use fork & PAT)
   - Add distributionManagement to .release/distribution/github-packages/pom.xml pointing at your fork's Packages URL.
   - Add a PAT with write:packages to fork secrets (MAVEN_PUBLISH_TOKEN) or use GITHUB_TOKEN in Actions for the fork.
   - Trigger a release workflow or run locally with a settings.xml referencing the PAT and run deploy (dry-run if desired).

Next actionable tasks (recommended order)
1) Credential spike (Task 5a) — determine whether GITHUB_TOKEN is sufficient in your org or a PAT is required. I can run this and produce `.josesosa/credential-spike.md`.
2) Add distributionManagement to `.release/distribution/github-packages/pom.xml` and update the publish workflow to deploy only that module on stable tags (Task 5b + Task 8 update). Use settings.xml templating in workflow referencing MAVEN_PUBLISH_TOKEN secret or GITHUB_TOKEN as decided.
3) Run full validation on an environment with JDK 21 (local or CI) and perform JitPack preview build on a fork and a GitHub Packages deploy on a fork.

Notes and caveats
- The current setup intentionally keeps sources/tests at repo root per your instruction; that required core POM to point to repository source directories. This works but is non-standard; later consider moving sources into .release/core for conventional layout if opinions change.
- I updated the Maven wrapper to 3.6.3 and added an mvnw launcher so `./mvnw` works even on systems without a global mvn. Ensure CI runners have network access to download the wrapper distribution when first run.

Where to find everything
- Task list: .josesosa/release-to-jitpack-tasks.md
- Architecture decision: .josesosa/release-architecture.md
- Module POMs: .release/core/pom.xml, .release/distribution/jitpack/pom.xml, .release/distribution/github-packages/pom.xml
- Root POM: pom.xml
- README updated with release module guidance: README.md

If you want me to continue now
- I can run Task 5a (credential spike) and produce `.josesosa/credential-spike.md` immediately.
- Or I can wait for you to create a devcontainer branch and provision a devcontainer with JDK 21 and run the compile/tests there.

Signed-off-by: OpenCode
