# Flyway compatibility policy for ExecuteSpec 2.0

## Decision

Existing databases must upgrade from their current migration head without manual history edits. Applied `V*.sql` files are immutable. The current release ends at V272; any future schema cleanup may start at V273 or later. The historical V119 source is intentionally present in the release classpath because dev and older installations may already record it as applied.

The default runtime does not suppress missing applied migrations. A package with an incomplete
`classpath:db/migration` chain must fail Flyway validation rather than silently starting against an
unverifiable schema. Any local recovery workflow that needs to inspect a partial database must be
explicitly profile-scoped and must never be enabled in production.

The user-requested consolidation option is supported only for clean installs:

1. Generate and review a complete schema-only baseline from a known migration head.
2. Add a Flyway baseline migration (`B<baseline>__executespec_2_schema.sql`) containing the generated schema and required seed data in a separately configured clean-install location/profile; do not place it in the ordinary upgrade path by default.
3. Keep all post-baseline migrations (`V<baseline+1>__...`) as normal forward migrations.
4. Prove a clean install using the baseline and an upgrade from a database that has applied V255 (the compatibility contract remains explicit for that historical head), then validate the current V255→V272 forward step. Both must reach the same Hibernate `ddl-auto: validate` shape and application smoke checks.
5. Never delete, rename, edit, or checksum-repair an already-applied V-file as part of this work. Historical migrations remain available for old database histories and auditability.

No baseline migration is created until the schema has stopped moving for the release cut; generating one earlier would create a second source of truth while V2 work is still changing entities.

## V256–V272 upgrade contract

V256 is a forward data migration only. It removes Sarvam from reusable provider/model state in `app_user`, `project`, `pipeline_run`, `execution_run`, `agent_session`, `agent_task`, and `org`. V257–V268 add the coordinator-subagent, task-cost, auto-verification, routing, local-execution, replay, fail-closed cleanup, local-hands, provider-quality, and final provider-removal safeguards. V269 adds replay cursor hardening; V270 persists validation boot evidence; V271 seeds capability-profile controls; V272 deduplicates agent action results. These migrations deliberately do not rewrite immutable provider strings in usage, benchmark, or event history. All are idempotent and preserve existing rows except malformed executable overrides, which are cleared safely.

The `model_overrides_json` cleanup treats this legacy TEXT column defensively: valid JSON objects are filtered, while V263 clears malformed values containing the retired provider so executable configuration cannot retain a removed route.

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
| V1–V272 | Existing history; do not rewrite or rename; V119 is retained in source for applied-history validation |
| V256–V272 cleanup, flags, execution intent, replay cursor, provider safeguards, boot evidence, capability profiles, and action-result dedupe | Forward migrations; source/classpath inventory expects the complete chain |
| Squashed clean-install baseline | Not yet generated; deferred until schema freeze; separate clean-install profile only, never a replacement for the immutable V1–V262 history |
| Upgrade integration test | Testcontainers/Flyway suite proves V255→V272 (17 forward migrations), idempotent rerun, and historical data preservation |
