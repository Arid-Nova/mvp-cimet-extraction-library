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

Each graph includes source-linked nodes and edges, per-node degrees, aggregate metrics, strongly connected components, cycle witnesses, and bounded simple-chain witnesses. Multiple source observations for one directed pair become one edge with an `observations` count. Degree metrics use distinct neighbors; the additional weighted metrics use this static count. Neither interpretation measures traffic or execution frequency.

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
| SC, LWF, GWF | Implemented | Original numeric formulas over explicit static observation weights; module-import and HTTP-call scopes remain separate. |
| SCCmodularity | Implemented | Original JGraphT 1.5.2 SCC partition and undirected modularity implementation, with the same singleton-partition convention. |
| SIDC, LOMLC | Implemented with contract coverage | Complete-source-contract values, available-evidence estimates, and field/shape proxies are separate. Missing evidence produces null strict values. Coarse JavaScript type tags are not nominal Java DTO equivalence. |
| SSIC | Implemented as a partial local-function observation | Uses shared reachable implementation sets across endpoint registrations. This replaces the legacy runner's annotation proxy; dynamic and external behavior remains incomplete. |
| WrongCuts, health checks, API gateway, wobbly interactions, AR7 | Unsupported | Required domain/service/repository roles, contracts, framework configuration or data-access semantics are absent. Applying old Spring-specific tests would be misleading. |

## Conservative HTTP observations

Calls targeting `external` remain external. Unknown targets, unknown HTTP methods, unresolved URLs, and wholly dynamic paths such as `/{param}` remain unknown. Literal paths and whole-segment `{param}` paths are compared segment by segment. Express `ALL` and GET candidates for HEAD are considered, but overlapping registrations remain ambiguous; routing order is not simulated. Duplicate registrations are not silently collapsed to make an ambiguous result appear unique.

Matching defaults to case-sensitive. An explicit `architecture.caseSensitiveRouting: false` enables case-insensitive matching. With no routing evidence, otherwise compatible case-only alternatives are reported separately and never create graph edges. This represents uncertainty about routing configuration, not a confirmed broken call. Slashes and path structure are not changed to guess application routing.

Embedded placeholders such as `/reports/{param}.pdf` are currently unsupported by the matcher. Their coarse segment candidates are retained as unknown; they do not create accepted edges or an unmatched-call finding. Unsupported endpoint templates are not labeled unused. Origin routing, API gateway rewrites, payload schemas, authentication, authorization, middleware order and runtime dispatch are outside these matches.

AR4-style `no-observed-caller` means no caller candidate within the supplied snapshots. External customers, mobile clients, dynamic calls, and unextracted code may still use the endpoint. A backend analyzed alone is an incomplete client universe. Source coverage and findings must be interpreted together.

## Weighted structural coupling and modularity

For an existing ordered pair A -> B, let `out` be its static observation count, `in` the reverse pair's count (zero if absent), `d = out + in`, and `D` the largest such pair degree in that graph:

- `LWF = (1 + out) / (1 + d)`.
- `GWF = d / D`.
- `SC = 1 - 1 / d - LWF * GWF`.

The report contains maximum, mean and population standard deviation for each metric. Each observed ordered pair contributes once; reciprocal directions contribute separately. Changing the graph's maximum degree can change GWF/SC elsewhere, so a trend is not solely a change in the measured pair.

SCCmodularity uses JGraphT 1.5.2 `KosarajuStrongConnectivityInspector`, `AsUndirectedGraph`, and `UndirectedModularityMeasurer`, as in the inspected CIMET2 implementation. An all-singleton SCC partition returns zero by the legacy convention. Other values can be negative. SC can also be negative; results are never clamped into an invented quality-score range.

The weighted projection excludes self loops, while the enclosing dependency graph retains and reports them. Empty projections, inapplicable service graphs, missing module evidence and exhausted HTTP searches produce null weighted values with reasons. `graphs.<scope>.weightedMetrics` records definitions, projection counts and availability. Static import observations and method/path candidate calls are different weights; their module/application results are not interchangeable with validated inter-service runtime measurements.

## Contract and implementation cohesion

New parser snapshots optionally include `architecture.contracts`, with one record per endpoint. Each record contains observed request fields, coarse type evidence, response shapes, completeness flags, fixed limitation codes and source locations. Raw request/response values are not emitted. Request/property reads and coercions are observations, not proof of an exhaustive accepted-input schema. Opaque, dynamic or unsupported values remain unknown. The parser operates only on source ASTs and never executes the application.

`cohesion.components` groups operations by **declared backend application**. Frontends are not operation groups. Duplicate route registrations remain distinct operations; no endpoint is promoted into a service. Reports retain per-endpoint coverage records so eligible subsets can be audited.

| Output | Definition and interpretation |
|---|---|
| `SIDC.value` | Distinct known input types shared by at least two operations divided by the union of input types. Requires complete input evidence for every operation. |
| `LOMLC.value` | Mean over unordered operation pairs of `1 - (input Jaccard similarity + output type identity) / 2`. Requires complete input/output evidence for every operation. Response alternatives use a sorted type-set identity. |
| `availableEvidenceEstimate` | The corresponding formula on operations with usable observed evidence; includes eligible endpoint IDs/counts and the whole-component denominator for coverage. This is not a complete application score. |
| `observedFieldShapeProxy` | Separately named adaptations comparing location-qualified request-field identities and canonical response shapes. These are not the original typed metrics. |
| `SSIC.value` | `2 * sharedDistinctReachableFunctions / (distinctReachableFunctions * fullOperationCount)`, including all directly bound handlers and middleware. Always qualified as partial static local-function evidence. |

The SSIC equation preserves the **executable** legacy formula, which deduplicates shared elements, rather than its older prose description. Its upper bound is `2 / operationCount`; changing the number of operations affects its scale. Inspect the shared-element fraction and operation count alongside a historical SSIC change. The older Java runner fills implementation elements from annotations and replaces its operation list inside the controller loop; this extension uses all extracted endpoint registrations and resolved function reachability instead, with the difference stated explicitly.

Fewer than two operations, unknown contract data, empty undefined denominators and exhausted work budgets produce null scores. Unknown responses do not become a common `UNDEFINED` response type. Known coarse tags (string, number, boolean, array, object, null) can be less discriminating than domain-specific DTO types; a high score alone does not establish cohesive business responsibilities.

Cohesion analysis defaults to at most 10,000 operations, 1,000,000 reachability steps and 1,000,000 distinct-signature comparisons. A second comparison budget limits estimated character work to 128 times the pair limit. SIDC/SSIC use element frequencies rather than materializing operation pairs. LOMLC groups identical signatures and accounts for their multiplicity. Bound exhaustion is explicit and does not emit scores from truncated subsets. The [adapter contract documentation](../adapter/javascript/README.md) describes extraction limits and the conservative completeness guards for mutable values, opaque calls and possible inherited middleware.

## History and potential endpoint impact

`compare` uses stable node, edge and finding identities, excluding line numbers, source ordering and commits. It reports introduced/resolved/persisting observations, graph additions/removals, and numeric metric differences. Different names, conflicting repository identities, changed analysis options, duplicate/dangling records, or changed architecture availability for a shared component are rejected. Missing architecture evidence is not treated as deleted code.

Weighted observation-count changes are included in `edgeWeightsChanged`, even when the directed pair's identity persists. Cohesion differences retain before/after status, coverage, operation counts, estimates and proxies. Missing strict scores have null deltas. Estimates with different eligible populations must not be interpreted as causal improvement/degradation. `limits.metricDefinitionsVersion` and the cohesion limits prevent comparisons across incompatible measurement definitions.

The comparison excludes sampled chain witnesses from finding counts. Incomplete HTTP searches suppress HTTP/component finding deltas; missing module coverage suppresses module finding deltas. `excludedScopes` and `findingComparisonComplete` expose this. Graph changes remain raw observed differences with before/after status; no composite degradation score or causal claim is produced.

Function changes include added, removed and changed fingerprint identities. A bounded reverse breadth-first traversal finds endpoint handlers that can statically reach those functions, with one witness per endpoint per side. Removed functions are traced in the old graph; added functions in the new graph. Changes to resolved middleware supplied directly in route registration can therefore affect multiple endpoints. Global or mounted middleware is not automatically attached.

Fingerprint changes are not proof of changed behavior. Conversely, import rewiring, changed closed-over/top-level constants, environment or database changes can affect behavior without changing a function fingerprint and will not seed this traversal. Dynamic calls, callback execution, unresolved dispatch and omitted top-level calls are outside the resolved call graph. Function/file renames can appear as additions/removals. Reports say **potentially affected**, never confirmed breakage or test selection guarantees.

## Bounds, determinism and schema

Default options are degree threshold 6, chain length 3 nodes, 100 witnesses per graph/impact side, 30,000 combined module/function nodes, 150,000 records/edges per category, 5,000,000 HTTP endpoint comparisons, and 1,000,000 traversal steps per witness search or impact side. A second HTTP path-character budget is 128 times the comparison limit. Exceeding input limits fails closed. Exhausted search budgets produce explicit unknown/truncated results. Endpoint-impact witness paths are capped at 128 function identities; total observed affected endpoints and emitted witnesses are reported separately.

SCC traversal is iterative and linear in graph size apart from deterministic sorting. Chain depth is capped at 8 by option validation. Graphs and findings use sorted deterministic identities; duplicate dependency observations do not inflate degree metrics. The `sourceCoverage` array preserves numeric parser coverage counters without copying source bodies or arbitrary metadata.

Reports use `schemaVersion: 1`, `analysis: static-javascript-architecture`, and a capability list with `supported`, `partial`, `unsupported`, or `not-applicable` statuses and reasons. Deltas use `analysis: static-javascript-architecture-delta`. Older snapshots lacking `architecture` remain valid for HTTP analysis, but their module and function capabilities are unsupported, with null degree summaries rather than healthy zero values.

Synthetic tests cover cyclic/acyclic graphs, distinct-neighbor metrics, reciprocal backend links, frontend scope exclusion, threshold candidates, incomplete coverage, duplicate routes, unknown/external/case-only calls, bounded searches, historical graph changes and potential handler impact. These tests verify implementation behavior, not precision/recall on Nutrosal or any other industrial system.
