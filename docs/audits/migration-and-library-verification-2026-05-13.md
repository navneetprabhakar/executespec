# Migration + Library Verification Audit — 2026-05-13

> PR-J. Author: ExecuteSpec team. Status: one-time audit + template for the next sweep.

## Scope

End-of-branch sanity check on the 11-commit `fix/project-a092ffaa-permanent-fixes-2026-05-13`
branch. Confirms that:

1. Every Flyway migration added on this branch (V189–V193) has an entity counterpart in
   `src/main/java/com/navneet/executespec/**/entity/`
2. Each migration is documented in the matching package's `CLAUDE.md` migration log
3. Sequential numbering is intact (modulo the documented V119 skip — see below)
4. No new `pom.xml` dependencies were silently introduced

This audit is operational, not aspirational: the contract is "what's actually in the tree",
not "what we want to be in the tree." Anything found amiss gets fixed in this PR or filed as
a follow-up.

## Migration head

```
src/main/resources/db/migration/ — head: V193 (5 new on this branch)
  V189__execution_run_cost_backfill.sql           — PR-A
  V190__agent_task_failure_diagnostic.sql         — PR-A
  V191__project_archive_delete_timestamps.sql     — PR-B-part-2
  V192__arch_doc_generated_from_branches.sql      — PR-M
  V193__execution_run_failure_reason.sql          — PR-I
```

193 files / head V193 → exact match (the only `V*_1` flavour file is `V60_1`, which Flyway
treats as a sub-version of V60). Sequence audit:

```
gaps found:
  V118 → V120 — KNOWN: V119 was an orphan from the preview-engine repo split (renamed → V120
                       to dodge a collision; documented in application.yml lines 85-114)
```

No unexpected gaps.

## Migration → entity coupling

| Migration | DDL touched          | Entity field                                                                         | Verified |
| --------- | -------------------- | ------------------------------------------------------------------------------------ | -------- |
| V189      | `execution_run` (UPDATE backfill only — no column added) | n/a (pure data fix)                                              | ✓        |
| V190      | `agent_task.failure_diagnostic_json TEXT`        | `AgentTask.failureDiagnosticJson`                                        | ✓        |
| V191      | `project.{archived_at, deleted_at}` + indexes    | `Project.archivedAt`, `Project.deletedAt`                                | ✓        |
| V192      | `project_architecture_doc.generated_from_branches jsonb` | `ProjectArchitectureDoc.generatedFromBranches`                   | ✓        |
| V193      | `execution_run.failure_reason TEXT`              | `ExecutionRun.failureReason`                                             | ✓        |

Spot-checked via `grep -l <field> src/main/java/.../entity/*.java`; all 5 land in the entity
the migration's `ALTER TABLE` targeted. No drift.

## Migration log coverage

| Migration | Logged in                  | Notes                                                                |
| --------- | -------------------------- | -------------------------------------------------------------------- |
| V189      | `state/CLAUDE.md`          | ✓                                                                    |
| V190      | `state/CLAUDE.md`          | ✓                                                                    |
| V191      | `state/CLAUDE.md`          | ✓                                                                    |
| V192      | `state/CLAUDE.md`          | ✓ (added in PR-J)                                                    |
| V193      | `state/CLAUDE.md`          | ✓ (added in PR-J)                                                    |

Root `CLAUDE.md` migration head: bumped V186 → V193 across the three call sites (Version
header, Code Conventions DB section, Key file locations table). Cross-checked.

## Repository-method audit (added this branch)

| Method                                                    | Repository              | Live caller                                           |
| --------------------------------------------------------- | ----------------------- | ----------------------------------------------------- |
| `findByOrgIdAndDeletedAtIsNull(orgId)`                    | ProjectRepository       | ProjectService.listProjectsForUser (PR-B-2)           |
| `countByOrgIdAndStatusAndDeletedAtIsNull(orgId, status)`  | ProjectRepository       | TierEnforcementService.assertCanCreateProject (PR-B-2)|
| `findByDeletedAtBefore(cutoff)`                           | ProjectRepository       | DataRetentionService.hardDeleteTombstonedProjects (PR-H)|
| `findPipelineRunIdsByExecutionRunId(execRunId)`           | ExecutionSolutionRunRepository | ExecutionCostAggregator (PR-A)                |
| `aggregateCostAndTokens(runIds)`                          | PipelineRunRepository   | ExecutionCostAggregator (PR-A)                        |

All new repository methods have a live caller in production code. No dead-Repository drift.

## SSE event audit

| Event type    | Defined in        | Emitted by                                | Consumed by                       |
| ------------- | ----------------- | ----------------------------------------- | --------------------------------- |
| `FILE_WRITTEN`| RunEvent.fileWritten (PR-C) | WriteFileTool, EditFileTool   | useSse hook → useSseStore tick    |

New event has a producer and a consumer; not orphaned.

## Application event audit

| Event type                       | Listener                                   | Allowlisted? |
| -------------------------------- | ------------------------------------------ | ------------ |
| PipelineEvent.RunCompleted       | RunCompletionEmailListener.onRunCompleted  | ✓ (PR-O)     |
| PipelineEvent.RunFailed          | RunCompletionEmailListener.onRunFailed     | ✓ (PR-O)     |

AsyncAllowlistTest updated in PR-O so the two new @Async@EventListener methods are explicitly
approved.

## pom.xml audit

```bash
git diff c81f1581..HEAD -- pom.xml
```

No changes to `pom.xml` on this branch. All 11 PRs implemented entirely against existing
dependencies. No new transitive dependency churn.

## Frontend `package.json` audit

```bash
git diff c81f1581..HEAD -- frontend/package.json
```

No changes. Same story — pure code + types delta, no new npm deps.

## Tier-enforcement integration

Before this branch, `TierEnforcementService.assertCanCreateProject` counted soft-deleted
projects against the tier cap (via `countByOrgIdAndStatus`). Post-PR-B-2 it correctly uses
`countByOrgIdAndStatusAndDeletedAtIsNull` so the 30-day undo window doesn't double-charge.

Spot-checked test: `TierEnforcementServiceProjectLimitsTest` mocks the new method on all 4
test paths; suite still 1194/1194 green.

## ArchUnit guard status

| Guard test                              | Status | Drift                            |
| --------------------------------------- | ------ | -------------------------------- |
| AccessGuardCoverageTest                 | green  | None (PR-B-2 `/restore` added correctly via assertCanRead) |
| AsyncAllowlistTest                      | green  | 3 new entries approved in PR-O   |
| ApiContractTest                         | green  | No drift                         |
| KNOWN_LEGACY_DRIFT (P4.1-followup)      | 10     | Unchanged                        |

## Action items left over

None blocking. Future-audit hygiene:

- The arch generator's combined-arch LLM path could benefit from a structural test that
  exercises the PR-K fingerprint cache hit path against a real ProjectArchitectureService
  (currently only the static `serialiseFingerprint` is unit-tested).
- The PR-H project sweeper has no test (DataRetentionService.hardDeleteTombstonedProjects).
  Same shape as the existing GDPR test would do — add when DataRetentionService gets its next
  feature.

## Methodology (for the next audit)

```bash
# 1. Migration sequence
ls src/main/resources/db/migration | grep -oE "V[0-9]+" | sed 's/V//' | sort -n \
  | awk 'BEGIN{prev=0} {if ($1 != prev+1) printf "GAP: V%d → V%d\n", prev, $1; prev=$1}'

# 2. Migration ↔ entity coupling (per migration)
grep -l "<entity-field-camelCase>" src/main/java/com/navneet/executespec/**/entity/*.java

# 3. Migration log coverage
grep -n "V<N>" src/main/java/com/navneet/executespec/**/CLAUDE.md

# 4. pom.xml delta
git diff <last-merge-base>..HEAD -- pom.xml
git diff <last-merge-base>..HEAD -- frontend/package.json

# 5. ArchUnit guards
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn test -Dtest=AccessGuardCoverageTest,AsyncAllowlistTest,ApiContractTest -q
```
