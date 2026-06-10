# 07 — Product & positioning: honest assessment

> Inputs: the live product walked end-to-end (checkpoints/ux-live-notes.md), the code-verified
> feature surface (batch checkpoints), tier/pricing config (application.yml), your own benchmark
> data. You said PMF isn't the worry (1 proven customer + pipeline) — so this focuses on
> coherence, surface discipline, and the story you can defend.

## 1. What the product actually is (code-verified)

One loop, genuinely working end-to-end: **spec in plain language → classify → plan → human
approves at a gate → sequential tier-routed execution with build/test smoke → reviewer passes →
deterministic verified score → PR with the score attached.** Plus four autofix on-ramps (PR
review comments, tech-debt categories, CI failures, code-review severities) that feed the same
PATCH pipeline, a project workbench (codemaps, architecture docs, RAG, memory, chat), previews,
and a benchmark layer that runs the REAL pipeline on TB/SWE-bench.

That loop is real, differentiated, and demonstrably cheap to run (your $0.06 palindrome run; TB
60% at $0.40). The verified score — additive, measured-signals-only, honest about what wasn't
measured — is the single most defensible product idea here.

## 2. The core product problem: surface area vs. depth

The codebase is ~168K LOC across 75 packages serving ~450 endpoints — and the review found a
large fraction of it dormant, flag-gated OFF, or unreachable:

- A whole V3 multi-solution execution lane (~5K LOC) deliberately unrouted in the UI.
- Enterprise-readiness surfaces (contracts drift, standards, ADRs, compliance, governance,
  readymade catalog, landing analytics) — substantial code with little or no live consumption
  (per-package verdicts in checkpoints/be-governance.md, be-api-platform.md).
- ~20 default-OFF feature flags whose code paths each carry maintenance cost (V216-V235 family).
- Frontend audit history shows ~half the API client functions unwired from any surface.
- Dead subsystems actively burning cycles (a status poller hitting three vendor status pages
  every 60s into Redis keys nobody reads).

For a one-founder company with one proven customer, this is the strategic risk — not PMF. Every
dormant subsystem taxes: compile time, review surface, doc drift, onboarding, and your own
attention (this review needed 19 agents to cover it). **The product is a sharp 20K-LOC loop
wearing a 150K-LOC coat.**

Recommendation: declare an explicit product kernel (run loop + scoring + autofix + project
workbench + benchmark layer + billing/auth), and move everything else to one of: DELETE (dead by
grep), FREEZE (keep compiling, no maintenance, no docs), or ROADMAP (re-enter via the flag with a
customer attached). The doc-06 cleanup list operationalizes this.

## 3. Positioning

### Where you sit in the market

The agentic-coding space splits into: IDE copilots (Cursor/Copilot), autonomous engineers
(Devin-class), CLI agents (Claude Code/Codex-class), and CI-attached fixers (Sweep-class
ancestry). ExecuteSpec's actual differentiators against all four:

1. **The plan gate before any token spend** — procurement-friendly, budget-deterministic.
2. **The deterministic verified score on every PR** — nobody else hands reviewers a
   trust-calibration number.
3. **Cost-proportional credits with a user-settable cap + BYOK** — spend is a first-class,
   bounded quantity, not a subscription mystery.
4. **Cheap-model economics with flagship escalation** (doc 08) — once the matrix expresses it,
   you can sell "agentic PRs at 1/10th the token cost, scored so you know which ones to trust."

That combination — *governed, scored, cost-bounded agentic PRs* — is a real position. Notably it
is a **B2B/team** position: every differentiator speaks to an engineering manager or platform
lead, not a solo hacker.

### What to sharpen

- **Lead with the score + the gate, not the IDE.** The current web app cosplays VS Code while
  the actual value is mission-control (approve plans, read verdicts, track spend). The Console
  chrome direction (doc 11) matches the positioning; the IDE chrome competes with the customer's
  actual IDE — a fight you can't win and don't need.
- **"Verified score" needs to be bulletproof before it's the headline.** The review found the
  scoring path degrading silently (real-test-exec falls back to LLM-judged for most JVM projects;
  backfill recomputes with a different formula; coverage-level case drift disables anti-gaming
  guards). If a customer ever catches the score being soft, the brand is dead. Fix-first list in
  doc 09 §2.5 + checkpoints/be-router-cost.md BUG-3/4/5.
- **Benchmark transparency as marketing.** You're building in-app benchmark tracking anyway —
  publish it. "Here is our TB/SWE-bench standing per model, measured blind through the production
  pipeline, with costs" is content nobody in your bracket publishes honestly. The cheap-model
  angle gives it a unique hook: a public $/resolved-task leaderboard.
- **Kill insider language from public surfaces**: "BYOK bypasses the cost cap" on the login page,
  "Default (org / system matrix)" in compose, raw enums (CLEAN_COMPLETION) on results, provider
  ids as buttons. The product speaks fluent internal-jargon to strangers (full list in doc 11).

### Pricing coherence notes

- FREE at 5 runs/month (yml) vs the documented intent of 15 — whichever is real, the login page,
  pricing page, and enforcement need to agree (they currently don't all read the same source).
- TEAM at $39/seat BYOK-only from 1 seat is clean. But org-settings still shows "TEAM-tier orgs
  share a seat pool (min 2)" copy — stale vs the 1-seat reality (live-verified).
- The billing page contradicting the runs list ($0.00 vs $0.08 same account, live-verified) is a
  trust bug in the one place money is displayed; root cause in checkpoints/be-billing.md.

## 4. The one-customer reality check

With one proven customer: optimize for **referenceability, not breadth**. The strongest move this
quarter is not a new surface — it's (a) the scoring path made unbreakable, (b) the model matrix
made honest (docs 08/09/10), (c) the TB/SWE sweep published, (d) the kernel/coat split executed
so the next contributor (human or agent) ships features instead of archaeology. Everything in
this review converges on those four.
