# CIMET JavaScript static adapter

This adapter parses JavaScript, JSX, TypeScript, and TSX with the pinned TypeScript
5.9.3 compiler API. It extracts Express route registrations and HTTP request call
sites. A single Node process parses one repository. Target code is never executed.

## Install and run

Requires Node 22 or newer. Install only the adapter's parser dependency:

```sh
npm ci --prefix adapter/javascript --ignore-scripts
npm test --prefix adapter/javascript
node --max-old-space-size=256 adapter/javascript/analyzer.cjs --root /absolute/checkout --kind backend --name example-api
node --max-old-space-size=256 adapter/javascript/analyzer.cjs --root /absolute/checkout --kind frontend --name example-web --config /absolute/adapter-config.json
```

Successful CLI output is one JSON object on stdout. Failures emit a generic
message on stderr and a nonzero exit status. Windows absolute paths also work.
The adapter does not clone repositories or switch revisions; its caller supplies
an existing snapshot. It does not install or load dependencies from that snapshot.

## Supported evidence

- Express `express()` / `Router()` provenance through imports, require, and local
  aliases; HTTP method registrations, route arrays, chained `route()` methods,
  imported routers, and application/router mount prefixes.
- CommonJS and ESM local exports; registration functions passed a proven Express
  application, including Consign `include/then/into` loading. An arbitrary object
  with a `get` method is not treated as a router.
- Terminal handler references through local controller imports. Handler body
  fingerprints include the supplied middleware functions that can be resolved.
- Axios method calls, named aliases, callable Axios/config forms, `request`, and
  instances created with static `baseURL` and method defaults; unshadowed `fetch`.
  `axios.all` is a combinator and is excluded.
- Declared local request wrappers; constants, simple string concatenation,
  template strings, declared backend environment bases, and imported constants.

These are static registration/call-site candidates. Source presence does not
prove runtime reachability, deployment boundaries, or successful HTTP requests.

## Wrapper and origin configuration

The default profile reflects the inspected Nutrosal wrappers. It is an explicit
mapping, not automatic inference of arbitrary wrapper implementations:

```json
{
  "backendEnv": ["VITE_NUTROSAL_BE", "VITE_LOCAL_URL"],
  "backendOrigins": [],
  "wrappers": [
    {"module": "server_requests", "kind": "server"},
    {"module": "dataUtility", "kind": "method-first", "relativeTarget": "backend", "methodPolicy": "get-or-post"},
    {"module": "dataUtilityImage", "kind": "method-first"}
  ],
  "maxFiles": 20000,
  "excludeDirectories": []
}
```

Wrapper names match imported module basenames (without extensions), and the
import must resolve to a local source file. Imports can use aliases. `server`
recognizes `get_request`, `post_request`, `put_request`, `patch_request`,
`delete_request`, `head_request`, `options_request`, and method-named calls
including `get({path})`. `method-first` uses arguments 0 and 1 as method and URL.
`get-or-post` models the inspected dataUtility implementation: recognized GET
stays GET and other recognized literal methods become POST. Without that policy,
the declared method is retained. Unknown methods remain `UNKNOWN`.

For a generic repository, use `{"backendEnv":[],"wrappers":[]}` and then add
only verified mappings. Wrapper implementation calls are excluded to avoid
counting application calls a second time. Thus wrapper retries, telemetry, cache
hits, and conditional fallbacks are not a runtime request-count estimate.

The adapter never reads environment values. A base expression referencing a
declared backend environment variable identifies a backend call. A literal
absolute URL is external unless its origin is in `backendOrigins`. For
dataUtility, a relative path is backend by the declared profile; dataUtilityImage
has no such assumption. Unknown bases stay unknown, including `this.SERVER_URL`.

## Output schema and matching

`schemaVersion` is 1. `component` contains `name` and `kind`. `endpoints`, `calls`,
and `diagnostics` use repository-relative file paths and one-based lines.

Each endpoint/call has a stable `id`, `method`, `path`, `normalizedPath`, `caller`,
`bodyFingerprint`, `contractFingerprint`, and combined `fingerprint`. Endpoints
also have `handler` and, when resolved, `handlerFile`/`handlerLine`. Calls include
`source`, `target` (`backend`, `external`, `unknown`), and `resolution`
(`literal`, `template`, `unresolved`).

- An unresolved path is `null`, never an invented concrete path.
- JS dynamic path segments and ordinary Express named parameters normalize to
  `{param}`; case is preserved and a final slash is removed except at root.
- Express optional parameters, wildcards, regex strings, and other complex
  patterns retain their raw path but have `normalizedPath: null` and a diagnostic.
  They must not be used for exact or template contract matches.
- Unmounted routers retain registration evidence with null complete paths and
  an unresolved mount diagnostic.
- URL origins, userinfo, query values, and fragments are not exported. Absolute
  call URLs retain only their path. Unknown methods/options produce diagnostics;
  options containing unresolved spreads/computed properties are conservative.
- IDs use component, relative file, enclosing logical function, method, path,
  and occurrence, excluding line numbers and body content. Whitespace/body edits
  preserve IDs. Moving files/functions or inserting identical earlier occurrences
  can change IDs. Contract hashes fall back to raw paths for unsupported patterns.
- Body fingerprints hash parser tokens and AST node-kind structure, excluding
  positions and comments. Harmless whitespace is ignored, while newline changes
  that alter the AST through automatic semicolon insertion are detected. For
  endpoints they combine resolved handler/middleware bodies; for calls they hash
  the request expression. They are syntax change indicators, not semantic equivalence
  proofs, and do not transitively hash all callees or resolve runtime dispatch.

`stats` includes parser/version, counts, `elapsedMs`, and `peakRssKiB` from
`process.resourceUsage().maxRSS`. Performance fields vary between runs and report
this Node subprocess only, not combined JVM/web application memory. The 256 MiB
V8 heap setting is not a 256 MiB RSS limit.

## Source architecture evidence

New snapshots include the additive `architecture` object with its own
`schemaVersion: 1`. Existing HTTP fields retain their schema; readers can continue
to accept older snapshots without architecture evidence. The graph is built from
the same parsed source corpus, without additional target file reads or execution.

```text
modules: [{id: file, file, line: 1}]
dependencies: [{id, source: file, target: file|null, line, kind, resolution, typeOnly}]
functions: [{id, file, line, endLine, name, bodyFingerprint, unresolvedCalls}]
calls: [{id, file, line, callerId, targetId}]
endpointHandlers: [{endpointId, functionId}]
summary: {... counts ...}
limitations: [... fixed explanations ...]
```

Every valid included source file is a module node. Dependencies cover imports,
re-exports, unshadowed require and require aliases, dynamic imports, TypeScript
import-equals/import types, and proven default-configuration Consign loads.
`kind` identifies that evidence. A resolved target is an included module's file
path. External and unresolved targets are explicitly null; raw import specifiers
are never exported, including URLs and credential-bearing strings. Type-only
edges are retained and must be excluded from runtime dependency calculations.
Mixed type/value imports can produce two edges with different `typeOnly` values.

`resolvedRuntimeDependencies` counts static dependency observations eligible for
a runtime-source graph. It is not proof that a module loads at runtime. Conditional
imports, unreachable source, and Consign registrations remain source evidence.
Relative asset, stylesheet, and JSON imports contribute to unresolved dependencies
because those files are excluded from the code corpus. Package aliases and
dependencies are not loaded to resolve them.

Local call edges require a proven function target and an enclosing source
function. Lexical aliases, object methods, local imports, ESM named/star/namespace
re-exports, and CommonJS exports are supported. Resolution has cycle/depth guards;
ambiguous star exports, shadowed require/exports, and reassigned bindings are not
assigned guessed targets. The graph omits class-instance dispatch, implicit `this`,
returned-function inference, and callback invocation by libraries. Calls inside
callbacks may resolve, but invocation of the callback itself is not inferred.

Functions are actual syntax functions, including callbacks; no synthetic module
initializer nodes are added. `resolvedTopLevelCallsOmitted` reports resolved calls
without an enclosing function. `unresolvedCallSites` includes library, builtin,
dynamic, and unresolved local calls. These counts are coverage information, not
error or defect counts. Function IDs use component identity, file, logical parent,
name/context, and occurrence rather than lines or body hashes. Anonymous callbacks
on the same line have distinct IDs; inserting earlier identical callback contexts
may change occurrence-based identities.

Endpoint bindings include every statically resolved handler/middleware directly
supplied to a registration, allowing several unique function IDs per endpoint.
Global `app.use` middleware, middleware inherited through mounted routers, and
dynamically assembled middleware are not propagated. Having a binding for an
endpoint does not establish complete middleware coverage or runtime invocation
order. Downstream reverse graph traversal provides candidate impact paths, not
proof of behavior changes or independent microservice boundaries.

## Request and response contract evidence

`architecture.contracts` is another optional object with `schemaVersion: 1`.
Its `operations` array has exactly one record per extracted endpoint:

```text
{endpointId,
 inputs: [{location, name, type, evidence, file, line}], inputComplete,
 responses: [{kind, type, shape, complete, file, line}], outputComplete,
 limitations: [...]}
```

Input locations are `body`, `query`, `params`, and `headers`. Types are `string`,
`number`, `boolean`, `array`, `object`, `null`, or `unknown`. Evidence is `declared`,
`coercion`, `property-read`, or `route-param`. The helper follows request/response
parameters from resolved supplied handlers through lexical aliases, destructuring,
bounded local helper calls, and captured callbacks. Recursive entries clear stale
parameter/local aliases. Unknown calls and unrelated objects do not acquire a
request or response role. Explicit unshadowed `Number`, `parseInt`, `parseFloat`,
`String`, `Boolean`, and unary coercions provide field type evidence; plain reads
remain unknown. Route parameter strings alone never establish a complete input.

Response observations recognize proven response receivers using `json`, `send`,
and `end`, including common response-builder chains. Canonical shapes contain
only sorted field names and type tags, for example `object{data:null,ok:boolean}`;
literal strings, numbers, payloads, headers, and annotation prose are not exported.
Dynamic values remain `unknown`. Aggregate identifiers are conservatively unknown
unless explicitly declared, because initializer literals can escape into mutating
code. Direct literal arrays/objects, scalar constants, and bounded explicit source
type declarations can supply shapes. `end()` has shape `empty` and unknown type;
it is not a known typed output. Duplicate fields, spreads, unsupported types,
mutation, and unknown nested values prevent complete shape claims.

`inputComplete` requires one resolved handler and an explicit closed request
declaration containing all four input locations with known fields and types.
Inline TypeScript/JSDoc object declarations and simple same-file TypeScript
aliases/interfaces are supported. Open indexes, optional fields, generics,
intersections/unions, imported types, and arbitrary validation libraries are not
treated as closed schemas. Completeness describes a source declaration, not proof
of runtime validation. No reads, or a subset of reads, is never a complete schema.

`outputComplete` requires a single resolved handler with a simple terminal
response or exhaustive `if`/`else` branches, complete response shapes, and no
opaque calls. Complex control flow stays incomplete. Any `.use(...)` registration
in the included corpus conservatively prevents operation completeness because
global/inherited middleware is not propagated. This guard can also reject an
unrelated method named `use`; it cannot establish middleware provenance.
Captured callback evidence does not prove callback execution. Observed fields and
shapes support explicitly labelled partial cohesion estimates; strict typed
metrics must respect completeness and unknown values.

Per operation, contract extraction caps inputs at 256, responses at 128, local
function contexts at 128, AST visits at 40,000, declared type work at 4,000 nodes,
recursive depth at 8, and individual shapes at 2,048 characters. Type expansion
uses a cache and early shape limits, including tuples and nested arrays.
`summary.truncatedOperations` and operation limitations report reached bounds.
Other summary counts report observed fields/responses and complete operations.
Each function's `unresolvedCalls` counts all unproven callees, including libraries
and builtins; this is conservative coverage evidence for structural cohesion,
not a count of missing local function targets or a runtime completeness guarantee.

## Database and operational source evidence

`architecture.semanticEvidence` is additive and has its own `schemaVersion: 1`.
Older snapshots without it remain valid. It is built from the existing source
ASTs; target packages, environment files, deployment manifests, and database
connections are never loaded or executed.

```text
dataAccess: [{id,file,line,endLine,functionId,kind,library,operation,
              tables,columns,shape,fingerprint,complete}]
entities: [{id,file,line,endLine,functionId,library,name,
            fields:[{name,type}],fingerprint,complete}]
healthEndpoints: [{endpointId,functionIds,classification,basis}]
proxies: [{id,file,line,endLine,functionId,library,kind,operation,target,fingerprint}]
resilience: [{id,file,line,endLine,functionId,library,kind,operation,fingerprint,
              appliesToFunctionId?}]
summary: {... counts, inspectionComplete, boundsReached ...}
limitations: [... fixed explanations ...]
```

Database observations require recognized `mysql`, `mysql2`, `mysql2/promise`, or
`pg` client provenance. SQL `query`/`execute` calls can resolve through local
imports, exports, stable aliases, and bounded parameter-forwarding wrappers.
This includes Nutrosal's exported connections and `queryRunner` helpers. Both
the underlying sink and caller observations are retained; these are source
observations, not distinct runtime requests. IDs are stable across line/value
edits; `functionId` links each observed call site to the local call graph, or is
null for module-level code. Wrapper propagation permits four rounds and eight
descriptors per function. Dynamic arguments stay unknown.

SQL shapes contain coarse operation, table and column identifiers. Single-quoted,
double-quoted and PostgreSQL dollar-quoted contents, numeric values, comments and
connection settings are omitted. Only hashes of this sanitized structure are
retained; raw SQL is never exported. Templates retain static fragments and mark
interpolations unknown. Joins, CTEs, nested queries, computed expressions, dialect
ambiguity and dynamic SQL are incomplete. `complete` concerns the supported
coarse shape extraction, not SQL validity, complete database schema knowledge or
runtime execution. Fingerprints intentionally ignore SQL value-only changes.

Supported ORM observations include Knex query builders, Prisma client model
operations, Mongoose models, and Sequelize models/raw queries. ORM names are
logical model identifiers, not verified physical tables. Explicit Mongoose
`Schema`/`model` and Sequelize `define` declarations yield field names and type
tags; unknown/spread/duplicate fields prevent complete field-shape claims.
Schema modifiers/defaults/validation logic and generated/imported Prisma schemas
are not interpreted. Arbitrary objects with similar method names are not ORM
evidence. `unresolvedDataAccessCandidates` combines proven SQL calls with unknown
operations and coarse unproven query-like calls; it is not a defect count.

Health observations identify endpoint path segments such as `health`, `healthz`,
`ready`, `readiness`, `live`, `liveness`, and `ping`, with supplied handler IDs.
This is explicitly a naming heuristic, not proof of a working readiness or
liveness probe. Proxy observations require proven Express mounting of
`http-proxy-middleware`, or `http-proxy` `web`/`ws`/`listen` calls. Imports and
unmounted factories are not registrations; the latter have a separate count.
Proxy targets are only `literal-redacted`, `dynamic`, or `unknown`; URLs and
credentials are never emitted.

Resilience observations recognize explicit Opossum circuit breaker construction
and `fire`, `p-retry` invocation, `retry.operation`/`attempt`/`retry`, mounted
Express rate-limit middleware, rate-limiter-flexible construction/`consume`,
Bottleneck construction/`schedule`/`submit`/`wrap`, and p-limit construction/use.
The `operation` distinguishes construction from invocation or mounting.
`appliesToFunctionId` is present only when a supplied callback resolves locally.
These observations do not prove effective limits, retries, circuit state or
runtime bulkhead isolation. Literal settings and target URLs are deliberately
excluded from fingerprints, so value-only configuration changes are not detected.

Bounds are 20,000 observations per category, two million AST/resolution work
steps, resolution depth 64, SQL length 16,384 characters, 4,096 SQL tokens, 64
table/column names, 128 entity fields, and 2,048 emitted SQL shape characters.
`inspectionComplete` also becomes false for parser-error files, oversized source
files, or skipped symbolic links. Numeric `sourceExcludedPaths`,
`sourceParseErrorFiles`, `sourceOversizedFiles`, and `sourceSymlinkPaths` counters
explain these exclusions; `boundsReached` remains reserved for semantic work
limits. These flags never mean exhaustive semantic or deployment coverage.
No recognized evidence is not evidence that production lacks the capability.

## Privacy, bounds, and limitations

Only recognized source extensions are read. Dot directories, `.env`, dependency,
build, upload, public, and test directories are skipped; symlinks are not followed.
Individual source files over 5 MiB are skipped with a diagnostic; the default
source count limit is 20,000. Configuration is limited to 64 KiB by the CLI.
Parser errors use fixed generic messages and exclude the affected file. Raw
source, request payloads, headers, credentials, and handler bodies are not emitted.
Only hashes of bodies are exported; public schemas still contain application
route paths, module names, and function names and should be handled accordingly.

This is a bounded syntactic analysis, not a full TypeScript type checker, runtime
interpreter, or interprocedural data-flow engine. Limitations include dynamic
imports/routes, package aliases, HTTP-role propagation through re-exports, router factories and plugin patterns,
mutations of object properties, runtime environment selection, implicit `this`
bindings, higher-order wrappers, and complex control flow. Registration reachability
is not evaluated. Inputs with mutations, dynamic helper-returned URLs, unresolved
options, unsupported framework patterns, or unknown mounts need manual review.
Tests and inventory agreement are coverage evidence, not a measured precision or
recall result. Evaluation requires manually validated endpoint/call/link labels.

The synthetic suite exercises router provenance, mounts, Consign registration,
wrapper imports, templates, aliases, shadowing, method uncertainty, unsupported
route patterns, deterministic structure, body changes, parser errors, source
size limits, symlink exclusion, output sanitization, and absence of target execution.
