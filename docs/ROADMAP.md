# Execution roadmap — ordered

> Created 2026-06-01. Single ordered sequence across everything in the 2026-06 review session.
> Ordering is by **dependency + leverage + risk**, not by doc. Each item links its source plan.
> Effort: S(≤1d) · M(days) · L(week+). 🔒 = has a security gate. ⏳ = eval-gated before default-ON.

## Why this order
Most threads converge on ONE capability — **the agent authoring a run-recipe and booting +
health-checking it in-loop** (`verifyBoot`). It is preview-rebuild Phase 1 **and** the Devin
"run-and-observe / verification-as-spine" lever **and** what makes `verifiedScore` deterministic
instead of LLM-self-graded. So: unblock what's built → land the keystone → everything else composes
on top of it.

---

## Wave 0 — Unblock what's already built (do first, this week)
1. **`mvn verify` + `spotless:apply` on PR #199 → merge.** [pending A1–A3] — S. The editing-core fix
   (readFile/editFile), router failover, and all the new tests are written; only a real compile +
   format pass (blocked in the review sandbox) stands between here and merge.
2. **Confirm the 4 re-enabled worker tests are green** [pending A2] — S. Bundled with #1.
3. **Toggle `router.failover.enabled` ON** [pending C1] — S. Fail-safe/additive; lowest-risk flag.

## Wave 1 — The keystone (highest leverage; everything composes on it)
4. **`verifyBoot`/`runApp` worker tool + agent authors a run-recipe + boots/health-checks before
   `completeTask`; feed boot/health into the BUILD/TESTS score.** 🔒 [preview Phase 1 / Devin
   verification-spine + run-and-observe / grading-honesty] — L. Routes through the
   `ShellSandboxPolicy` cleanroom (timeout + output budget + no host network). This single item
   closes the biggest Devin gap, de-LLM-judges the score, and is the foundation for the whole preview
   rebuild.

## Wave 2 — Predictability + latency quick wins (compose on the keystone)
5. **Flip context-compaction flags `worker.tool-compactor` + `worker.history-compaction` ON** after a
   no-regression eval. ⏳ [latency Phase 0a / pending D1] — S. Prereq verified done (compactors
   already use `req.provider()`).
6. **Skip redundant per-task `buildProject` smokes** (build only when build-relevant files changed).
   ⏳ [latency Phase 0b / pending D2] — M.
7. **Per-tier budget re-tune** now that context is smaller. ⏳ [latency Phase 0c / pending D3] — S.

## Wave 3 — Preview rebuild core (build-once + buildpacks + DELETE old code)
> Strangler-fig: each phase ships behind a flag, validates, then deletes what it replaces.
8. **Build-once → run-same digest everywhere; authenticated previews** (fix
   `CloudRunClient --allow-unauthenticated`); **delete the build-at-preview-time path.** 🔒
   [preview Phase 2] — L.
9. **Nixpacks/CNB buildpack default + DELETE `DevDockerfileGenerator` (3,514 LOC) + the 22 code-rewrites
   + the 10 strategy classes.** 🔒 [preview Phase 3] — M. The big cleanup; net LOC drops >50%.
10. **DELETE the `vm` mode + retire the `preview-engine` repo** (removes the SPOF GW VM). [preview
    Phase 4] — M.

## Wave 4 — Mobile
11. **`MOBILE_EXPO` target: Expo/EAS QR + `expo export --platform web` in the preview surface +
    device-cloud verification.** 🔒 [preview Phase 5] — L. Never self-host iOS/Android simulators.

## Wave 5 — The bigger Devin bets + scale (after the foundation is solid)
12. **Mid-task re-planning trigger** in `SequentialExecutor` (worker can request a re-plan when the
    plan is wrong). [Devin closed-adaptive-loop lever] — L.
13. **Wire `TaskWaveScheduler` into the executor** behind `harness.parallel-tasks.enabled` (the core
    is built + validated; this is the race-prone half). ⏳🔒 [latency Phase 1 / pending D4] — L.
14. **RunOrchestrator god-class — next by-hand increment** (with a compiler in the loop). [pending E1]
    — M. Do locally; automated extraction corrupted it twice.
15. *(optional, scale)* **microVM (Firecracker) / managed agent-sandbox** isolation for many
    concurrent untrusted previews. 🔒 [preview Phase 6] — L.

## Parked (founder decision — not scheduled)
- **Grading / `verifiedScore` strategy** [pending F1] — note: Wave-1 (#4) already substantially
  de-risks this by making the TESTS/BUILD axes deterministic; revisit the *weighting* after.
- **Claude Code / Codex head-to-head report** [pending F2] — handled on your setup.
- **Frontend code-splitting + unit tests, `ProviderHealthTracker` circuit breaker on failover,
  JAVADOC rewrite** [pending G] — optional pre-existing debt.

---

## Dependency map (the critical path)
```
#1 merge ─▶ #4 verifyBoot (KEYSTONE) ─┬─▶ #5 context flags ─▶ #6 skip builds ─▶ #7 budgets
                                       ├─▶ #8 build-once ─▶ #9 buildpacks+delete ─▶ #10 delete vm ─▶ #11 mobile
                                       └─▶ #12 re-planning ─▶ #13 parallel exec
#14 RunOrchestrator: independent (local, anytime)   #15 microVM: only at preview scale
```
Two things gate the most downstream value: **(a) merging #1** and **(b) landing #4**. Do those two and
the rest is parallelizable.
