Maven Wrapper (mvnw)
====================

Why use the wrapper
-------------------
- Reproducible builds: the wrapper ensures everyone uses the same Maven version.
- Simple onboarding: developers can build without installing Maven globally.

Usage
-----
- `./mvnw clean package`
- `./mvnw test`

Regeneration
------------
- Regenerate using the Takari wrapper if needed: `mvn -N io.takari:maven:wrapper -Dmaven=3.6.3`
