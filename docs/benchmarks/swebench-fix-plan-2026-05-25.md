> ⚠️ **UPDATE (later same day):** two findings supersede parts of this plan — see
> **[`swebench-oracle-correction-2026-05-25.md`](swebench-oracle-correction-2026-05-25.md)**.
> (1) The eval loop below was partly run against a **broken oracle** (gold fails on ~4/10 Lite);
> now fixed. (2) The bigger lever turned out to be a **prompt-mode mismatch**: `patch-agent.md` was
> the *oracle* prompt ("READ THE GOLD TESTS FIRST"), which is wrong for blind runs (benchmark **and**
> the product's blind surfaces) — rewritten mode-aware reproduce-first. L1's minimality clause (file-
> count cap) was dropped (it under-fixed multi-file cases); scope-discipline kept without the cap.

# SWE-bench Fix Plan — Failure RCA → Implementable Levers (2026-05-25)

> Eval loop: the **10 Lite cases, Anthropic only** (fixed sample). Implement a lever → re-run the 10
> → keep only if it improves (or holds) the score. Each lever must also help **general/product runs**
> (the 4 autofix surfaces + existing-repo BUILD), not just the benchmark. Validated against
> `scripts/v2-eval.sh` (greenfield no-regression) before anything merges.

## Failure taxonomy (from the Live-45 RCA)

| Mode | Share | What happens | Root cause |
|---|---|---|---|
| **Wrong fix** | ~60% (UNRESOLVED) | patch applies, hidden test fails | reasoning/localization — 3 sub-types below |
| **Thrash-no-edit** | ~13% (EMPTY) | 100–120 iters, 0 edits, 0-byte patch, $2+ | can't localize in a large repo; no "give up / commit" pressure |
| **Diff pollution** | ~20% (PATCH_FAIL) | worker ran the tool; its output files captured → non-applying | `captureDiff` not gitignore-aware + over-scoping |
| **Give-up (OpenAI)** | provider | ~12 iters then bails → 0 resolved | per-provider iter behavior |

**Wrong-fix sub-types (the hard core):**
- **A — different valid approach** (conan-17708): solved the 200%-progress bug by pre-computing size; gold streamed members + bumped interval. Hidden test pins the gold's approach. *Largely inherent to blind eval.*
- **B — scattered vs root fix** (gitingest-115): added `.lower()` at 2 call-sites; gold normalized **once at the source** → model misses cases.
- **C — misread intent** (reflex-4347): tried to *enable* direct init; gold *blocks* it. Wrong direction.

**Key prior result:** the "blast-radius / enumerate touch-points" prompt lever moved the score by **0** (A/B). So sub-types B/C are **not** fixable by a single prompt sentence — they need a *feedback signal* or a *structural* change.

## Levers (prioritized for the blind Lite-10 score)

### L1 — Self-verification via a worker-written repro test (targets WRONG-FIX; highest score upside)
**Idea:** the blind worker has **no test feedback** today (gold held out, gate OFF for benchmark) — so wrong fixes (B/C) go undetected. Have the worker **reproduce the issue first**: from the issue's described behavior, write a *scratch* repro (at `/tmp`, NOT in the repo, so it never pollutes the patch), confirm it FAILS on the unmodified code, make the fix, then confirm the repro PASSES. This is "write the failing test first," the single strongest lever for blind bug-fixing.
- **Impl:** `patch-agent.md` — add a "REPRODUCE FIRST" section; scratch repro at `/tmp/repro_*.py`; delete before finish. No gate change.
- **Risk:** worker writes a weak repro / can't reproduce blind → no help. Measure on Lite-10.
- **Product benefit:** the 4 autofix surfaces already get the failing test (the finding); this helps the *blind* slices (issue-only specs / existing-repo BUILD where no test is provided).

### L2 — Thrash guard: no-edit ceiling (targets THRASH; biggest cost/time win)
**Idea:** if no `writeFile`/`editFile` after N iters (e.g. 25), inject ONE hard "commit to an edit now or call completeTask('cannot fix')" nudge, force-terminate sooner. Today the progress signal counts no-*progress*, but a worker *reading 100 files* looks busy.
- **Impl:** `WorkerAgent.run` — add `itersSinceFirstEdit` ceiling alongside the existing `itersSinceProgress`.
- **Product benefit:** protects customer autofix/existing-repo runs from 15-min / $2–3 runaways that deliver nothing. **Validate no-regression on `v2-eval`.**

### L3 — Diff hygiene: exclude generated/output files (targets POLLUTION; PR-quality win)
**Idea:** stop capturing tool-output / build artifacts into the patch (checkov scan CSVs, `dist/`, caches).
- **Impl:** widen `captureDiff` excludes (benchmark) + make the **product `gitCommit`/`ScmPushService`** gitignore-aware (shared logic).
- **Product benefit:** the autofix surfaces open PRs — this stops committing junk.

### L4 — Per-provider iter caps (targets GIVE-UP / cost; tuning)
OpenAI bails at ~12 iters → 0 resolved (give it a higher floor or accept it's a fast-trivial-only provider); Gemini thrashes to 120 (cap earlier). Tuning only — measure.

## Iteration protocol
1. Establish **Lite-10/Anthropic baseline** (running).
2. Implement **L1** (highest wrong-fix upside) → re-run Lite-10/Anthropic → compare.
3. Add **L2** (thrash guard) → re-run → compare.
4. Keep only levers that improve/hold; revert the rest.
5. Before merge: `v2-eval` greenfield no-regression check.

## Honest expectation
Sub-type **A** (different-valid-approach) is largely irreducible blind. The movable share is **B/C (via L1 feedback)** + the **non-attempts (L2/L3 convert EMPTY/PATCH_FAIL → real attempts)**. Realistic near-term: lift the blind Lite-10 from its baseline by converting thrash/pollution to attempts and catching a fraction of B/C with self-verification. Not a path to 60%+ blind — that needs deeper investigation-quality work.
