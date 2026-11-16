Release & Packaging
====================

Packaging
---------
- Build a release with Maven: `./mvnw -P release -DskipTests=false package` (adjust profile as needed).

Include schemas
---------------
- If you want schemas packaged with the artifact, copy `.docs/schema/*.json` into `src/main/resources` as part of the release build.

Publishing
----------
- The project's `pom.xml` defines distributionManagement for GitHub Packages. Configure credentials as needed in CI or local `~/.m2/settings.xml`.
