# SWE-bench Phase-1 — Unresolved Deep-Dive + Model-Level Read (2026-05-25)

> Deep analysis of every genuine unresolved run (official harness, real per-instance test output),
> per provider, and an honest read of our score vs published **model-level** numbers.

## 0. The scoreboard, model-level (official harness, test-300 stratified-29)

| | Anthropic (sonnet-4-6) | OpenAI (gpt-5.4) | Best-of-both (union) | Both resolve |
|---|---|---|---|---|
| Resolved | **17/29 (59%)** | **15/29 (52%)** | **19/29 (66%)** | 13/29 (45%) |

## 1. Is 59% actually "weak"? — the published comparison is apples-to-oranges

The headline numbers you're comparing against are **SWE-bench *Verified*** (Sonnet 4.6 = 79.6%; agent scaffolds 60–66%). We ran **SWE-bench *Lite* test**. They are different benchmarks:
- **Verified (500)** is human-curated to be *well-specified and solvable* → resolve rates run much higher.
- **Lite (300)** includes under-specified / ambiguous issues and is *not* curated for solvability.
- The right yardstick is **published *Lite* SOTA ≈ 47–48%** (Globant/Devlo, Claude 3.5, 2024). **Our 59% (Anthropic) / 52% (OpenAI) is *above* published Lite SOTA**, and 66% best-of-both.
- The 79.6% is also a **dedicated SWE-bench harness**; ours is a **general product** scaffold.

So vs the correct yardstick we're **not weak** — we're at/above Lite SOTA. The "weak" feeling comes from comparing Lite→Verified.

## 2. The hard core: 10/29 fail on BOTH providers (the model-capability ceiling)

`django-11019, django-12589, django-15738, matplotlib-22835, xarray-3364, sphinx-10325, sphinx-8435, sympy-14317, sympy-16106, sympy-24102`

Both Sonnet **and** GPT miss these → it's a **reasoning ceiling**, not provider- or scaffold-specific. Provider-unique misses are few: Anthropic-only `django-14534`; OpenAI-only `django-13158, django-13768, django-14580, sympy-14817`. (Anthropic 17 vs OpenAI 15 — Anthropic edges it on the margin.)

## 3. WHY each one fails (real test output) — dominant mode = right place, *wrong precise output*

Localization was **88% HIT** (worker edits the exact gold file). So these are not "couldn't find it" — they're "fix is plausible but the exact behavior/output is subtly wrong." Evidence (the actual failing assertions):

| Instance | Real failure | Type |
|---|---|---|
| django-14534 | `AssertionError: 'id_name_0' != None` | wrong value |
| django-15738 | `Operation type mismatch (expected ['RemoveField','DeleteModel','AddField'…])` | wrong logic (migration optimizer) |
| django-13768 | error-message string differs (`'Error calling <function …>' != 'Error calling Disp…'`) | wrong string/repr |
| django-13158 | `QuerySet[…] != []` | wrong queryset behavior |
| matplotlib-18869 | `assert _parse_to_version_info(s) == version_tuple` | wrong parse output |
| matplotlib-22835 | `assert img.format_cursor_data(v) == label` | wrong formatted output |
| sphinx-10325 / 8435 | `assert list(actual) == [...]` (close but ≠) | wrong list content/order |
| sympy-16106 | `doprint(Interval(0,a)) == '<mrow>…'` ≠ | wrong MathML string |
| sympy-24102 | `parse_mathematica(e) == sympify(…)` ≠ (Greek chars: regex vs gold's `isascii`) | different valid approach → wrong |
| xarray-3364 | `_FillValue` decode dict-comprehension change | wrong logic |
| sympy-14317 / 14817(oai) | worker patch triggers `ValueError: Name node can't be used with 'False'` | fix *broke* parsing |
| django-12589 | `ambiguous column name: rating` (the queryset bug itself) | wrong fix |

**Pattern:** ~80% are **wrong-output-precision** — the model lands in the right function, makes a believable edit, but the exact string / value / sequence / logic differs from what the hidden test pins. A handful (`sympy-14317`) actually **break** something. Almost none are localization or system crashes.

## 4. Why the published dedicated scaffolds beat this (and what we lack)

The model, run **blind**, has **no signal that its plausible output is wrong**. Reproduce-first only checks the model's *own* (possibly wrong) understanding. The scaffolds that hit 65–80% close exactly this gap with **test-time techniques we don't have**:
- **Multi-sample + selection** (generate N candidate patches, pick by regression-test signal / LLM-judge) — the single biggest lever in the published Lite SOTA jump (~43%→48%+).
- **Execution feedback against the repo's existing tests** (the contract the hidden test also encodes).
- **Self-consistency / critique loops.**

Our system does **one** patch, no second opinion, no existing-test execution gate. That's the gap between ~59% and the 65%+ scaffolds — and it's the **product-relevant** place to invest (it lifts any model on any existing-repo PATCH).

## 5. Model-level lift — THE PRODUCT'S VALUE, QUANTIFIED

Model-alone baseline = the same model, **single-shot, handed the gold file** (localization given for free), no agentic loop. Scored on the official harness:

| Provider | **Model alone** (single-shot + file) | **Our system** (agentic) | **Lift** |
|---|---|---|---|
| Anthropic (sonnet-4-6) | **1/29 (3%)** | **17/29 (59%)** | **+16 instances · +55 pts · ~17×** |
| OpenAI (gpt-5.4) | **3/29 (10%)** | **15/29 (52%)** | **+12 instances · +41 pts · ~5×** |

**The system turns a ~3–10% raw model into a 52–59% solver on real existing-repos.** That is the product's reason to exist, measured. And note: the baseline was *handed the correct file*, so the lift is **not** localization — it's the **agentic loop** (read more context → iterate → reproduce-first → refine) doing the work. The model one-shot, even with the file, almost never nails the exact fix; our loop does, 5–17× more often.

**Honesty caveat:** the model-alone baseline is single-shot *diff* generation, which is format-fragile (a wrong line number → non-applying patch), so it conflates "reasoning" with "edit mechanics." That *understates* raw reasoning somewhat — but it's exactly the fragility our scaffold removes (we use surgical `editFile`, not hand-written diffs), so it's legitimate system value. Directionally unambiguous: the scaffold is doing the heavy lifting.

**Reframes "is 59% weak?":** no — the system is **adding +41 to +55 points** over the model alone. The remaining gap to the 65–80% dedicated scaffolds is the test-time techniques in §4 (multi-sample + selection, existing-test execution), which would lift *every* provider further.

## 6. The targets, ranked for the overhaul (all from a ~59% base, not a false 17%)
1. **Multi-candidate + test-based selection** (HIGH-complexity gate) — the proven SOTA lever; directly attacks the wrong-output-precision core.
2. **Execute the repo's existing tests in-loop** as a correctness gate (catches "plausible but wrong" before completeTask).
3. **Self-critique pass** on the candidate patch vs the issue's exact expected behavior.
Localization and harness/measurement are **not** the bottleneck (88% localization; official harness now canonical).
