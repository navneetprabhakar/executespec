# ExecuteSpec — Executive Report (2026-05-25)

> Covers the SWE-bench benchmark cycle (corrected), the product-value (lift) finding, the
> project-features audit, and the fixes shipped this session. All code changes are on
> `feature/swebench-harness`, **flag-gated / dormant**, compile clean, with passing tests, and are
> **NOT pushed** (awaiting approval).

## 1. Bottom line
- **The product works and the scaffold is the value.** On real existing-repo bug-fixing (SWE-bench Lite *test*, django/sympy-heavy), our system resolves **Anthropic 17/29 (59%) · OpenAI 15/29 (52%)** — *above* the published Lite SOTA (~48%). The same models **alone** (single-shot, even handed the right file, even with 5 tries) get **1–5/29**. So our agentic scaffold delivers a **+41 to +55 point lift (5–17×)**. That is the reason to exist, measured.
- **Differentiator confirmed:** competitors chase greenfield; with model costs rising, our edge is **existing-repo PATCH**, where the scaffold's lift is largest.
- **Two systemic problems found + fixed this session:** (a) our benchmark scorer was wrong (4 bugs) — now superseded by the official SWE-bench harness; (b) the project workbench had real security leaks + a "decorative enterprise" gap + a "code-intel never reaches the agents" gap — the highest-value ones are now fixed (dormant behind flags).

## 2. SWE-bench — the corrected numbers (official harness is now canonical)
My hand-rolled scorer had **4 bugs** (numpy-2.0 drift, parametrized-F2P selector, missing-pytest, and django/sympy non-pytest test formats) and mis-scored ~70% of the sample **in both directions** — it had reported a false **17%**. Re-scoring the *same saved patches* with the **official `swebench` harness** (the authoritative scorer, py3.13 venv) gives the real picture above. **Action taken:** the official harness is now the scoring path; the hand-rolled scorer is retired for grading.

- Localization is **88% HIT** — the worker lands on the exact right file even in django/sympy. The dominant miss is **right file, wrong *precise* output** (a model-reasoning ceiling), not localization.
- 10/29 fail on **both** providers (the genuine hard core); best-of-both = 19/29 (66%).

## 3. The lift — the product's value, quantified
| | model alone (1-shot, oracle file) | model alone (pass@5) | **system** | lift |
|---|---|---|---|---|
| Anthropic | 1/29 (3%) | 3/29 | **17/29 (59%)** | **+55 pts (≈17×)** |
| OpenAI | 3/29 (10%) | 7/29 | **15/29 (52%)** | **+41 pts (≈5×)** |

Even multi-shot (pass@5) the raw model stays 3–7/29 → the **agentic loop** (investigate → reproduce → iterate → verify), not sampling, is the lever. (Note: OpenAI multi-shot 7/29 closes about half the gap to its system 15/29 — a hint that complexity-gated multi-candidate selection of the *full system* could add some lift for OpenAI specifically; lower priority.)

## 4. Project-features audit — three load-bearing gaps (full audit: `docs/reviews/project-features-audit-2026-05-25.md`)
1. **Security (real cross-org leaks):** public share-link IDOR (read another org's run/report via a no-auth link); contracts controllers had no tenant guard; `AccessGuardCoverageTest` was a paper tiger.
2. **Architecture/codemap was write-only:** the codemap/architecture pipeline generated well but was **never fed to the planner / worker / PATCH agent** — the most expensive intelligence withheld from the agents that write code.
3. **Enterprise was decorative:** contracts/standards/ADRs/invariants enforcement engines were fully built but had **zero callers** — they never reached the AI and blocked nothing.

## 5. Fixes shipped this session (branch `feature/swebench-harness`, dormant, tests pass, NOT pushed)
| # | Fix | Mechanism | State |
|---|---|---|---|
| Security | **Share-link IDOR** | scope↔project ownership check at create **and** read (404 on public read) | live (no flag) · test passes |
| Security | **Contracts controllers** | `ProjectAccessGuard` read/write on all 3 + **tightened `AccessGuardCoverageTest`** (verifies a real tenant guard, not any `assert*`) | live · test passes (would have failed pre-fix) |
| Consumption + PATCH | **Code-intel → planner + PATCH** | bounded codemap symbol-map injected into the planner (project BUILD) + the PATCH user prompt (project autofix) | flag `codeintel.inject.enabled` (V210, **OFF**) |
| Enterprise | **Contracts + ADR → planner** | `{{CONTRACTS}}` + `{{PROJECT_ADRS}}` now reach `OutlinePlannerAgent` | flag `enterprise.inject.enabled` (V211, **OFF**) |
| Quality | **Verify-before-done in BUILD** (Transfer #1) | `SequentialExecutor` runs the worker's tests in-loop after compile; failure → existing retry | flag `build.test-exec-gate.enabled` (V209, **OFF**) |
| Anti-overfit | **De-Python'd `patch-agent.md`** | reproduce-first prompt is now language-neutral (repo's own test runner) | live |

All new behavior is behind a **default-OFF flag** → zero production change until flipped. Migrations V209–V211.

## 6. Recommendations / next
1. **Validate + flip the three flags** after `scripts/v2-eval.sh` greenfield no-regression (the anti-overfit gate): `build.test-exec-gate`, `codeintel.inject`, `enterprise.inject`. These are the product-quality wins; they ship dormant pending that check.
2. **Don't chase the SWE-bench leaderboard** — it validated the scaffold; the remaining gap to 65–80% scaffolds is test-time techniques (multi-candidate + selection of the *full system*, not the raw model). Optional, complexity-gated.
3. **Still open (not done this session):** wire the standards-runner + breaking-change gate (the rest of the "enterprise decorative" fix); direct worker codemap injection; the 4 frontend project-UI runtime bugs (PRs/Activity tabs empty, settings partial-save, RAG-upload auth); RAG binary-corruption + provider-neutral embeddings.
4. **Gemini** remains blocked by the Google spend cap (raise to re-include).

## 7. Caveats
- N=29 (one stratified sample); directional, not statistical.
- Live-45 numbers remain unverified (different dataset; needs the Live harness).
- The flag-gated changes are compiled + unit-validated but not yet run end-to-end on a live project (the v2-eval/validation step).
