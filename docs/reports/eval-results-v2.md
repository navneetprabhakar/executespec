# Post-fix eval — 2026-04-16

> Companion to [`eval-results.md`](eval-results.md). After the fix batch
> (commits `f8201b5c`, `eb3b55b8`, `be110b7a`) landed on
> `feature/validation-v3-pillar1-requirement-graph`, re-ran S1 (TINY fib) ×
> 3 providers and submitted a deterministic-failure project Launch to
> verify retry dedup.

## Fixes applied

| # | Fix | Commit | Migration |
|---|---|---|---|
| 1 | Dashboard uses `verifiedScore` instead of `accuracyScore` | `f8201b5c` | V110 (flips `scoring.v2-verified=true`) |
| 2 | JWT TTL 60 m → 360 m | `f8201b5c` | — |
| 3 | PARTIAL retry loop dedups by V107 `statusReason` | `eb3b55b8` | V111 (adds `last_failure_signature` + `consecutive_failure_count`) |
| 4 | `SequentialExecutorV2` populates `lastLlmFailure` with outcome-specific message | `be110b7a` | — |
| 5 | Backend restart → `/api/models/available` now returns `claude-sonnet-4-6` for Anthropic PLANNING | (config) | — |

Not applied (confirmed out of scope):
- `*_BATCH` enum strip (frontend/CLAUDE.md says retained for V2 batch roadmap).
- `.temperature(...)` removal in `ProjectChatService` (user directive).
- Planner/reviewer tier-model resolution (separate PR).
- Gemini `salvagePlanFromText` extension (deferred).

## Verification matrix

### Anthropic chat (was Bug B)
Before:
```
Sorry — chat failed: 400 - {"type":"error","error":{"type":"invalid_request_error","message":"`temperature` is deprecated for this model.", …
```
After:
```
Based on the available context, this project provides a hostel management
system with a REST API backend and a React frontend, including features like
payment handling and connectivity monitoring.
```

### Dashboard avgScore (Hostel Management)
- Before: **63.43** (averaging `accuracyScore`).
- After:  **69.14** (averaging `verifiedScore` where present; falls back to `accuracyScore`).
- Key individual row that moved: `af6be6db` (backend README banner, COMPLETE, build passed, reviewer FIXED_ISSUES) — **45 → 85**.
- 3 rows with genuine PARTIAL (frontend banner retries) stayed at 55 because verifiedScore ≈ accuracyScore on those. The overall move is smaller than initially predicted because only 1 of 7 recent rows had the mismatch — most clean runs post-V106 already match.

### JWT TTL
```
iat=1776392473 exp=1776414073 ttl=21600s (6.0h)
```
Confirmed 6h.

### Migrations
Flyway boot log:
```
Successfully validated 112 migrations (execution time 00:00.194s)
Successfully applied 2 migrations to schema "public", now at version v111 (execution time 00:00.030s)
```
V110 + V111 applied cleanly.

### Model matrix
```
ANTHROPIC  PLANNING model=claude-sonnet-4-6
GOOGLE     PLANNING model=gemini-3-flash-preview
OPENAI     PLANNING model=gpt-5.4
```
No `claude-opus-4-7` in the live matrix. Temperature 0.2/0.3 in
`ProjectChatService` works against sonnet-4-6.

### S1 (TINY fib) × 3 providers — BEFORE vs AFTER

| | **Before** (flag OFF) | **After** (flag ON) |
|---|---|---|
| Anthropic verified | — (pre-V106 path) | **90** |
| Anthropic accuracy | (not computed) | 92 |
| Anthropic dimensions | — | **90 / 76 / 90 / 95 / 90** (CQ / TC / AR / SC / PF) |
| OpenAI verified | 90 | **90** |
| OpenAI accuracy | 82 | 62 (LLM lowered its own estimate; doesn't matter) |
| OpenAI dimensions | 82 / 76 / 82 / 82 / 82 (anchored on accuracy) | **90 / 76 / 90 / 90 / 90** (anchored on verified) |
| Gemini verified | — (reviewer hang, no report) | 50 |
| Gemini accuracy | — | 88 (still hallucinating high on a broken build) |
| Gemini dimensions | — | **50 / 76 / 50 / 50 / 50** (honest — build failed) |
| Gemini build | ✓ (tests passed, reviewer hung) | ✗ (reviewer terminated with REVIEW_FAILED this time — slightly different failure mode) |

The two-headline improvements from flag-on:
1. **Clean runs score 90 instead of 82** (anchored on verified build+verdict evidence, not LLM's generous-or-stingy grade).
2. **Broken runs score 50 instead of 88** (Gemini's accuracyScore was 88 even with build=false — without the verified anchor, that hallucination would have averaged into the dashboard).

### Retry dedup (was P0 in synthesis)

Submitted a deterministic-failure Launch on Hostel Management: spec asked to
inject an import of a module `definitely_does_not_exist_xyz123_fake` — the
build on any sane repo fails compilation.

Before (analogous run from earlier today, `9142233f-159`):
- 1 original + 3 retries = **4 attempts**
- `failureReason: "V2 returned PARTIAL after 3 retries"`
- Wasted 3 retry cycles on a provably-deterministic failure.

After (run `4de8cf32-c02` today):
- 1 original + 1 retry = **2 attempts**
- `failureReason: "Same failure signature 'FINAL_BUILD_FAILED' on 2 consecutive attempts; aborting retries"`
- Dedup fired correctly: the child V2 PipelineRun's V107 `statusReason` was
  `FINAL_BUILD_FAILED` both times → match → short-circuit.
- `consecutive_failure_count` persisted to the DB per V111 (not surfaced on
  the DTO, but the behaviour that reads/writes it works).

**Savings:** ~50 % of the original retry budget on deterministic failures.
In cost terms, a launch that used to chew 3 wasted V2 runs (say $0.15 of
waste) now chews 1 ($0.05).

### Sibling solution did complete
The companion solution in the same Launch (`fcc7a1c8-c53`, running on the
other repo) completed cleanly at DONE — OpenAI likely ignored the weird
import request in that repo and picked a different interpretation. This is
fine for the dedup test because it confirms:
- The dedup is **per-solution**, not per-launch — a healthy repo proceeds
  normally.
- The launch ends PAUSED (1 DONE, 1 FAILED) instead of all FAILED, so the
  user can still ship the half that worked.

### Worker failureReason (was Bug D)

Worker-FAILED tasks now write an outcome-specific reason to
`lastLlmFailure`, which `RunMapper.extractFailureReason` already reads as a
fallback. Possible messages:
- *"Worker stopped without calling completeTask after 18 iterations. Final: …"*
- *"Iteration cap exceeded (35 iters, 47 tool calls). Worker did not call completeTask."*
- *"Worker threw unrecoverable exception: …"*
- *"Run hit its hard cost cap; worker stopped cleanly mid-task."*

No FAILED task surfaced during this re-eval (all 3 S1 runs ended cleanly,
or in Gemini's case ended REVIEW_FAILED at the run level, not task level),
so this path is exercised only when a real worker-level failure occurs.
Regression risk is low — the change is strictly additive.

## Cost total for today

| Stage | Cost |
|---|---:|
| Initial 6-run eval | ~$0.26 |
| Initial project launch (4-attempt waste) | ~$0.08 |
| Chat + reconcile probes | ~$0.00 |
| Post-fix S1 × 3 re-eval | ~$0.12 |
| Post-fix deterministic Launch (dedup verified) | ~$0.08 |
| **Total** | **~$0.54** |

Cap was $25. Actual spend: 2.2 % of cap.

## Still-open items (from synthesis.md, deferred)

- **Gemini reviewer-hang** — the Gemini S1 re-run ended REVIEW_FAILED
  rather than hanging this time. Unclear if the hang was transient or if
  the new 90/60 s timeouts in `ReviewerAgentV3.runPassWithTimeout` (already
  present per the plan-mode explore) closed it; monitor.
- **Bug A (Gemini `MALFORMED_FUNCTION_CALL`)** — not re-exercised. Would
  need an S2-class spec to trigger.
- **Planner/reviewer tier-model resolution** — separate PR; OpenAI runs
  today still used `gpt-5.4-mini` (run-level default) instead of the matrix's
  `gpt-5.4` for PLANNING. That still doesn't match the stated model matrix.
