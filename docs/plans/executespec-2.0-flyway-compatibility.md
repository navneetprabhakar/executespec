# Flyway compatibility policy for ExecuteSpec 2.0

## Decision

Existing databases must upgrade from their current migration head without manual history edits. Applied `V*.sql` files are immutable. The current release continues from V256; a future schema cleanup may start at V257 or later.

The user-requested consolidation option is supported only for clean installs:

1. Generate and review a complete schema-only baseline from a known migration head.
2. Add a Flyway baseline migration (`B<baseline>__executespec_2_schema.sql`) containing the generated schema and required seed data.
3. Keep all post-baseline migrations (`V<baseline+1>__...`) as normal forward migrations.
4. Prove a clean install using the baseline and an upgrade from a database that has applied V256. Both must reach the same Hibernate `ddl-auto: validate` shape and application smoke checks.
5. Never delete, rename, edit, or checksum-repair an already-applied V-file as part of this work. Historical migrations remain available for old database histories and auditability.

No baseline migration is created until the schema has stopped moving for the release cut; generating one earlier would create a second source of truth while V2 work is still changing entities.

## V256 upgrade contract

V256 is a forward data migration only. It removes Sarvam from reusable provider/model state in `app_user`, `project`, `pipeline_run`, `execution_run`, `agent_session`, `agent_task`, and `org`. It deliberately does not rewrite immutable provider strings in usage, benchmark, or event history. The migration must be idempotent with respect to already-clean rows and must not drop tables or columns.

The `model_overrides_json` cleanup assumes the existing application contract: this TEXT column contains valid JSON objects. If production data violates that contract, the migration must be split into a quarantine/repair migration before the JSON rewrite; it must not silently discard malformed customer data.

## Required gates for every schema change

- Inventory test: unique versions, valid names, expected head, and source/classpath parity.
- Fresh compile/test with Flyway validation enabled.
- Integration test on a database upgraded from the previous head.
- Clean-install test from the current baseline (once a baseline exists).
- Hibernate `ddl-auto: validate` and representative API smoke.
- Explicit check that historical usage/benchmark rows remain unchanged.
- Documentation updates in root instructions, package instructions, CODEMAP, and reason log.

## Current state

| Path | Status |
|---|---|
| V1–V255 | Existing history; do not rewrite |
| V256 Sarvam state cleanup | Forward migration; source/classpath inventory expects it |
| Squashed clean-install baseline | Not yet generated; deferred until schema freeze |
| Upgrade integration test | Existing Testcontainers/Flyway suite; V256 data assertions still required |
