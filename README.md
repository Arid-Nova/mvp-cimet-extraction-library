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
- Docs index: [/.docs/README.md](./.docs/README.md)
- Schema docs: [/.docs/schema/README.md](./.docs/schema/README.md)
- Devcontainer docs: [/.docs/devcontainer/README.md](./.docs/devcontainer/README.md)

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
- Fork, create a branch, push a PR.
- Run tests and linters locally before opening a PR.

Docs directory
--------------
This repository contains a structured docs directory at `/.docs/`. See the linked pages below for focused guides:

- Schema docs: [/.docs/schema/README.md](./.docs/schema/README.md) — JSON Schema artifacts and validation guidance
- Devcontainer: [/.docs/devcontainer/README.md](./.docs/devcontainer/README.md) — deep dive into the devcontainer image, scripts and extension choices
- Development: [/.docs/development/README.md](./.docs/development/README.md) — regular development workflows, coding standards, and IDE tips
- Testing: [/.docs/testing/README.md](./.docs/testing/README.md) — unit/integration/E2E guidance and CI notes
- Deployment: [/.docs/deployment/README.md](./.docs/deployment/README.md) — packaging and release guidance

For a central index see [/.docs/README.md](./.docs/README.md).

Contact
-------
Issues and PRs welcome. Use GitHub issues for bug reports and feature requests.
