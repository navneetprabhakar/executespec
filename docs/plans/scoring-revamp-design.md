# Scoring revamp — context-driven, goal-graded reporting

> Status: **design draft for discussion** (no code yet).
> Author: 2026-05-13 audit, run 6024f510 + user feedback "I don't trust the scoring."

## Why the current scoring isn't trusted

`verifiedScore = clamp(0..100, baseAccuracy × buildOK × testRatio × reviewerMultiplier − criticalPenalty)`

Five problems:

1. **One scale for every spec.** A 6-file `black` formatting task and a Spring Boot library API get scored on the same 0-100 axis. The number means different things in each context.
2. **Score measures effort, not outcome.** A run that wrote 6 files + opened a PR gets 43 even when the PR is functional, because the LLM-judged `accuracyScore` (62) feeds the formula. Customer sees 43 and doesn't trust it.
3. **Validation report is an LLM oracle.** It scores against the *workspace*, not against *whether the user's goal landed*. So if the auto-push opened a PR that passes CI, the report doesn't know — it scored on what's in the workspace before push.
4. **No closed-loop signal.** PR merged in 2 hours? CI green on first push? No follow-up PR within 7 days? None of this feeds back into the score.
5. **Single number compresses too much.** "43" hides whether the issue was build / tests / coverage / docs / wiring. The structured `ReviewIssue` list exists but the headline is just a number.

## Design principles

- **Goal-graded, not effort-graded.** Score reflects whether the user's stated goal was achieved.
- **Context-driven rubric.** Each spec generates its own 5-7 measurable acceptance criteria at plan time; the score is the % of those met.
- **Closed-loop.** Post-terminal signals (PR CI status, merge, customer follow-up) flow back into the score over time.
- **Confidence-banded UI.** Drop the precision; show qualitative bands. Numeric only on disclosure.
- **Show the rubric, not just the score.** The user should see which criteria passed/failed, not a single opaque number.

## Proposed design — 3 layers

### Layer 1: Plan-time rubric (REPLACE `accuracyScore`)

At plan creation, the planner emits a **measurable rubric** alongside the task list. Each rubric row has:

```json
{
  "id": "R1",
  "criterion": "All 6 listed Python files in app/ pass black --check",
  "verification": "shell: black --check app/ exits 0",
  "weight": 5,
  "category": "FORMATTING"
}
```

Categories suggested: `BUILD`, `TESTS`, `LINT_FORMAT`, `CONTRACT` (API endpoints), `FEATURE` (functional behaviour), `DOCS`, `STRUCTURE` (file presence/layout), `VCS` (commit message format, branch name).

Verification types:

| Type | What to verify | Tool |
|---|---|---|
| `shell` | run a command, assert exit code | BuildProjectTool / ShellTool |
| `file-exists` | check workspace tree for a file | listFiles |
| `file-content` | grep file content for a pattern | searchFiles |
| `endpoint` | HTTP probe against preview | preview/api-prober |
| `pr-state` | PR opened / merged / CI green | GitHub API |
| `manual` | reviewer LLM judgement (fallback only) | ReviewerAgent |

### Layer 2: Run-time verification

After task execution, before final scoring:

1. Walk the rubric
2. For each row, dispatch the verification step
3. Mark PASS / FAIL / UNKNOWN with evidence (e.g., "black --check exited 0", or "PR opened https://...")
4. Headline score: `weightedSum(PASS) / weightedSum(all)`
5. Failed rows ARE the issue list — no separate AccuracyAssessment LLM

### Layer 3: Post-terminal closed-loop signals

Background job that periodically updates score on:

- **CI status** flips green within 24h → +5 confidence
- **PR merged** within 7d → +10 confidence
- **Follow-up PR with same branch prefix** within 7d → -10 confidence (the run needed correction)
- **`@executespec retry` label on PR** → -20 confidence (user explicitly asked for redo)

This gives the customer a number that *evolves*, signalling "verified by reality" vs "judged at terminal".

## UI changes

### Run detail header

Replace:

```
verifiedScore: 43          → [4-band colour bar]
```

with:

```
[Verdict pill] Ready to ship          (or Needs review / Likely needs rework / Blocked)
[Confidence] 4 of 7 criteria verified  (click to expand)
```

Expanding shows the rubric checklist:

```
✓ All 6 listed Python files exist
✓ buildProject smoke passes (exit 0)
✓ pre-commit-config.yaml present at repo root
✓ PR opened against pplai-api
✗ black --check app/ exits 0       ← evidence: 6 files would reformat
✗ Commit message matches "fix: apply black formatting…"
? Reviewer manual: code style consistent (UNKNOWN — too few files reviewed)
```

Numeric `verifiedScore` lives on disclosure only.

### Status badge

Drop the 4-band by-score colour. Instead, badge derives from verdict:

| Verdict | Trigger | UI |
|---|---|---|
| **Shippable** | All hard-required rubric rows PASS + build OK | green |
| **Needs review** | ≥1 SOFT rubric row FAIL OR ≥1 UNKNOWN | amber |
| **Likely rework** | ≥1 HARD rubric row FAIL | red-amber |
| **Blocked** | Build fail or run errored | red |

## Migration plan

### Phase A — non-breaking (3-4 days)

1. New `run_rubric` table per V_next: (runId, rubricRowId, criterion, verification, category, weight, status, evidence)
2. Planner agent emits the rubric alongside the plan (new `submitPlan` schema field)
3. Run-time verification step at end of pipeline, populates row statuses
4. New `verifiedScoreV2` column on validation_report = weighted % verified
5. Legacy `verifiedScore` stays, populated dual-write
6. UI shows BOTH old + new numbers gated behind feature flag `scoring.v2.preview`

### Phase B — UI swap (2-3 days, after Phase A bakes for ~1 week)

1. UI surfaces verdict + rubric checklist as the headline; numeric becomes disclosure
2. Status badge derives from verdict
3. Validation report markdown becomes "rubric report"

### Phase C — retire (1 day, after Phase B confirmed)

1. Legacy `verifiedScore` becomes deprecated (no longer computed; persisted=null on new runs)
2. Legacy `accuracyScore` deprecated (rubric subsumes it)

## Open questions

1. **Who emits the rubric?** Planner has the context but might game it (emit easy criteria). Alternative: a separate `RubricAgent` (LIGHTWEIGHT tier, deterministic) inspects spec + emits criteria. Adds ~$0.02/run.
2. **Should rubric be visible to the worker?** Yes — current spec doesn't surface "what makes this DONE" to the worker. Showing the rubric upfront makes the worker self-grading.
3. **What about specs with no explicit criteria?** ("Build a TODO API") → planner inferred rubric ("POST /todos returns 201", "GET /todos returns 200", "README has run instructions"). Show these inferred criteria in the plan-approval gate so user can correct.
4. **Customer-facing or operator-facing?** Both. Customer sees rubric in the project dashboard. Operator (Admin) gets cross-run rubric-category trends.
5. **How does this interact with `ValidationReport.markdownReport`?** The current LLM-generated narrative still has value for readability. Keep it but auto-generate it FROM the rubric (one sentence per row), not the other way around.

## Cost / time impact per run

| Component | Added cost | Wall-clock |
|---|---|---|
| RubricAgent (LIGHTWEIGHT) at plan | ~$0.02 | +3-5s |
| Verification step (mostly shell + file probes, one preview/HTTP) | ~$0.005 (LLM-judged "manual" rows only) | +5-15s |
| Background closed-loop sweep (async, per project) | <$0.001 | 0 (async) |
| **Total** | **~$0.025/run** | **+10-20s** |

Roughly **2-3%** of typical run cost. Cheap insurance for trustworthy scoring.

## Decision needed before building

- [ ] Approve the goal-graded direction in principle?
- [ ] Phase A 3-4 day scope OK?
- [ ] Any rubric categories I should add/drop?
- [ ] Phase B UI: keep VerdictPill (already exists) or design new "rubric checklist" component?
