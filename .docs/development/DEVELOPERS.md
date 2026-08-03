Developing Locally (Developers Guide)
====================================

Preferred: Devcontainer (recommended)
-------------------------------------
We recommend contributors use the provided devcontainer for a reproducible environment that matches CI.

Quick start (recommended):

1. Install Docker Desktop (or equivalent container runtime) and VS Code with the Remote - Containers extension (or use the CLI).
2. Open the repository in VS Code.
3. Select: "Dev Containers: Rebuild and Reopen in Container".

Why use the devcontainer?
- Ensures Java 21 and Maven versions match CI.
- Preinstalls recommended VS Code extensions and tools like `shellcheck` for consistent linting.
- Runs post-create validation scripts that verify the Maven wrapper and workspace health.

Devcontainer details
- See: `./.docs/development/devcontainer.md` for full devcontainer design, post-create scripts, and maintenance notes.

Host (classic) development
--------------------------
If you prefer developing on your host machine (not in the container), follow these minimal prerequisites:

- Java 21 JDK installed and JAVA_HOME configured
- Maven 3.6.3 (or rely on the wrapper `./mvnw`)
- Git

Typical host workflow:

```
git clone <repo>
cd mvp-cimet-extraction-library
./mvnw -B -DskipTests clean package
./mvnw -B test
```

Testing and CI
--------------
- Unit tests: `./mvnw test`
- Integration tests: `./mvnw verify` (runs failsafe)
- CI should run `./mvnw -B -DskipTests=false verify` to exercise unit and integration tests.

Contributing (how to contribute)
--------------------------------
- Look at GitHub issues and pick work to do. If an issue exists, comment "I'm working on this" to avoid duplication.
- Create a branch using `issue-<number>/<short-desc>` (for example `issue-123/fix-npe-in-merge`).
- Implement changes and run tests locally.
- Open a Pull Request referencing the issue and include verification steps.

If you do not have push access, opening a PR from a fork is supported.

PR checklist (what reviewers expect)
- Tests added/updated and passing
- Code formatted and style checks pass
- Documentation updated in `.docs/` if behavior or public API changed
- PR description includes how to verify changes locally

Useful references
- Devcontainer design & scripts: `./.docs/development/devcontainer.md`
- IDE guidance: `./.docs/development/ide_vscode.md` and `./.docs/development/ide_jetbrains.md`
- Maven wrapper rationale: `./.docs/development/maven-wrapper.md`
