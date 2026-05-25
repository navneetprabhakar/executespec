# Propagating PATCH learnings → BUILD pipeline + PATCH agents in project runs (2026-05-25)

> Strategic frame (from the SWE-bench cycle): the **scaffold lifts the model 5–17×** on existing-repo
> PATCH (model-alone 3–10% → system 52–59%). Competitors mostly chase greenfield; with model costs
> rising, **existing-repo PATCH is a genuine differentiator**. This doc maps the PATCH learnings into
> (A) the normal BUILD pipeline and (B) project runs — **language-neutral**, validated for no-regression.

## The transferable learnings (validated, generalizable — not SWE-bench/Python-specific)
1. **Manufacture a feedback signal** — reproduce/verify the expected behavior *before* claiming done.
2. **Run the repo's existing tests** as a correctness gate (the contract the hidden test also encodes).
3. **Scope discipline** — no unrelated cleanups; never modify existing tests; clean diff.
4. **(candidate) Multi-sample + selection** — generate N candidates, keep the one whose tests are cleanest (pending the pass@N diagnostic now running).

## A. Into the BUILD pipeline (so NORMAL/greenfield runs improve, not just PATCH)

**Key finding — BUILD already writes tests but never *runs* them in-loop.** `SequentialExecutor`'s
per-task smoke gate calls **`BuildProjectTool` (compile only)**; `SYSTEM_PROMPT_BASE` makes tests
*mandatory to write* but the only in-loop check is "does it compile." `WorkspaceTestRunner`
(multi-language, via `TestCommandResolver`) runs **post-run, for scoring only** (`generateValidationReport`).
So BUILD's mandatory tests are **written but unverified**.

| PATCH learning | BUILD today | Transfer (language-neutral) | Effort/risk |
|---|---|---|---|
| **verify-before-done (run tests, see green)** | compile-only smoke; tests written but not run in-loop | **Wire `WorkspaceTestRunner` into the per-task smoke gate** — after compile, run the worker's just-written tests; fail→one retry with the failure injected (mirror the existing build-fail retry). The infra already exists + is polyglot; this is mostly *wiring*. | **High value, low risk** — biggest BUILD quality lever |
| **reproduce/expected-behavior discipline** | tests-mandatory only | Add to `SYSTEM_PROMPT_BASE`: "after writing a function, write+run a test that asserts the *spec's* expected behavior, not just that it runs" (language-neutral). | Low |
| **existing-test feedback (existing-repo BUILD)** | none | In existing-repo BUILD, run the touched module's existing tests in the smoke gate (regression catch). | Med |
| **scope discipline / no test-tampering / clean diff** | weak in `SYSTEM_PROMPT_BASE` | Strengthen the prompt + make `ScmPushService`/`gitCommit` gitignore-aware (shared w/ bench captureDiff). | Low |
| **multi-candidate + selection** | none | HIGH-complexity tasks only: 2 candidates, keep the one with the cleaner smoke+test run. Gate by complexity (cost). | Med, **pending pass@N proof** |

**Anti-overfit gate:** every change validated on `scripts/v2-eval.sh` (greenfield, multi-language, the
planner→worker path) for no-regression *before* dev. That's the guard that keeps this from becoming a
Python/SWE-bench special.

## B. PATCH agents in PROJECT runs (cost/time differentiator)

**Today:** project context uses PATCH **only** via the 4 autofix surfaces (`FindingPatchSubmitter`,
`source="finding:*"` → `runMode=PATCH`). **Every other project ask** (a feature, a small fix) goes
through the **full BUILD pipeline**: `SmartClassifier → OutlinePlanner → SequentialExecutor → Reviewer`
— a planner call + a reviewer multi-pass on *every* ask, even a one-line fix.

**Opportunity — route targeted project asks to PATCH-mode:**
1. **Classifier routing.** Extend `SmartClassifierAgent`: an **existing-repo + targeted/small** ask
   (SINGLE_SHOT / SMALL, a bug-fix or localized change) → dispatch **`runMode=PATCH`** (thin agent),
   skipping the planner + reviewer. Reserve full BUILD for greenfield + multi-file features.
   *Saves the planner call + the reviewer multi-pass per ask — material cost/latency cut as model
   prices rise.*
2. **Feed project context into the PATCH agent for localization.** PATCH's one weakness is finding the
   right place on an *unfamiliar* repo. In a project we already have **codemaps + architecture +
   pgvector RAG + project memory** — inject the top candidate files/symbols into the PATCH prompt so it
   starts localized. (SWE-bench showed 88% localization *blind*; with project context it should be near-ceiling.)
3. **Keep the safety rails for project PATCH:** retain the build smoke + (optionally) the reviewer wiring
   pass; for PRODUCTION tier, add a lightweight plan-gate (PATCH today skips the gate — fine for autofix,
   needs a gate for customer feature asks).

**Net:** a project "fix X in repo Y" ask runs cheaper + faster via PATCH (no planner/reviewer overhead)
with *better* localization (project context) — and the verify-before-done loop ensures quality. This is
the existing-repo value-add, productized beyond just autofix findings.

## Sequencing (impact × risk, all language-neutral, all v2-eval-gated)
1. **Wire `WorkspaceTestRunner` into the BUILD per-task smoke gate** (verify-before-done in BUILD) — #1 BUILD quality lever, infra already exists.
2. **De-Python'd reproduce-first discipline** into `SYSTEM_PROMPT_BASE` (done for `patch-agent.md`; mirror into the BUILD worker prompt).
3. **Classifier → PATCH routing for targeted project asks** + project-context localization injection — the cost/time differentiator.
4. **Multi-candidate + selection** (HIGH-complexity, both BUILD + PATCH) — *only if* the pass@N diagnostic shows multi-sampling lifts the ceiling.
5. **Diff hygiene** (gitignore-aware) shared across bench + product commit paths.
