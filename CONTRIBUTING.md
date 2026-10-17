# Contributing

ExecuteSpec is an experimental codebase. Prefer focused changes with a concrete behavior and clear
verification over broad refactors or new orchestration layers.

Read [AGENTS.md](AGENTS.md), the affected package notes, and [README.md](README.md) first. Open an
issue for a substantial architecture or scope change. Report vulnerabilities privately according to
[SECURITY.md](SECURITY.md), rather than in a public issue or pull request.

## Workflow

1. Branch from the maintained development branch, `dev`.
2. Preserve approval, tenant, spend, workspace, and publication boundaries.
3. Update the relevant documentation with the code.
4. Run backend `mvn verify`, frontend tests/build, and `python3 scripts/check-public-release.py`.
5. Include the problem, resulting behavior, checks run, and material unverified cases in the PR.

`mvn verify` excludes `e2e`, `integration`, and `chaos` tests by default. Changes to database
transactions, leases, crash recovery, or accepted artifacts need the relevant real PostgreSQL/
container tests, normally through `mvn -Pintegration test` with a focused selection.
Live LLM tests require explicit provider-spend authorization and their own opt-in. Do not substitute
mocked tests for evidence at a boundary whose actual composition changed.

The source of public assets is `frontend/public/`. Run the Vite build to regenerate backend-served
assets. Do not commit `src/main/resources/static/`, captures, workspace snapshots, Terraform plans,
or local environment files.

Contributions are provided under [Apache License 2.0](LICENSE). Third-party code and assets must
retain their own required attribution and compatible licensing.
