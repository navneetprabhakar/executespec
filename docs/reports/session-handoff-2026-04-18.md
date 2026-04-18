# Session handoff — 2026-04-18

> Pick up the work in a fresh thread. Captures branch state, what shipped,
> what's open, and how to resume without re-deriving context.

## TL;DR

- Branch: **`docs/v2512-snapshot`** (this doc) — open as PR.
- 13 PRs landed yesterday + today (#21, #25, #26, #27, #28, #29, #30, #31,
  #32, #34, #35, #36, #37 — all merged to dev). Dev cycled to main twice.
- Backend: **`mvn package -q` green**, 214 unit tests pass.
- Frontend: **`npm run build` green**.
- Servers: **local backend + frontend running** (`:8080`, `:5173`); dev
  env **UP** at https://dev.executespec.ai.
- LLM spend in this session: **≈ $0.30** (3 generate calls + 2 analyse
  runs against the local app).

## Where we are

```bash
cd /Users/navneetprabhakar/Documents/antigravity/executespec
git status                # clean except docs/v2512-snapshot tip
git log --oneline -15     # last 15 commits below
git branch --show-current # docs/v2512-snapshot
```

```
e00a7a9e fix(test): tag ExecutespecApplicationTests as @integration (#37)
b46e67ef fix(ci): drop -DskipTests + fix health-check buffer-reuse bug (#36)
8b2263e9 fix(rbac+validation+v2): close 3 gaps from persona test pass (#35)
f93238f1 docs: personas + journeys + persona-driven test cases + 2026-04-18 analysis (#34)
900d2bef fix(codeintel): thread caller PAT through Staleness probe (#32)
fe9beca5 fix(codeintel): drop @Transactional from arch generate() + add tier mappings (#31)
a1c52852 feat(codeintel): 3-step architecture pipeline + real Sync (#30)
d86795cf fix(codeintel): V114 widen project_generated_doc.generated_by (#29)
d0b43e1c fix(codeintel): V113 project_id column type mismatch (#28)
80506602 feat(codeintel): project-level Generate Docs — Explanation + Setup (#27)
8b7b63a2 ci: restore deploy.yml on dev branch (dev-only trigger) (#26)
6ad5150d Merge pull request #25 from navneetprabhakar/feature/v2512-followups
3b7adfe6 fix(scoring): honest Test-coverage + verified-score consistency
```

## What shipped

### v2.5.12 sprint — three feature blocks + five gap fixes

**Block A — 3-step architecture pipeline (PR #30):**
1. Per-repo codemap, idempotent by SHA → reuse if `generatedFromSha`
   matches current origin HEAD.
2. Per-repo HLD+LLD architecture doc via new `RepoArchitectureGenerator`
   (LIGHTWEIGHT tier), idempotent by `sourceCodeMapId`.
3. Combined architecture doc via rewritten `ArchitectureDocGenerator`
   synthesised from per-repo docs (LLM-driven WORKHORSE) with
   deterministic fallback.

V115 adds `repo_architecture_doc` table. New endpoint
`GET /api/projects/{id}/repos/{repoId}/architecture`. Workspace layout
gains `repos/<short>/architecture.md` per repo. Verified locally:
re-Analyse with no upstream change drops from 55s to 27s and logs
`codemap up-to-date @ sha=…` for every repo.

**Block B — Generate Docs feature (PR #27):**
New `ProjectGeneratedDoc` table (V113-V114) for user-invoked EXPLANATION
+ SETUP docs. `ProjectDocGeneratorService` reads project + arch + per-
repo codemaps and produces 500–1200 word markdown via LIGHTWEIGHT LLM.
Three endpoints per type: `POST /generate`, `GET /` (204 if absent),
`GET /download` (text/markdown attachment). Frontend
`GeneratedDocsPanel` on Project Overview tab: Generate / Regenerate /
inline View / Download.

**Block C — Sync enhancement (PR #30):**
RepoCard Sync now does fresh-pull + per-repo regen, not metadata-only.
For every project this repo is linked to: refresh metadata
(`detected_stack` + `last_synced_sha`) + run Steps 1+2 of the arch
pipeline via `regenerateForRepo(projectId, repoId, pat)`. Step 3 still
on next Analyse to keep Sync cheap.

**Gap fixes from 2026-04-18 persona test pass (PR #35-37):**
- **#1** — `loadRepoForRead` now bypasses ownership check for SUPER_ADMIN
  via single `appUserRepository` lookup.
- **#2** — new `ValidationAdvice` `@RestControllerAdvice` translates Bean
  Validation 400s into JSON `{error,message,fields:{field:msg}}`.
- **#3** — CI dropped `-DskipTests`; surefire excludes
  `e2e,integration,chaos`; `ExecutespecApplicationTests` tagged
  `integration` (full-context smoke needs DB).
- **#4** — health-check curl wrapper rewritten: `rm` stale body file
  before each curl, gate on `http=200` AND body containing UP/DEGRADED.
  Closes the false-positive that hid yesterday's V113 boot-crash for an
  hour.
- **#5** — `V2RunOrchestrator` background staleness probe loads run-owner
  PAT from `appUserRepository` and threads it through.

**Other fixes that landed:**
- V112 `@Version` on `ExecutionRun` + `OptimisticLockAdvice` (Bug #3
  root cause)
- `RunTokenAttributor` strangler extraction (~90 LOC out of
  `V2RunOrchestrator`)
- 9 modal migrations onto new `ResponsiveModal` + `Button` + `Checkbox` +
  `Radio` primitives
- `EstimateChip` shared between New Run + ExecutionSetup
- Honest scoring fixes (testCoverage = null on doc-only runs;
  `RunDetail` banner + `ScoreCard` header prefer `verifiedScore`)
- ArchUnit `AsyncAllowlistTest` guards the 3a17e9aa @Async + lazy-JPA
  regression class
- New memory rules: `feedback_test_locally_first.md`,
  `feedback_ff_only_pull.md`

## Reports written this session

| File | Lines | What |
|---|---:|---|
| [`docs/test-scenarios/personas-journeys-and-cases.md`](../test-scenarios/personas-journeys-and-cases.md) | 230 | 6 personas, 8 journeys, ~55 test cases, smoke pack |
| [`docs/test-scenarios/persona-test-analysis-2026-04-18.md`](../test-scenarios/persona-test-analysis-2026-04-18.md) | 320 | Test-pass report, system rating 7.4/10, 10 gaps |
| `docs/reports/session-handoff-2026-04-18.md` | (this) | Handoff |

## What's still open

### Backend

1. **Bug #1c — `ModelResolver.selectAdaptiveProvider` bypasses
   strict-provider** (v2.5.9 finding, still not fixed). Add either a
   strict-mode guard inside ModelResolver or an opt-out flag per org.
2. **Planner model sourced from run model, not tier** —
   `PlannerAgentV2.plannerViaLlm` uses `req.model()` not
   `ModelResolver.resolve("PlannerAgentV2", provider).getModel()`.
3. **V2 batch not implemented** — scoped in
   `docs/plans/v2-batch-execution-plan.md`.
4. **V2 startup replay (WORKING/REVIEWING)** — boot recovery currently
   marks them FAILED; full replay is planned (`docs/plans/harness-v2-remaining-work.md`
   Gap 2).
5. **Validation v3 Pillars 2-5** — evidence-based reviewer, sync test
   gate, CLI+LIBRARY test coverage, strict score formula + UI. Plan:
   `.claude/plans/immutable-painting-platypus.md`.

### Gaps from 2026-04-18 analysis still open

The five fixed in this session were #1, #2, #3, #4, #5. The remaining
five from `persona-test-analysis-2026-04-18.md`:

- **#6 (🟡)** — SUPER_ADMIN cannot impersonate a user. Open ticket for an
  admin-only `POST /api/admin/impersonate/{userId}` with audit trail.
- **#7 (🟢)** — `GeneratedDocsPanel.DocRow` doesn't surface token cost.
  Add a chip next to the model + version chips.
- **#8 (🟢)** — Idempotency log line could include the previous SHA when
  regenerating ("regenerated; previous sha was abc12345").
- **#9 (🟡)** — Local profile silently degrades GCP-dependent jobs.
  Detect missing ADC at boot, disable cleanly with a single startup
  WARN.
- **#10 (🟢)** — `document_map.md` updated this session ✓ (closed).

### Eval (when you want to spend money)

- **Strict eval scenarios S3-S5** — the harness is ready
  (`scripts/v2-eval-strict.sh`) but no live run yet. ≈ $12–15 for the
  full 9-run matrix.

## Known good local state

- `application-local.yml` (gitignored) has working API keys for OpenAI /
  Anthropic / Gemini. Boot with
  `--spring.profiles.active=local`.
- Postgres.app 18 on `:5432`; Redis on `:6379`; both assumed running.
- Admin user: `admin@executespec.ai` / `REDACTED_FOR_PUBLIC_RELEASE` (SUPER_ADMIN,
  ENTERPRISE).
- 115 Flyway migrations apply cleanly on boot. Last applied this
  session: V115.
- Feature flags in dev DB: `scoring.v2-verified=true` (V110),
  `validation.v3-strict=false` (V109 default).

## How to resume in a fresh thread

```bash
# Catch up on context (in this order):
docs/test-scenarios/personas-journeys-and-cases.md      # the personas + journeys
docs/test-scenarios/persona-test-analysis-2026-04-18.md # what's been tested + gaps
docs/reports/session-handoff-2026-04-18.md              # this file
CLAUDE.md                                               # latest project state (updated today)

# Bring servers back up:
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn spring-boot:run \
  -Dspring-boot.run.arguments=--spring.profiles.active=local
npm --prefix frontend run dev   # in another terminal

# Login:
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@executespec.ai","password":"REDACTED_FOR_PUBLIC_RELEASE"}'

# Strict-provider mode for eval reproducibility:
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-Dexecutespec.strict-provider=true"
```

## Decisions outstanding

User mentioned having "a few ideas to check" at the end of the session.
Pending those, sensible next sprints:

- **Strict eval matrix** — verify v2.5.12 didn't regress anything
  measurable (~$12 spend; produces a comparable report to
  `eval-results.md`).
- **Validation v3 Pillar 2** — evidence-based reviewer (replaces the
  reviewer's "looks fine" output with cited evidence).
- **Close gap #6** — admin impersonation (unblocks QA + customer support
  workflows).
- **Production deploy story** — `prod` GCP env still unset; provision
  credentials and re-add `main` to the `Build & Deploy` trigger
  branches.

## Branch + push state

- Local branch: `docs/v2512-snapshot` (this file).
- Remote: about to be pushed; PR will follow.
- All other v2.5.12 PRs are merged.
- No outstanding rebases.

## Budget reminder

Cumulative LLM spend across yesterday + today (audits, fixes, persona
testing, generate-docs end-to-end): **≈ $1.80** of $25 cap. Most of it
went to Generate Docs + Analyse runs against the local Hostel project.
Strict eval matrix would push spend toward $15.
