# How to read the SWE-bench brief — methodology + validity (forwardable)

> **⚠️ SUPERSEDED (2026-06-10) — do NOT quote these numbers, internally or externally.** The harness
> that produced them did not pin or track the worker model: LOW-complexity tasks silently ran on the
> LIGHTWEIGHT tier, so the 59%/52% headline is not attributable to the stated models. The eval is
> being re-run on the rebuilt harness (which pins + captures + validates the worker model per run,
> RL-2026-06-001); this note and the brief will be replaced when the re-run lands.

> Companion to `swebench-exec-brief.{html,pdf}`. Written to answer "how do I read these numbers, and
> how much should I trust them?" Honest about what's solid and what isn't. A Claude Code / OpenAI
> Codex head-to-head is in progress; this note will be folded into a revised brief once that lands.

## What each headline number means

| Number | Reading |
|---|---|
| **59% (Anthropic) / 52% (OpenAI)** | Of a **29-instance** SWE-bench Lite sample, the ExecuteSpec pipeline produced a patch that passed the issue's **hidden** tests, scored by the **official** `swebench` harness. **Blind** — the system never saw those tests. |
| **~48% Lite SOTA** | Best published result on the **full Lite-300**. ⚠️ Different (larger) instance set — see caveat #2. |
| **raw model 3–10% (1-shot) / 10–24% (best-of-5)** | The **same** model, single LLM call (even handed the right file), no agentic loop. The "floor," not a competitor — see caveat #3. |
| **88% localization** | The worker edits the correct file 88% of the time; the dominant miss is "right file, wrong precise fix" (a model-reasoning ceiling), not "couldn't find it." |

## What is methodologically solid (trust these)

1. **Official scorer.** Patches are graded by the canonical `swebench` harness (apply patch → run the repo's hidden FAIL_TO_PASS / PASS_TO_PASS tests in the official Docker image), not by us.
2. **Blind, not oracle.** The system generates its patch from the issue text alone. (Earlier internal numbers were oracle-mode — gold test injected — and were **discarded**; this brief is the blind re-measure.)
3. **Auditable.** Every one of the 29 instance IDs + its pass/fail is listed in the brief. Anyone can re-run any single instance.
4. **Same model ± scaffold.** "59% Anthropic" and "raw 3%" use the **same** sonnet model, so the gap is attributable to the agentic scaffold, not a model swap.

## What to caveat (the legitimate limitations)

1. **Small N → wide error bars.** 17/29 = 59% has a 95% confidence interval of roughly **[41%, 74%]**. That interval **overlaps** the ~48% SOTA. Honest claim at this N: *"competitive with SOTA,"* **not** *"beats it."* A full Lite-300 run would tighten this.
2. **The SOTA comparison is not same-sample.** 59% is on a 29-instance stratified subset; SOTA is on the full Lite-300 (different instances). You can't strictly rank a 29-subset rate against a 300-set number — the subset could be easier or harder.
3. **The raw-model baseline is a floor, not the competitor.** "Model alone, 1-shot" is a deliberately weak baseline nobody ships. "5–17× lift over raw model" answers *"does a scaffold help?"* (obviously yes) — **not** *"is our scaffold competitive with the tools people actually use?"* The right baseline is other **agentic** systems (Claude Code, OpenAI Codex, SWE-agent), measured on the **same** instances with the **same** scorer.

## The fix in flight

A **head-to-head vs. Claude Code + OpenAI Codex** — same 29 instances, blind, official-harness scored — is being run (gated: a 5-instance validation first, then the full 29). That replaces the weak raw-model bar with the real competitive question and is the number to quote to advisors. The brief will be revised once that data is in.

## One-line summary for a skeptical reader

> "On a small (N=29), auditable, officially-scored, blind SWE-bench Lite sample, ExecuteSpec's
> agentic scaffold lands ~52–59% — competitive with published SOTA, and far above the same model
> with no scaffold. The headline's error bars are wide at this N, the SOTA comparison isn't
> same-sample, and the definitive number is the in-progress head-to-head against Claude Code and
> Codex on identical instances."
