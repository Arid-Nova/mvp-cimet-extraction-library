# JavaScript and TypeScript extraction

This opt-in adapter extends CIMET with static Express endpoint extraction,
browser/server HTTP call extraction, native IR projection, and snapshot deltas.
It is intended for API evolution and change-impact studies of systems such as
an Express backend with a React frontend. A route is an operation exposed by an
application; declaring a backend component does not establish that each route
is an independently deployed service.

## Build

Requirements: Java 21+, Maven, Node.js 22+, and npm. Run these commands from the
library checkout:

```sh
npm --prefix adapter/javascript ci --ignore-scripts
npm --prefix adapter/javascript test
mvn -Pjavascript-cli -DskipTests package
```

The executable is `target/cimet-javascript.jar`. Keep `adapter/javascript/`
(including its installed dependencies) alongside it. The JAR calls this parser
using Node; JavaScript extraction is not a Java-only runtime. Java consumers can
continue to use the normal library JAR and its existing Java extraction APIs.

The parser reads syntax trees. It does not install dependencies in the input
repository, import its modules, run its build scripts, start its server, or
contact the application database. Parser dependencies belong to the adapter.

## Extract a local snapshot

```sh
java -jar target/cimet-javascript.jar snapshot \
  --adapter adapter/javascript --root /path/to/backend \
  --kind backend --name nutrition-api \
  --output study-output/backend.json \
  --ir-output study-output/backend-ir.json

java -jar target/cimet-javascript.jar snapshot \
  --adapter adapter/javascript --root /path/to/frontend \
  --kind frontend --name nutrition-web \
  --output study-output/frontend.json
```

These are shell examples with line continuations; on PowerShell use one line
or replace the continuation backslashes with PowerShell backticks.

`backend` declares one application boundary. Frontend modules are represented
as client orphans in native CIMET IR rather than increasing the microservice
count. `JSModule` distinguishes source modules from Java classes. Provenance
includes relative file paths, line locations, and parser evidence.

Local snapshot extraction reads the current source directory. For historical
research use the [study runner](../study/README.md), which exports the selected
Git objects and records resolved commit hashes independently of working files.

### Java API

```java
var extractor = IRExtractionService.createJavaScript(Path.of("adapter/javascript"));
var snapshot = extractor.extract(Path.of("/path/to/backend"), "backend", "nutrition-api");
var ir = extractor.toIR(snapshot, "local:nutrition-api", "PINNED_COMMIT");
var changes = new JavaScriptDeltaService().compare(beforeSnapshot, afterSnapshot);
```

Import `IRExtractionService` from the existing
`edu.university.ecs.lab.intermediate.create.services` package and the new
services from `edu.university.ecs.lab.javascript`. JavaScript snapshots use
`{param}` placeholders; native IR projection converts these to CIMET's `{?}`.
Unresolved observations retain their evidence as ordinary methods/calls rather
than inventing a resolved endpoint or HTTP target.

## Compare and combine

```sh
java -jar target/cimet-javascript.jar delta \
  --old study-output/before.json --new study-output/after.json \
  --output study-output/delta.json

java -jar target/cimet-javascript.jar ir \
  --snapshot study-output/backend.json --snapshot study-output/frontend.json \
  --name nutrition-system --output study-output/system-ir.json
```

The JavaScript snapshot delta API is separate from the legacy Java
`DeltaExtractionService` and merge format. Use the JavaScript delta command for
these snapshots; passing JavaScript files through the legacy Java parser does
not enable language support. Line movement alone should not be interpreted as
an API change. A changed handler fingerprint is implementation evidence, not
proof of a breaking contract.

## Architectural analysis and evolution

New snapshots also contain source-module dependencies, resolved local function
calls, function body fingerprints, directly supplied endpoint handlers and
bounded request/response contract observations. Weighted SC/LWF/GWF,
SCCmodularity and coverage-qualified SIDC/SSIC/LOMLC are available through the
architecture commands. Coarse contract evidence, estimates and field/shape
proxies are reported separately; missing information does not become a score.
Snapshots also include bounded, sanitized `architecture.semanticEvidence`:
recognized SQL/ORM access, explicit entity fields, health-like Express routes,
activated proxy calls and resilience policies. No SQL literals, credentials,
proxy target URLs or database execution are needed. These observations enable
AR7 data/schema change impact and source-scoped health/gateway/WrongCuts/wobbly
interpretations. Weighted greedy/hub/AR20 detectors, local function cycles/chains,
AR1 caller loss and AR22 nominal entity evolution are described in the
[architecture capability matrix](javascript-architecture.md).
The opt-in analysis service consumes this richer snapshot evidence:

```sh
java -jar target/cimet-javascript.jar architecture --snapshot backend.json --snapshot frontend.json --name Nutrosal --output architecture.json
java -jar target/cimet-javascript.jar architecture-delta --old architecture-before.json --new architecture-after.json --output architecture-changes.json
```

Use the same system name and analysis settings for before/after reports. The
analysis separates file modules, declared applications/clients, and declared
backend applications. One backend application does not establish a distributed
microservice architecture. Source graph metrics and heuristic findings can be
compared over time, subject to extraction coverage and stable boundaries.

Older snapshots without architecture evidence still support HTTP analysis;
module/function capabilities report missing evidence. The native IR projection
continues to expose endpoints and HTTP calls; the richer architecture commands
consume JavaScript snapshots directly. They do not invoke the unmodified legacy
CIMET2 history runner or Java/Spring-specific detector classes.

See [architecture analysis](javascript-architecture.md) for exact capabilities,
formula parity, bounds, evidence requirements, and unsupported checks. See the
[study runner](../study/README.md) for historical checkpoints and traceable
per-component results. A rise in a metric or candidate count is an observation
to review with maintainers, not proof of architectural degradation.

## Scope and validation

Consult the [parser documentation](../adapter/javascript/README.md) for exact
supported syntax and wrapper configuration. Unsupported syntax and unresolved
dynamic values must remain visible in diagnostics. Review diagnostics before
using counts in a paper.

Static extraction cannot establish production reachability, middleware effects,
runtime-generated routes, authorization behavior, release pairing across
repositories, or compatibility of response schemas. A matching method/path is
a candidate dependency, not proof of a runtime call. Optional/wildcard routes
and unresolved URL construction require particular care.
Socket.IO events, SQL access, and calls through other third-party SDK protocols
are outside this endpoint/call extractor.

The study runner produces candidate links and a worksheet for maintainer
validation. Validate both detected records and missed operations using a
sample selected independently of the extractor. Report precision, recall,
unresolved coverage, and disagreements; do not infer accuracy from route counts
or agreement with a second script alone.

Private repositories and generated study outputs are local inputs. Synthetic
test fixtures in the library do not contain Nutrosal application source.

## Tests

```sh
npm --prefix adapter/javascript test
mvn "-Dtest=JavaScript*Test,ServiceBoundaryDetectorTest,PathNormalizationTest" test
node --test study/*.test.mjs
```

The added CI workflow runs these checks on Windows and Linux. Local validation
was performed on Windows; the remote CI workflow has not yet been run for this
branch.

The upstream Windows baseline at `d8fbbe44f1117165a0fa559da3490e75766be413`
already had four failing cases in `mvn test`: two configuration parsing errors
from `/C:/...` paths, `DeltaAndMergeTest.testComparison`, and
`IRExtractionTest.testIRToJSON`. After this extension, the full 49-test Java run
had the same four failures and no new failing cases. The focused 38-test
adapter/boundary/path suite passed. This is not a claim that the whole upstream
suite is green.

## Runtime and deployment

The adapter parses a repository in one Node subprocess, so it avoids starting a
new runtime for each source file. Java enforces a subprocess timeout and output
limits. Run one extraction job at a time on a small host. The parser's reported
peak RSS covers its Node process only; capacity planning must also include the
JVM, the web server, and operating-system overhead.
The Java process starts Node with a 384 MiB V8 heap ceiling. This is not an RSS
limit. The study runner separately starts the JVM with a 256 MiB heap ceiling.

For a container, include Java 21, Node 22, the executable JAR, and the installed
adapter directory. Run adapter `npm ci --ignore-scripts` at image build time.
Do not run the studied application's install/start commands. The existing
CIMET Studio web image remains pinned to its original Java engine until it is
explicitly upgraded to use these commands and the required adapter runtime.
