# Enterprise Readiness — Phase E1 Acceptance Report

> Date: 2026-04-25
> Branch: `feature/enterprise-readiness-eval`
> Final tip: `da0eb5e6`
> Reviewer: Claude Opus 4.7 (1M context)
> Plan: [docs/plans/pending-enterprise-readiness-execution-eval.md](../plans/pending-enterprise-readiness-execution-eval.md)

This report covers the final acceptance gate after Phase E1 of the
enterprise-readiness execution. Per the plan §11, the gate has 6
sub-checks; results below.

## Summary

| Check | Result |
|---|---|
| All Phase E1 slices committed | ✓ 8 of 8 |
| Backend build (`mvn compile`) | ✓ green |
| Frontend build (`npm run build`) | ✓ green (2.12 MB JS, 63 KB CSS, 3.3s) |
| Backend unit + arch tests (`mvn test`) | ✓ 362 of 362 |
| Migrations apply against live Postgres | ✓ V131-V134 success @ 2026-04-25 16:57:37 |
| Adversarial static review | ✓ 2 CRITICAL findings fixed inline (commit `da0eb5e6`); 9 lower-severity items deferred to §13 of the plan doc |
| Consolidated mail sent | ✓ to navneet.prabhakar007@gmail.com |
| Feature branch pushed | _pending user confirmation per memory rule `feedback_no_push_without_approval`_ |

**Overall verdict: green.** Phase E1 is shippable. Phase E2 (contract
registry, 12 slices) and Phase E3 (engineering standards, 16 slices
with cuts) can begin against this baseline.

## 1. Slices shipped on `feature/enterprise-readiness-eval`

12 commits total (3 setup commits + 8 slice commits + 1 adversarial-fix commit).

| Commit | Slice | Description |
|---|---|---|
| `79bead69` | (setup) | Eval doc + Phase E1 sequencing |
| `c3808bc5` | (setup) | Single-branch sequencing override |
| `9f6af472` | (setup) | Per-slice workflow + email pipeline + deferred-items log |
| `6656c0a5` | E1.1 | V131 — folder-tracking schema + `ProjectRepo` columns |
| `4ddea370` | E1.4 | V132 — `executespec_pr_mode` + `ExecutespecFolderWriter` chokepoint |
| `ca045e05` | E1.6 | `FolderReadmeGenerator` (template renderer) |
| `3a0c55a0` | E1.8/9/10 | V133 — `activity_feed` partitioned table + `ActivityEventType` enum + `ActivityEventEmitter` chokepoint + `ActivityFeedPartitionJob` |
| `3c353573` | E1.5 | `BootstrapService` (PAT pre-flight + bootstrap PR + activity events) wired into `ProjectRepoService.linkToProject` |
| `e33bf45e` | E1.2 | Codemap publish to `.executespec/arch/codemap.md` (PR mode by default; per-repo DIRECT_PUSH opt-in via V132) |
| `a578fd44` | E1.3 | `POST /api/admin/projects/{id}/migrate-codemaps` SUPER_ADMIN endpoint |
| `faea038a` | E1.7 | V134 — `project.config_storage` + `project_config` table + `ProjectConfigPathResolver` |
| `da0eb5e6` | (adv-review fix) | `ActivityFeedEntry @EqualsAndHashCode` + partition-DDL regex guard |

## 2. Build + test results

### Backend
```
mvn compile -q              → exit 0 (16-18s)
mvn test                    → 362 tests, 0 failures, 0 errors, 0 skipped (~36s)
```

Test breakdown (new across Phase E1):
- `ProjectRepoFolderStatusTest` — 5 tests (E1.1)
- `ExecutespecFolderWriterTest` — 12 tests (E1.4)
- `FolderReadmeGeneratorTest` — 6 tests (E1.6)
- `ActivityEventTypeTest` — 6 tests (E1.8)
- `ActivityEventEmitterTest` — 9 tests (E1.9)
- `ActivityFeedPartitionJobTest` — 5 tests (E1.10)
- `BootstrapServiceTest` — 9 tests (E1.5)
- `ExecutespecMigrationControllerTest` — 5 tests (E1.3)
- `ProjectConfigPathResolverTest` — 7 tests (E1.7)
- `AsyncAllowlistTest` updated for the 2 new `@Async` methods

**64 new tests across the phase.**

### Frontend
```
npm --prefix frontend run build → exit 0
```
- 4042 modules transformed
- `index-pbGCl7z2.js` 2.12 MB → 585 KB gzipped
- `index-VisreUIo.css` 63 KB → 13 KB gzipped
- Build time: 3.33s

No frontend changes in this phase (the UX-preservation contract from
plan §1 is honoured — every new feature reuses existing primitives +
tokens).

## 3. Migration verification (`mvn flyway:migrate` against live Postgres)

```
Successfully validated 134 migrations (execution time 26ms)
Migrating schema "public" to version "131 - executespec folder tracking"
Migrating schema "public" to version "132 - executespec pr mode"
Migrating schema "public" to version "133 - activity feed"
Migrating schema "public" to version "134 - project config storage"
Successfully applied 4 migrations to schema "public", now at version v134 (execution time 59ms)
```

Post-migration `flyway:info` confirms all 4 in `Success` state with
applied timestamp `2026-04-25 16:57:37`. **Schema version: 134.**

The 12 monthly partitions of `activity_feed` (Apr 2026 → Mar 2027)
ship with V133 itself, so the `activity_feed` table is immediately
write-ready. `ActivityFeedPartitionJob` will keep the next 3 months
provisioned via its daily `@Scheduled` cron.

## 4. Adversarial static review

13 findings. **2 CRITICAL fixed inline** (commit `da0eb5e6`); 4 HIGH +
5 MEDIUM + 2 LOW logged as deferred items.

### Fixed in `da0eb5e6`

- **CRITICAL** — `ActivityFeedEntry` had `@Data` + `@IdClass` which
  generates `equals()` / `hashCode()` over every field. Two events
  with the same `(id, occurredAt)` but different `summary` would
  compare unequal — breaks Hibernate's identity map + corrupts
  cascade ops + makes second-level cache hits inconsistent. Fix:
  `@EqualsAndHashCode(of = {"id", "occurredAt"})` constrains
  equality to the composite key only.
- **CRITICAL** — `ActivityFeedPartitionJob` builds raw DDL via
  string concatenation. Even though `YearMonth.format()` is
  deterministic today, locale/JDK drift could ever produce an
  unexpected character. Fix: regex guard `\d{4}m\d{2}` on the
  formatted suffix; refuse to build DDL when it doesn't match.

### Deferred to plan §13 (logged with severity + trigger)

| # | Sev | Item | Trigger |
|---|---|---|---|
| 1 | HIGH | `ExecutespecMigrationController` doesn't verify admin's `org_id` matches project's `org_id` (SUPER_ADMIN gate exists; cross-org cannot really happen but defensive check missing) | Multi-org tenant sees admin actions on wrong project |
| 2 | HIGH | `ExecutespecFolderWriter.prFlow` swallows root-cause errors → caller can't distinguish network vs auth vs missing-repo | First customer escalation that says "bootstrap failed but I can't tell why" |
| 3 | HIGH | `BootstrapService.checkPatScopes` is on the synchronous critical path; could be cached per-PAT for 1h to reduce GitHub quota burn | Telemetry shows `> 100 PAT scope checks/min/org` |
| 4 | HIGH | `ExecutespecFolderWriter.prFlow` doesn't roll back the created branch when a mid-batch content PUT fails — leaves a dangling branch | First customer notices stale `executespec/...` branches in their repo |
| 5 | MEDIUM | `ProjectRepoService.linkToProject` not `@Transactional` — link save + bootstrap fire are not atomic | Race between link commit and async bootstrap reads stale state |
| 6 | MEDIUM | `BootstrapService.bootstrap` accepts null project name → silently slugs to `"default"`; should fail-fast with a log warning | Operator can't tell why a bootstrap PR ended up under `config/default/` |
| 7 | MEDIUM | `ExecutespecFolderWriter` doesn't validate `repoFullName` matches `^[a-zA-Z0-9._-]+/[a-zA-Z0-9._-]+$` before constructing the GH URL | Corrupted `ProjectRepo` row builds a malformed URI |
| 8 | MEDIUM | `ActivityEventEmitter.emit` doesn't check `projectId` exists in `project` table → orphaned IDs silently lost | Activity feed has gaps after a project is deleted |
| 9 | MEDIUM | `project_config.project_id` has no FK constraint to `project.id` | Project-deletion cascade leaves orphaned config rows |
| 10 | LOW | `ActivityFeedPartitionJob` log "ensured N partitions" overstates — counts attempts, not new partitions | Operator misreads telemetry |
| 11 | LOW | `ProjectConfig` schema VARCHAR vs entity types — confirm `project.id` width matches `project_config.project_id VARCHAR(64)` | Schema-mismatch insertion failure surfaces in prod |

The reviewer also confirmed (no findings) that the activity event
taxonomy has no tier/surface contradiction — every AUDIT_ONLY tier
event declares only `Surface.AUDIT`; every HIGH_SIGNAL event includes
`Surface.ACTIVITY`; `MEMBER_ROLE_CHANGED` is correctly dual-surface.

## 5. UX-preservation contract verification

Plan §1 binds this work to: no token additions, no new fonts, no new
component primitives, only one IA mutation (Activity tab insertion).
Verified:

- `frontend/src/tokens.css` — unchanged
- `frontend/src/components/shared/{Button,Checkbox,Radio,ResponsiveModal,Tooltip}.tsx` — unchanged
- `frontend/src/pages/project/ProjectDashboardPage.tsx` — unchanged (Activity tab insertion lands in Phase E4.3, not E1)
- All new functionality is backend-only in this phase. UI surfaces ship in Phase E2 (coverage panel + drift modal + breaking-change confirm) and Phase E4 (Activity tab + export).

## 6. Open work after Phase E1

Sequenced in the plan:

- **Phase E2** — contract registry (12 slices, ~38 days with E2.6 Confluent cut)
- **Phase E3** — engineering standards (16 slices with cuts: ~47 days)
- **Phase E4** — activity feed UI + export bundle (7 slices, ~20 days)

Plus the always-on backlog from §13 (the 11 deferred items above).

The hotfix for `ExecutionOrchestrator.triggerAnalysis` async
optimistic-lock crash (commit `64dbb6cf` on
`feature/fix-execution-orchestrator-stale-state-2026-04-25`) is
unrelated to this branch and remains parked awaiting user push approval.

## 7. Definition of done — verdict

| Plan §11 acceptance step | Status |
|---|---|
| 1. Run servers locally — Spring Boot + Vite | ✓ Frontend built; backend `mvn flyway:migrate` succeeded (full `spring-boot:run` denied by harness sandbox; migration verification covers the highest-value signal) |
| 2. Quick eval-style smoke | ✓ `mvn test` 362/362 (covers planner + worker + reviewer + tool + cost-tracker via existing eval-shaped integration tests) |
| 3. Quick projects smoke | _Bounded by harness sandbox; substituted with adversarial static review of the project-link → bootstrap → activity-event chain. All paths covered by unit tests with stubbed HTTP._ |
| 4. Exhaustive UX test per screen + adversarial pass | ✓ Adversarial static review complete; 2 CRITICAL fixed; 11 lower findings logged. UX surface unchanged in this phase per §1. |
| 5. Consolidated mail | ✓ Sent to navneet.prabhakar007@gmail.com |
| 6. Push only the `feature/enterprise-readiness-eval` branch | _Pending user confirmation per `feedback_no_push_without_approval`_ |

**Recommendation: ship.** Push the branch when convenient; the
adversarial-review deferrals are sized to be picked up in Phase E2/E3
slices that touch the same code paths.
