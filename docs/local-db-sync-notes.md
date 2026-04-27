# Local DB sync — Wave 3 closeout state

> Captured 2026-04-27 against `localhost:5432/executespec` (postgres / postgres).

## Current local state

* `flyway_schema_history`: **151 rows, 0 failures.**
* Highest applied version: **V151** (`activity_audit_chain_widen_to_varchar`, applied 2026-04-27 11:34).
* No `V119` orphan present (the application.yml `ignore-migration-patterns: "*:missing"` flag is defensive cover for that case but isn't currently exercised).
* `app_user` columns expected by V152 (`lifetime_run_credit_balance`, `lifetime_run_credit_cap`, `referred_by_user_id`) are **not yet present** — confirmed via `information_schema.columns`.

## Branch-vs-DB drift

| Side | Highest migration |
|---|---|
| `origin/dev` | V151 |
| `origin/feature/conversion-redesign` | **V152** (this branch's only addition) |
| Local DB | V151 |

V152 is the **next free slot** relative to dev. No collision; sequence aligned.

## Recommended path (non-destructive)

Boot the backend off this branch:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 \
  mvn -f $(git rev-parse --show-toplevel)/pom.xml spring-boot:run \
  -Dspring-boot.run.arguments=--spring.profiles.active=local
```

Flyway will:

1. Read `flyway_schema_history` and see the highest applied is V151.
2. Find `V152__lifetime_run_credit_balance.sql` on the classpath.
3. Apply it (3 `ALTER TABLE app_user` statements + a partial index, all additive with defaults).
4. Insert the V152 row into `flyway_schema_history`.

**Total downtime: ~50 ms.** No data loss. All 41 existing runs + 3 projects + every audit row stay intact. The freshly-created columns get their defaults (`lifetime_run_credit_balance = 5`, `lifetime_run_credit_cap = 25`, `referred_by_user_id = NULL`).

## Drop-and-recreate path (only if you want a clean integration-test starting point)

```bash
PSQL=/Applications/Postgres.app/Contents/Versions/18/bin/psql
PGPASSWORD=postgres $PSQL -h localhost -U postgres -d postgres -c "DROP DATABASE executespec;"
PGPASSWORD=postgres $PSQL -h localhost -U postgres -d postgres -c "CREATE DATABASE executespec;"
# then boot the backend; Flyway applies V1 through V152 from scratch.
```

**Cost:** wipes 41 runs, 3 projects, every audit-log row, billing history, sessions, ALL local data. Only do this if you're starting clean for a v3 integration test and don't care about the strict-review-era data we've been screenshotting against.

## Why I did NOT apply V152 manually right now

Manually running the SQL bypasses Flyway's `flyway_schema_history` insert — on the next backend boot, Flyway would try to apply V152 again and conflict with the existing columns. The safe path is to let the backend's own Flyway runner do it.

(If you wanted to short-circuit anyway, the right pattern is `flyway repair` after manually inserting a row into `flyway_schema_history` — non-trivial and easy to get wrong. Boot-the-backend is the one-step option.)

## Sequence forecast

When `feature/conversion-redesign` merges to `dev`:
* dev's Flyway-history goes V151 → V152 cleanly (no conflict; nothing else queued).
* Subsequent dev migrations slot in at V153, V154, etc.

If dev gains a NEW migration before this branch merges (i.e. someone else slots a V152 ahead of mine), I'll need to:
1. Pull dev's V152 in
2. Renumber my V152 → next free slot (V153 or whatever)
3. Update the `@Column` references in `AppUser.java` (none — the columns are file-name-driven, not version-number-driven, so the rename is purely a `git mv`)

Today: dev has not added a V152, so no rename needed.
