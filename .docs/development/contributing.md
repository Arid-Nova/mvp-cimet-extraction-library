Contributing
============

Workflow
--------
- Fork the repository, create a feature branch, run tests locally, and open a PR.
- Include a short description and link to related issues.

Developer checks
----------------
- Run `./mvnw -B test` and `./mvnw -B verify` when applicable.
- Run `shellcheck` on modified shell scripts (the devcontainer includes shellcheck extension and binary).
- Run `./mvnw -B -DskipTests=false verify` in CI or devcontainer to confirm integration tests when needed.

PR checklist
------------
- [ ] Tests added/updated and passing
- [ ] Code formatted (EditorConfig) and style checks pass
- [ ] Documentation updated in `.docs/` if behavior or public API changed
- [ ] PR description includes how to verify changes locally

Commit message guidance
-----------------------
- Use concise, imperative style: "Add X", "Fix Y".
- Group small changes into logical commits; avoid large monolithic commits.
