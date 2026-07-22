Maven Wrapper (mvnw) — Why we prefer it
=====================================

Summary
-------
This project prefers the Maven Wrapper (`./mvnw`) for local development, CI, and tooling because it ensures a reproducible, predictable build environment for all contributors.

Benefits of using the wrapper
----------------------------
- Reproducible Maven version: the wrapper downloads and uses the exact Maven version declared in `.mvn/wrapper/maven-wrapper.properties` (this repo uses 3.6.3). No developer needs to manage Maven versions globally.
- Onboarding friction reduced: contributors can build the project with `./mvnw` without installing Maven globally.
- CI parity: the same wrapper behaves the same in CI, smaller chance of "works on my machine" failures caused by different Maven versions or settings.
- Local overrides still possible: contributors can set `MAVEN_OPTS` or configure a global `maven` install if they prefer, but CI and the project use the wrapper as the canonical command.

Recommended usage
-----------------
- Build: `./mvnw -B -DskipTests clean package`
- Run tests: `./mvnw -B test` or `./mvnw -B -DskipTests=false verify` for full verification
- Use `./mvnw` in scripts, devcontainers, and CI workflows instead of `mvn`.

Regenerating or updating the wrapper
-----------------------------------
If you need to regenerate the wrapper (to update the takari wrapper JAR or bump the Maven version):

1. On a machine with a global `mvn` installed, run:

```
mvn -N io.takari:maven:wrapper -Dmaven=3.6.3
```

2. Commit `.mvn/wrapper/maven-wrapper.jar` and `.mvn/wrapper/maven-wrapper.properties` to the repository.

Notes & troubleshooting
----------------------
- If `./mvnw` fails due to file permissions, run `chmod +x ./mvnw` and ensure the file is executable in git (`git update-index --add --chmod=+x mvnw`).
- If you see native-access warnings from Jansi/Guava on modern JDKs, the devcontainer sets `JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED` to suppress these; CI can set the same if needed.
- The wrapper relies on network access to download the Maven distribution on first run; CI/devcontainers should allow outbound access or cache the distribution.

Why we keep wrapper-specific logic in scripts
-------------------------------------------
Small launcher conveniences (in `mvnw`) ensure `-Dmaven.multiModuleProjectDirectory` is set correctly when the wrapper is invoked from arbitrary working directories. The devcontainer uses a central `containerEnv` to provide consistent JVM flags for all Java processes.
