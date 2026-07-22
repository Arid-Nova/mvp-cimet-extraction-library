# CIMET Extraction Library

Reliable tooling to extract an intermediate representation (IR) and change deltas from microservice codebases.

This repository provides:
- Java libraries and services to clone repositories, scan code, and produce a machine-readable IR describing microservices, endpoints, and relationships.
- A delta extraction pipeline that computes SystemChange objects between commits.
- Utilities to merge deltas into IRs and to generate JSON Schema documentation for produced artifacts.

Project status
--------------
Active development. The repository includes a reproducible devcontainer for local development and CI parity.

License
-------
This project is licensed under the Apache License 2.0 — see the [LICENSE](./LICENSE) file for details. (SPDX: Apache-2.0)

Quick Links
-----------
- Docs index: [/.docs/index.md](./.docs/index.md)
- Schema docs: [/.docs/schema/schema.md](./.docs/schema/schema.md)
- Development & Devcontainer: [/.docs/development/DEVELOPERS.md](./.docs/development/DEVELOPERS.md)

Table of Contents
-----------------
1.  Overview
2.  Quickstart (regular development)
3.  Quickstart (devcontainer)
4.  Testing
5.  Contributing
6.  Docs directory (detailed topics)

Overview
--------
The library extracts an intermediate JSON representation of a microservice system (MicroserviceSystem) and can compute SystemChange (deltas) between commits. Outputs are JSON files intended to be consumed by downstream analysis tooling.

Sample Outputs (brief)
----------------------
Example IR (intermediate representation) and a delta (SystemChange) are produced as JSON under `./output/`.

IR example (truncated):

```json
{
  "name": "Train-ticket",
  "commitID": "1.0",
  "microservices": [
    {
      "name": "ts-rebook-service",
      "path": "./clone/train-ticket-microservices-test/ts-rebook-service",
      "controllers": [ { "name": "WaitListOrderController.java", "classRole": "CONTROLLER" } ],
      "entities": [],
      "files": []
    }
  ],
  "orphans": []
}
```

SystemChange (delta) example (truncated):

```json
{
  "oldCommit": "06f3e1e...",
  "newCommit": "82949fa...",
  "changes": [ { "oldPath": "...PriceController.java","newPath":"...PriceController.java","changeType":"MODIFY","classChange":{} } ]
}
```

Quickstart — Regular Development
--------------------------------
Prerequisites
- Java 21+ (a compatible JDK installed)
- Maven 3.6+
- Git

Get the code
```
git clone https://github.com/Arid-Nova/mvp-cimet-extraction-library.git
cd mvp-cimet-extraction-library
```

Build (recommended: use the Maven wrapper)
```
./mvnw -B -DskipTests clean package
```

Run tests
```
./mvnw -B test
```

Quickstart — Devcontainer (recommended for contributors)
-------------------------------------------------------
Prerequisites
- Docker Desktop (or compatible docker runtime)
- VS Code + Remote - Containers extension (optional if you use the CLI)

Open in container
1. Open the repository in VS Code.
2. Use "Dev Containers: Rebuild and Reopen in Container".

What the devcontainer provides
- Java 21 runtime
- Maven
- Recommended VS Code extensions preinstalled
- Post-create scripts that validate the workspace and verify the Maven wrapper

Testing (local & in-container)
-----------------------------
- Unit tests: `mvn test`
- Integration/E2E: `mvn verify` (runs failsafe)
- There is a lightweight test that generates JSON documentation: `mvn -Dtest=JSONDocumentationTest test` or run the JsonSchemaService from IDE.

Contributing
------------
- Look at the GitHub issues and pick work to do. Comment on the issue "I'm working on this" to avoid duplication.
- Create a branch using `issue-<number>/<short-desc>` (e.g. `issue-123/fix-npe`) and open a PR when ready. If you don't have push access, opening a PR from a fork is supported.
- Run tests and linters locally before opening a PR.

Docs directory
--------------
This repository contains a structured docs directory at `/.docs/`. See the linked pages below for focused guides:

 - Schema docs: [/.docs/schema/schema.md](./.docs/schema/schema.md) — JSON Schema artifacts and regeneration instructions
 - Development: [/.docs/development/DEVELOPERS.md](./.docs/development/DEVELOPERS.md) — developer workflows, contributing, devcontainer, and testing (preferred devcontainer)
 - Devcontainer: [/.docs/development/devcontainer.md](./.docs/development/devcontainer.md) — devcontainer design, scripts and extension choices
 - Deployment & Release: [/.docs/development/release.md](./.docs/development/release.md) — packaging and release guidance

For a central index see [/.docs/index.md](./.docs/index.md).

Contact
-------
Issues and PRs welcome. Use GitHub issues for bug reports and feature requests.
