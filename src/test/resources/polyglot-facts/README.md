# Language visitor regression fixtures

`expected-facts.json` records the public `PolyglotExtractor.extract` facts produced
by the compiled pre-refactor revision `ea93f9ac31529ab1849898b31abf3714f03e19a6`.
The four authored source files exercise declarations, fields, qualified methods,
parameters, imports, routes, HTTP observations and gRPC patterns. The test also
reuses one facade across languages to detect per-file state leakage.

These are behavior-preservation fixtures, not manually labeled extraction truth
or a claim of complete language/framework support. For example, existing heuristic
unknown URLs and `ANY` methods are deliberately retained. A deliberate rule change
must review the affected facts rather than silently regenerate the baseline.

Parse timing is excluded because it is not deterministic. Fixtures are parsed as
source only; their imports and application functions are never executed.
