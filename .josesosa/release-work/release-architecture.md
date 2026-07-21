Decision: Convert repository to canonical Maven multi-module layout (Option B)

Plan summary:
- Root POM will become an aggregator (packaging = pom) that centralizes dependencyManagement and pluginManagement.
- Create `.release/core/` module to represent the library (module POM here). Sources will remain at repository root (no file moves).
- Create `.release/distribution/` modules for distribution targets (initial: `.release/distribution/jitpack`, `.release/distribution/github-packages`).
- Move sources into `core/` in an atomic follow-up change; initial PR will create the module structure so CI continues to run.

Rationale:
- Aligns with Maven best practices, CI/IDE expectations, and makes it straightforward to add additional distribution modules in future.

Signed-off-by: OpenCode (automated change)
