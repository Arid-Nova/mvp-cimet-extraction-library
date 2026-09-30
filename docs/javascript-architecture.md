# JavaScript architecture observations

`JavaScriptArchitectureService` analyzes saved static JavaScript snapshots. It never imports, builds, or executes the analyzed application. This opt-in API complements the existing Java pipeline; it does not claim complete parity with every CIMET2 detector.

```java
var service = new JavaScriptArchitectureService();
ObjectNode architecture = service.analyze(List.of(backendSnapshot, frontendSnapshot), "Nutrosal");
ObjectNode delta = service.compare(beforeArchitecture, afterArchitecture);
```

The CLI exposes `architecture --snapshot one.json --snapshot two.json --name System --output architecture.json` and `architecture-delta --old before.json --new after.json --output changes.json`. See the [JavaScript adapter documentation](javascript-adapter.md) for extraction commands. Inputs must use the same component names and kinds across history. Repository identities are hashed in architecture reports, avoiding propagation of credential-bearing provenance URLs.

## Three distinct graph scopes

| JSON graph | Nodes | Edges | Interpretation |
|---|---|---|---|
| `graphs.modules` | Source files, qualified by declared component | Distinct resolved local runtime imports/requires | A module dependency graph. Type-only, external and unresolved imports are counted separately and excluded. |
| `graphs.components` | Declared backend applications and frontend clients | Distinct unambiguous HTTP method/path candidate component links | A static application/client graph; no claim of verified deployment routing. |
| `graphs.backendApplications` | Declared backend applications only | The same candidate links restricted to backend callers | Service-topology observations require at least two backends. With fewer, status is `not-applicable` and degree summaries/density are null. |

Endpoints, functions, source files, and frontend clients are never counted as additional microservices. Boundaries are supplied by the caller; independent deployability is not inferred. Self-imports are recorded as self loops and cyclic components, but excluded from neighbor degrees and density.

Each graph includes source-linked nodes and edges, per-node degrees, aggregate metrics, strongly connected components, cycle witnesses, and bounded simple-chain witnesses. Multiple source observations for one directed pair become one edge with an `observations` count; that count is not traffic, execution frequency, or a weight used in the metrics.

## Relationship to CIMET2

The definitions and detector families were checked against the local CIMET2 revision `44dd7923eedfdbe51f602194cb8f351107556fc0`, principally `detection/metrics/models/DegreeCoupling.java`, `antipatterns/services/GreedyService.java`, `HubLikeService.java`, `ServiceChainMSLevelService.java`, and `architecture/models/AR3.java`, `AR4.java`, and `AR6.java`. Algorithms are implemented here over snapshot JSON; the older JClass/Spring pipeline is not called.

| Capability | This implementation | Relationship / remaining gap |
|---|---|---|
| AIS | Number of distinct incoming neighbors | Matches the original degree definition; file-level values are explicitly module analogs. |
| ADS | Number of distinct outgoing neighbors | Matches the original degree definition. |
| ACS | AIS multiplied by ADS | Matches the original formula. |
| Degree summaries | Maximum, mean, population standard deviation | Same summary definitions; no NaN for empty or inapplicable graphs. |
| Density / SCF | Nonself directed pairs divided by `N * (N - 1)` | Same simple-graph definition; null when undefined or inapplicable. |
| Reciprocal pairs / SIY | Distinct unordered pairs with edges in both directions | Same pair-count definition, excluding self loops. |
| Cyclic dependency | Iterative strongly connected components, including self loops | Complete for the supplied graph; witnesses are bounded examples, not enumeration of every simple cycle. |
| Chains | Simple paths reaching the configured number of nodes | Bounded witnesses, unlike the old unbounded recursive enumeration. They are excluded from finding deltas. |
| Incoming/outgoing candidates | Distinct-neighbor degree at or above the threshold | **Different from** CIMET2 greedy/hub detectors that sum weighted REST edges. Default threshold 6 is transparent and configurable, not an empirically validated defect threshold for modules. |
| AR3/AR4 analogs | Unmatched/ambiguous/case-only calls and endpoints with no observed caller | Partial static API observations. They include frontend clients and preserve unknown/external categories. |
| AR6-style impact | Changed function fingerprints propagated backward along resolved local calls to endpoint handlers | A new static reachability observation, not the original Java service-class heuristic and not a prediction of inconsistent behavior. |
| SC, LWF, GWF, SCCmodularity | Unsupported | Original weighted structural-coupling and partition-modularity formulas have not been ported and validated. SCC counts are **not** SCCmodularity. Graph data may support future implementations. |
| SIDC, SSIC, LOMLC | Unsupported | Typed operation parameters and related cohesion inputs are not recovered. |
| WrongCuts, health checks, API gateway, wobbly interactions, AR7 | Unsupported | Required domain/service/repository roles, contracts, framework configuration or data-access semantics are absent. Applying old Spring-specific tests would be misleading. |

## Conservative HTTP observations

Calls targeting `external` remain external. Unknown targets, unknown HTTP methods, unresolved URLs, and wholly dynamic paths such as `/{param}` remain unknown. Literal paths and whole-segment `{param}` paths are compared segment by segment. Express `ALL` and GET candidates for HEAD are considered, but overlapping registrations remain ambiguous; routing order is not simulated. Duplicate registrations are not silently collapsed to make an ambiguous result appear unique.

Matching defaults to case-sensitive. An explicit `architecture.caseSensitiveRouting: false` enables case-insensitive matching. With no routing evidence, otherwise compatible case-only alternatives are reported separately and never create graph edges. This represents uncertainty about routing configuration, not a confirmed broken call. Slashes and path structure are not changed to guess application routing.

Embedded placeholders such as `/reports/{param}.pdf` are currently unsupported by the matcher. Their coarse segment candidates are retained as unknown; they do not create accepted edges or an unmatched-call finding. Unsupported endpoint templates are not labeled unused. Origin routing, API gateway rewrites, payload schemas, authentication, authorization, middleware order and runtime dispatch are outside these matches.

AR4-style `no-observed-caller` means no caller candidate within the supplied snapshots. External customers, mobile clients, dynamic calls, and unextracted code may still use the endpoint. A backend analyzed alone is an incomplete client universe. Source coverage and findings must be interpreted together.

## History and potential endpoint impact

`compare` uses stable node, edge and finding identities, excluding line numbers, source ordering and commits. It reports introduced/resolved/persisting observations, graph additions/removals, and numeric metric differences. Different names, conflicting repository identities, changed analysis options, duplicate/dangling records, or changed architecture availability for a shared component are rejected. Missing architecture evidence is not treated as deleted code.

The comparison excludes sampled chain witnesses from finding counts. Incomplete HTTP searches suppress HTTP/component finding deltas; missing module coverage suppresses module finding deltas. `excludedScopes` and `findingComparisonComplete` expose this. Graph changes remain raw observed differences with before/after status; no composite degradation score or causal claim is produced.

Function changes include added, removed and changed fingerprint identities. A bounded reverse breadth-first traversal finds endpoint handlers that can statically reach those functions, with one witness per endpoint per side. Removed functions are traced in the old graph; added functions in the new graph. Changes to resolved middleware supplied directly in route registration can therefore affect multiple endpoints. Global or mounted middleware is not automatically attached.

Fingerprint changes are not proof of changed behavior. Conversely, import rewiring, changed closed-over/top-level constants, environment or database changes can affect behavior without changing a function fingerprint and will not seed this traversal. Dynamic calls, callback execution, unresolved dispatch and omitted top-level calls are outside the resolved call graph. Function/file renames can appear as additions/removals. Reports say **potentially affected**, never confirmed breakage or test selection guarantees.

## Bounds, determinism and schema

Default options are degree threshold 6, chain length 3 nodes, 100 witnesses per graph/impact side, 30,000 combined module/function nodes, 150,000 records/edges per category, 5,000,000 HTTP endpoint comparisons, and 1,000,000 traversal steps per witness search or impact side. A second HTTP path-character budget is 128 times the comparison limit. Exceeding input limits fails closed. Exhausted search budgets produce explicit unknown/truncated results. Endpoint-impact witness paths are capped at 128 function identities; total observed affected endpoints and emitted witnesses are reported separately.

SCC traversal is iterative and linear in graph size apart from deterministic sorting. Chain depth is capped at 8 by option validation. Graphs and findings use sorted deterministic identities; duplicate dependency observations do not inflate degree metrics. The `sourceCoverage` array preserves numeric parser coverage counters without copying source bodies or arbitrary metadata.

Reports use `schemaVersion: 1`, `analysis: static-javascript-architecture`, and a capability list with `supported`, `partial`, `unsupported`, or `not-applicable` statuses and reasons. Deltas use `analysis: static-javascript-architecture-delta`. Older snapshots lacking `architecture` remain valid for HTTP analysis, but their module and function capabilities are unsupported, with null degree summaries rather than healthy zero values.

Synthetic tests cover cyclic/acyclic graphs, distinct-neighbor metrics, reciprocal backend links, frontend scope exclusion, threshold candidates, incomplete coverage, duplicate routes, unknown/external/case-only calls, bounded searches, historical graph changes and potential handler impact. These tests verify implementation behavior, not precision/recall on Nutrosal or any other industrial system.
