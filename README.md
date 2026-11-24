# CIMET Extraction Library

Reliable tooling to extract an intermediate representation (IR) and change deltas from microservice codebases.

This repository provides:
- Java libraries and services to clone repositories, scan code, and produce a machine-readable IR describing microservices, endpoints, and relationships.
- A delta extraction pipeline that computes SystemChange objects between commits.
- Utilities to merge deltas into IRs and to generate JSON Schema documentation for produced artifacts.

Project status
--------------
Active development. The repository includes a reproducible devcontainer for local development and CI parity.

## Polyglot extraction

The regular extraction API now routes each supported source file through an extensible
`LanguageParser` registry: Java, JavaScript/TypeScript, Python, Go, and C# can coexist
in one repository or across several repositories. Java keeps its JavaParser adapter;
JavaScript/TypeScript uses the bundled TypeScript AST adapter, and Python/Go/C# use
tree-sitter rules. Framework and type-resolution coverage differs by language.

See [polyglot extraction](docs/polyglot-extraction.md) for supported extensions,
parser capabilities, source provenance, local-source APIs, additional-language
registration, and limits. JavaScript requires Node and the installed trusted adapter;
Java-only extraction does not start Node. Target applications are never installed,
imported, built, or executed.

Snapshot comparison and revision delta extraction share this registry. Deltas retain
per-file/component changes and an additive complete target snapshot for exact
multi-class, multi-repository and application-boundary merges. Renames use deletion
plus addition in the existing change-type schema. Full target extraction may cost
more than an incremental diff; no Java performance-regression percentage is asserted.

Run `npm ci --prefix adapter/javascript --ignore-scripts` followed by `mvn clean verify`
for all deterministic local tests and packaging. Tests which clone public repositories
are explicitly tagged `network` and run with `mvn -Pnetwork-integration verify`; they
remain separate from hermetic fixture coverage. Run `npm test --prefix adapter/javascript`
and `node --test study/*.test.mjs` for the parser and study-runner suites.

## JavaScript / TypeScript API study support

An opt-in AST adapter supports Express endpoints and JavaScript/TypeScript HTTP
call sites, including React JSX/TSX and configurable local request wrappers.
It runs through a Java API or an executable JAR and projects endpoints/calls into
CIMET's intermediate representation. Frontend files retain their client role;
endpoint counts are not microservice counts.

The JavaScript architecture extension additionally extracts local module and
function dependencies and runs bounded graph analysis, degree-coupling metrics,
cycle/chain observations, API caller checks, and historical comparisons with
candidate endpoint impact paths. Each report states which capabilities have
sufficient evidence. Weighted SC/LWF/GWF and SCC modularity are available over
static observation graphs. SIDC, LOMLC and SSIC include explicit contract or
local-function coverage, with partial estimates separated from complete-contract
values. Additional JavaScript detectors now cover weighted greedy/hub/AR20
candidates, function cycles/chains, health-like routes, activated Node proxies
and resilience policies, the legacy WrongCuts analogy, AR1 endpoint-removal
caller impact, AR7 data-access impact, and AR22 explicit entity evolution.
These source observations state their scope and uncertainty; Java/Spring
annotations and production deployment behavior are not inferred.

See [JavaScript adapter](docs/javascript-adapter.md) for setup, supported patterns,
snapshot/delta commands and limitations, and [study runner](study/README.md) for
reproducible analysis of local Git revisions. The existing Java extraction APIs
continue to use the Java parser.

See [JavaScript architecture analysis](docs/javascript-architecture.md) for the
capability matrix, differences from CIMET2's original metrics, CLI commands,
and interpretation of historical indicators. The study runner also accepts
`study/nutrosal-history.config.json` for pinned calendar checkpoints.

## Prerequisites

* Maven 3.6+
* Java 21+
* Node.js 22+ for the optional JavaScript adapter

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
npm ci --prefix adapter/javascript --ignore-scripts
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
- Local integration and packaging: `mvn verify`; public-repository scenarios: `mvn -Pnetwork-integration verify`
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
