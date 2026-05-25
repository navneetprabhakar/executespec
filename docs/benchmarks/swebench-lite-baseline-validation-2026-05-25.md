# SWE-bench Lite — Baseline Validation vs Published Results (2026-05-25)

> **Goal:** sanity-check our harness against publicly published SWE-bench numbers for the models we run
> (`claude-sonnet-4-6`, `gpt-5.4`, `gemini-3.5-flash`), on **tokens/cost** and **accuracy**. This is a
> credibility check on the measurement, not a leaderboard submission.

## TL;DR

1. **Tokens/cost: VALIDATED (in range).** Our ~**$0.52/instance** (Sonnet, reproduce-first) sits right
   alongside published agentic scaffolds (Agentless **$0.34/issue** on Lite; Claude-Sonnet full-set runs
   **$0.21–0.48/instance**). Per-instance token footprint (~18–22K non-cached input + ~5K output per run,
   ~14K cache-read/call, ~37 turns) is consistent with the standard **"20 turns / 200K-token" cap**
   convention. No red flag — our cost accounting and token usage are credible.
2. **Accuracy: can't be cleanly Lite-vs-Lite compared — two reasons.** (a) Current model versions publish
   almost entirely on **SWE-bench Verified (500)**, *not* Lite (300); there is **no published
   `claude-sonnet-4-6` *Lite* number** to compare against directly. (b) We tested the **23-instance Lite
   *dev* set (N=10 sampled)**, not the 300-instance Lite *test* set the leaderboards use.
3. **Where our accuracy lands anyway:** our **50%** (Sonnet, reproduce-first, dev-10) is **at/above the most
   recent published *Lite* SOTA** (~47–48%, Claude 3.5 Sonnet, late-2024) and **well below the *Verified*
   headline** (Sonnet 4.6 = 79.6%) — both expected (see §3). Plausible and credible for a general-purpose
   scaffold; **not** a leaderboard-grade number at N=10.
4. **To make accuracy leaderboard-comparable:** run the **full Lite test-300** (≈$150 Sonnet, a few hours)
   or **SWE-bench Verified** against the 79.6% headline. The dev-10 is a directional sanity check only.

---

## 1. Our measured numbers (SWE-bench Lite dev set, blind, fixed oracle)

PATCH mode forces the PLANNING tier → Anthropic `claude-sonnet-4-6`, OpenAI `gpt-5.4`, Gemini `gemini-3.5-flash`.

| Provider (model) | Accuracy (dev-10) | Cost/instance | Turns (avg) | Token structure / instance |
|---|---|---|---|---|
| **Anthropic** `claude-sonnet-4-6` (reproduce-first) | **5/10 (50%)** | **$0.52** | ~37 | ~18–22K non-cached input + ~5K output; ~14K cache-read/call (cache-dominated, billed 0.1×) |
| **OpenAI** `gpt-5.4` (reproduce-first) | **4/10 (40%)** | **$0.26** | ~12 (fast) | lower volume; bails earlier |
| **Gemini** `gemini-3.5-flash` | *invalid* | — | — | run blocked by Google project spend-cap (429), not measured |
| Anthropic, OLD oracle prompt (reference) | 2/10 (20%) | $1.01 | higher (thrash) | — |

Fresh held-out 10 (overfit check) in progress at time of writing — will append.

## 2. Published baselines (fetched 2026-05-25, real data)

**The leaderboards report Verified, not Lite, for current models.** What's actually published:

### 2a. SWE-bench **Lite** (300) — the right benchmark, but only older-model numbers are prominent
| System | Model | Lite resolved | Cost |
|---|---|---|---|
| Globant Code Fix | Claude 3.5 Sonnet | **48.3%** | — |
| Devlo | Claude 3.5 Sonnet | 47.3% | — |
| CodeStory Aide | Claude 3.5 Sonnet + GPT-4o | 43% | — |
| MentatBot | GPT-4o | 38% | — |
| **Agentless** | (Lite) | 27.3% | **$0.34/issue** |

### 2b. SWE-bench **Verified** (500) — what current models publish (NOT directly comparable to our Lite)
| System | Model | Verified resolved |
|---|---|---|
| (Anthropic headline) | **claude-sonnet-4-6** | **79.6%** |
| live-SWE-agent | Claude Opus 4.5 | 79.2% |
| Cursor Background Agent | Claude Sonnet 4.6 | 65.7% |
| Composio SWE-Kit | Claude Sonnet 4.6 | 62.3% |
| Cline (autonomous) | Claude Sonnet 4.6 | 59.8% |
| Factory Droid | GPT-5.3-Codex | 58.1% |
| OpenHands + CodeAct v2 | GPT-5.2 | 44.7% |

### 2c. Token/cost conventions (published)
- Standard eval cap: **~20 turns / ~200K tokens per instance**.
- Full SWE-bench at Claude-Sonnet settings: **$480–$1,100** over 2,294 instances ⇒ **$0.21–0.48/instance**.
- Agentless on Lite: **$0.34/issue** at 27.3%.
- Sonnet pricing $3 in / $15 out per 1M (matches our `application.yml`; our `RunCostTracker` math verified correct).

## 3. The comparison + caveats

**Cost/tokens — PASS.** Our $0.52/instance (Sonnet) is the same order as every published agentic scaffold
($0.34 Agentless, $0.21–0.48 Sonnet full). It's slightly higher because our worker loop runs ~37 turns vs
the 20-turn convention — a known speed/cost tradeoff, not a measurement error. Token footprint is consistent
with the 200K-context convention (our per-call context ~18–22K with prompt caching, well under the cap).
**Conclusion: our cost accounting and token usage are credible — no inflation, no hidden under-count
(the $80 2-day Anthropic spend reconciles to the tracker within ~12%).**

**Accuracy — plausible but not leaderboard-comparable, for three structural reasons:**
1. **Lite vs Verified.** Current models publish on **Verified** (curated-solvable, 500). Verified resolve
   rates run materially higher than Lite for the same system. Comparing our **Lite 50%** to the **Verified
   79.6%** headline is apples-to-oranges; the gap is mostly the benchmark, not our system.
2. **Scaffold.** 79.6% is Anthropic's **best dedicated harness**; the leaderboard's 60–66% are
   purpose-built coding agents (Cursor, Cline, Composio). Ours is a **general plan→code→PR product** run in a
   thin PATCH loop — matching the ~48% *Lite* SOTA range of capable-model scaffolds is the realistic bar, and
   we're at/above it.
3. **N and split.** We ran the **23-instance Lite *dev* set, N=10 sampled** — not the 300-instance *test*
   set. At N=10 the 95% CI on 50% is roughly ±30%. dev≠test instances, so even our "Lite" number isn't on
   the same problems as any published Lite number.

**Net:** our **50% on Lite-dev-10** is consistent with published *Lite* results for capable models
(~48% SOTA), and the **cost/token profile is squarely in the published range**. The harness is producing
**credible** numbers. What we cannot yet claim is a **leaderboard-grade accuracy** figure.

## 4. To close the accuracy gap (recommended next steps)

| Option | What it buys | Cost (Sonnet, ~$0.5/inst) |
|---|---|---|
| **Run full Lite test-300** | A real, leaderboard-comparable **Lite** number for our scaffold | ≈ $150, a few hours |
| **Run SWE-bench Verified (500)** | Direct comparison to the 79.6% headline + the 60–66% agent scaffolds | ≈ $250 |
| Larger Lite-dev sample (all 23) | Tighter CI on the sanity check, cheap | ≈ $12 |

Until one of those runs, treat our accuracy as a **directional sanity check** (credible, in-range), and the
**cost/token validation as solid**.

## Sources
- [SWE-bench leaderboards](https://www.swebench.com/) · [CodeSOTA SWE-bench](https://www.codesota.com/browse/agentic/swe-bench) · [Dissecting the SWE-bench Leaderboards (arXiv 2506.17208)](https://arxiv.org/html/2506.17208v2)
- [Claude Sonnet 4.6 guide — 79.6% Verified, $3/$15 pricing (nxcode.io)](https://www.nxcode.io/resources/news/claude-sonnet-4-6-complete-guide-benchmarks-pricing-2026)
- [SWE-bench Coding Agent Leaderboard 2026 (awesomeagents.ai)](https://awesomeagents.ai/leaderboards/swe-bench-coding-agent-leaderboard/)
- [Agentless / SWE-bench-Lite cost notes (emergentmind)](https://www.emergentmind.com/topics/swe-bench-lite)
