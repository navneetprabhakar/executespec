# Post-merge retest + consolidated TODO — 2026-04-18

> Thorough local retest against dev tip after merging PR #40 (19 adversarial-test
> findings), PR #42 (Plan 1 P1–P5 + Plan 2 Gap 3 + Plan 5 P1 scaffold), PR #43 (dev→main).
> Commit at test time: `feb3d8b5`.

## Summary

- **Unit tests:** 257/257 pass · BUILD SUCCESS
- **Regression sweep:** 33/33 probes green — every one of the 19 earlier findings stays closed
- **Guardrail flag end-to-end:** LOW default works · PUT MEDIUM propagates within ~2 s · InputSanitizer 8 KB cap fires at MEDIUM · RateLimit multipliers correct (READ halved at HIGH, WRITE halved at MEDIUM + quartered at HIGH) · restore to LOW works
- **V2 batch scaffold:** flag seeded `false` · PollService silent at disabled · BATCH approval gives clean 400 (no crash)
- **Smoke run:** Anthropic PROTOTYPE `is_prime` → COMPLETE · CLEAN · Verified=90 · planner=`claude-sonnet-4-6` ✓ (Finding 14 fix live)

## New observations from this pass

Three minor findings — none are regressions, none block anything, all would be
nice-to-fix next sprint.

### N1 — HEAD requests classified as WRITE in `RateLimitPolicy.classifyGroup`
**Severity:** 🟢 polish · **Impact:** LOW

`classifyGroup("HEAD", "/api/models/available")` falls through the if-chain to
the final `return "WRITE"` because the `GET` check is method-exact.
Semantically, HEAD is a `GET` without the body (RFC 7231) and should hit the
same rate-limit bucket.

**Evidence:**
```
HEAD /api/models/available  → X-RateLimit-Limit: 1000  (WRITE ENTERPRISE)
GET  /api/models/available  → X-RateLimit-Limit: 2000  (READ ENTERPRISE)
```

**Fix:** change `if ("GET".equalsIgnoreCase(method))` to
`if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method))`.

### N2 — CHANGELOG test count stale (246 claimed, 257 actual)
**Severity:** 🟢 polish · **Impact:** none

`CHANGELOG.md [Unreleased] → ### Tests` says "Full suite: 246/246 pass" — actual is 257/257 after the P1/P2 + P3/P4/P5 test classes all landed. Off-by-11 typo in the commit message. Trivial doc fix.

### N3 — Guardrail diagnostic can lag live enforcement
**Severity:** 🟢 polish · **Impact:** low (operator confusion during flag flips)

Right after `PUT /api/admin/feature-flags/security.guardrail_level {value:"MEDIUM"}`, the `RateLimitFilter` already applies the new limits while the `GET /api/admin/guardrail` diagnostic can still return `level:"LOW"` for up to ~3 seconds.

Root cause: two caches with different invalidation windows. The `RateLimitPolicy.getLimit()` path reads through to whatever `FeatureFlagService.getString()` returns at each request; the diagnostic endpoint's read hits the same service but can observe a slightly different pub/sub delivery moment. Not actually a bug (both paths reach consistency), but an operator running `GET /guardrail` right after a flip might think the flip failed.

**Fix:** add a note in the diagnostic response like `"note": "may lag actual enforcement by up to 60 s — schedule-refreshed every minute, pub/sub best-effort"`, or invalidate the Caffeine cache on the PUT path so the diagnostic picks it up immediately.

---

## Consolidated prioritised TODO

Every item below is either **(a) a new finding from this retest**, **(b) a deferred item from the earlier plan-doc audit**, or **(c) a known-tech-debt item from CLAUDE.md**. Ordered by "biggest product + security wins per engineer-day".

### 🔴 HIGH — ship in the next 1–2 sprints

1. **Plan 1 P5 call-site wiring.** `FileSandboxPolicy` bean + tests exist today but no caller invokes it. Wire into `FileSystemTool` · `WriteFileTool` · `EditFileTool` so MEDIUM / HIGH actually restrict worker writes. Without this, the file-sandbox knob is theatre.
2. **Plan 2 Gap 2 Phase 2 — V2 startup full-replay.** Interrupted `WORKING` / `REVIEWING` runs currently marked FAILED on boot (Phase 1 already ships). Phase 2 loads the Redis snapshot and resumes `WorkerAgentV2` at the saved iteration. Plan estimates ~400 LOC; deserves its own PR with JVM-stop integration tests.
3. **Bug #1c — `ModelResolver.selectAdaptiveProvider` bypasses strict-provider.** From v2.5.9 eval findings (still open per CLAUDE.md). Adaptive routing silently switches providers even when `-Dexecutespec.strict-provider=true`. Evidence: Proj-OpenAI (74270ac6) logged `provider=anthropic` despite `project.aiProvider=OPENAI`. Fix: strict-mode guard inside `ModelResolver.resolve()` OR opt-out flag per org.
4. **Plan 5 P0 probe — Anthropic / OpenAI / Gemini batch APIs.** Verify native tool calling works in each provider's batch API. One submission per provider, capture response shape, note latency. Needs real spend (~$5–10) + up to 24 h wait per provider. Output: `docs/v2-batch-probe-notes.md` with verdicts.

### 🟠 MEDIUM — queue for a focused sprint each

5. **Plan 2 Gap 3 — V2 test-gen endpoint wiring.** `POST /runs/{id}/run-tests` + `apply-test-fixes` still 410 for V2. Requires product decision on CLI / LIBRARY test-gen scope (entangled with adversarial-report Finding 20). Design doc first, then wire.
6. **Plan 1 P3 / P4 / P5 canary at MEDIUM.** The level-aware policies are shipped but no one's run `scripts/v2-eval.sh` at MEDIUM to measure false-positive rate on legitimate worker shell / file traffic. Budget ~$10 for a 9-run matrix. Without this, operators can't confidently flip MEDIUM.
7. **Plan 5 P2 — V2AnthropicBatchClient end-to-end.** Blocked on P0 probe. Extract `WorkerAgentV2.resumeFromBatch(history, ctx)` from the existing 40-iteration loop. Full implementation + contract test.
8. **Plan 5 P3a / P3b — OpenAI + Gemini batch clients.** Blocked on P2 pattern. OpenAI: JSONL upload + batch create + fetch output. Gemini: inline mode first, then file mode; reuse thought-signature round-trip cache.
9. **Plan 5 P4 + P5 — eval + flag flip + version bump.** Blocked on P3. Full 9-cell matrix vs realtime baseline, publish comparison, flip `harness.v2.batch_enabled=true` in staging.

### 🟡 LOW — polish / doc / operational

10. **N1 (this pass) — HEAD → READ classification in `RateLimitPolicy`.** 2-line change + parameterised test.
11. **N2 (this pass) — CHANGELOG 246 → 257.** Doc fix.
12. **N3 (this pass) — Guardrail diagnostic lag note.** One-line Javadoc + response-body field.
13. **Plan 1 P6 — VERSION bump.** Security-posture feature is a clear minor bump (`2.5.x → 2.6.0`). Needs explicit approval per the versioning memory rule. CHANGELOG is already ready.
14. **Plan 2 Gap 3 — Frontend code-cleanup pass.** The `V2_LEGACY_UI` flags hide the UI; the underlying components (`RunConversation`, `FeasibilityEstimator`, etc.) still compile into the bundle. Once a given flag is permanently `false`, delete the component. Defer until each flag is confirmed won't return.
15. **Planner model sourced from run model, not tier** (CLAUDE.md known tech debt). v2.5.12 `3a3d091b` partially fixed this by resolving `plannerDbModel`/`reviewerDbModel` via `ModelResolver` for the DB field. But `PlannerAgentV2.plannerViaLlm` internally still uses `req.model()` for the actual call. Same fix pattern, thread through properly.

### 🟢 DEFER — ship only when triggered

16. **Plan 3 — Preview control-plane gRPC daemon (all 3 phases).** New infra: gRPC server, mTLS, cert rotation via Cloud KMS, new DB tables. Can't validate from a dev machine. Wait until (a) we hit a preview-resource contention issue OR (b) we want to scale preview independently of the app JVM.
17. **Plan 4 Item 1 — RAG memory-overflow vector spill.** Plan explicitly says "no project has crossed even the old 8K cap yet. Revisit once a real project reports planner context pressure." Track but don't ship until triggered.
18. **Plan 4 Item 3 — RAG OCR / rerank / semantic chunking.** Plan says "Revisit only if retrieval quality is observably bad." No signal today. Track and measure retrieval quality first.

---

## Deferred-item reasoning (condensed)

Full reasoning was written up on 2026-04-18; see the branch history for commit
`3a3d091b` (adversarial) + `feb3d8b5` (open plans merge). Short version per
category:

| Deferred group | Reason |
|---|---|
| Plan 1 P3–P5 canary (item 6) | Real LLM spend; needs explicit budget approval |
| Plan 1 P6 VERSION bump (item 13) | Per `feedback_versioning.md` memory rule — needs user approval |
| Plan 2 Gap 2 Phase 2 (item 2) | 400 LOC complex state restoration; deserves dedicated PR with JVM-stop integration tests |
| Plan 2 Gap 3 V2 test-gen (item 5) | Needs product decision on CLI/LIBRARY test-gen scope first |
| Plan 3 preview control-plane (item 16) | New infra; can't verify locally; needs staging VM pool + DevOps |
| Plan 4 Items 1 + 3 (items 17, 18) | Plan explicitly "defer until triggered" — no signal today |
| Plan 5 P0–P5 (items 4, 7, 8, 9) | P0 needs real batch-API spend; P2–P5 blocked on P0 results |

---

## Revision log

| Date | Who | Change |
|---|---|---|
| 2026-04-18 | autonomous retest pass (Opus 4.7) | Initial — 33-probe regression sweep green, 3 new polish findings, 18-item prioritised TODO merging new + deferred items |
