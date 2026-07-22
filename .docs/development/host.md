Host (classic) Development
==========================

This file documents the local (non-container) developer workflow.

Prerequisites
- Java 21 JDK installed and JAVA_HOME configured
- Maven 3.6.3 (or rely on the wrapper)
- Git

Typical workflow
---------------
1. Clone the repo
2. Use `./mvnw` to build (preferred)
3. Run unit tests: `./mvnw test`

Code style
----------
- Use Checkstyle rules where configured. Run `mvn checkstyle:check` to validate.
