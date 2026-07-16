# CommerceIQ Pilot — Strategy + Numbers (2026-07-16)

Two parts: **A** = internal playbook (not shared), **B** = the shareable proposal content (copy into email/deck/one-pager after your review). Defaults are marked [DECIDE] where you should confirm before sending.

---

# Part A — Internal playbook

## Why this is the right first partner

Warm double-intro (HR head + your former manager as eng head), a named pain that matches our sharpest wedge (token-billing opacity — the market-wide backlash pattern is documented in `COMPETITIVE-ANALYSIS-2026-07.md` §4), India+US engineering org where INR-denominated, DeepSeek-routed economics land hardest, and a co-build offer that matches our design-partner motion (GTM v2 §4) exactly.

## What we want out of it (in order)

1. **Evidence:** 2–3 teams running real specs weekly; activation + merged-PR data; a public case study with numbers.
2. **Co-build input:** their token-billing pain shapes the cost-governance surface (org caps, approval thresholds, per-team budgets — already built, needs a real org to harden against).
3. **Revenue later, not now:** free pilot converts to Team BYOK. Don't price-negotiate during the pilot; anchor the post-pilot price in the proposal so it's not a surprise.

## The sequence

1. **HR head follow-up (this week):** short note she can forward — the Part B one-pager, not a deck. Make her look good for brokering it.
2. **Eng-head call (you, 30 min):** former-manager dynamics — this is a peer conversation, not a pitch. Lead with the honest frame: "pre-launch, I want 3 design partners, you know how I build." Demo one live run end-to-end (spec → plan gate with cost estimate → PR + evidence report). Rehearse this demo until it's boring.
3. **Technical validation (their pick of 1–2 senior engineers):** give them the sandbox + one concierge run on a real-but-non-critical repo of theirs.
4. **Pilot agreement (1 page, not legal-heavy):** scope, duration, success criteria, data handling, exit.

## Honest-disclosure card (say these before they find them)

- Pre-launch; they'd be among the first external orgs. Founder-level support (you + me) throughout.
- **No SOC 2 yet** (process starting); no SAML. Mitigation: pilot on non-sensitive repos, their own DeepSeek/Anthropic keys (BYOK = code never billed through us; workspace wiped at run end), DPA available, security overview doc on request.
- Provider-quality parity across models is not a claim we make; DeepSeek routes are our most-tested lane (which is also the cheap lane — convenient honesty).
- Known limitation: a crash mid-provider-call may retry; cross-node recovery is roadmap.

## Risks

- **Procurement/security review ambush:** HR-initiated pilots can die in security review. Preempt: ask the eng head directly on the first call "what does your security team need for a scoped pilot on non-prod repos?"
- **Free-pilot drift:** no success criteria = eternal pilot. The agreement has a 60-day clock and a named decision meeting.
- **Champion risk:** your ex-manager's sponsorship is the asset; if he delegates to a skeptical lead, the pilot stalls. Ask him to name a DRI who *wants* this.

---

# Part B — Shareable proposal content

## ExecuteSpec × CommerceIQ — 60-day engineering pilot + co-build

**What ExecuteSpec is:** a spec-driven delivery pipeline. An engineer writes a plain-English spec; ExecuteSpec plans it, shows the plan **with a cost estimate you approve before any tokens are spent**, executes across one or more repos, runs your build and tests, reviews the change, and opens a pull request with a verification report attached. You merge.

### Why we're bringing this to CommerceIQ first

Your teams already use AI coding tools; the industry-wide problem is that their bills are unpredictable and their output still consumes senior-engineer review time. ExecuteSpec was built around three answers:

1. **Price before spend.** Every run shows an estimated cost at the plan gate. Nothing executes until a human approves the plan and the price.
2. **Failed calls aren't charged.** If a provider call fails, it costs you nothing.
3. **Zero markup on tokens.** On the Team plan you bring your own provider keys and pay providers directly at list price. We charge per seat, never per token.

### The numbers (DeepSeek-routed, our most-tested lane)

| Item | Number | Basis |
|---|---|---|
| Cost per agent task, terminal benchmark | **$0.054 avg/trial** | Terminal-Bench 2.1, 445 trials (k=5), $24.08 total, public Harbor job — link on request |
| Benchmark result behind that cost | 0.59 avg reward — comparable to Claude Code driving GLM-5.1 (58.7%) on the official leaderboard | [tbench.ai leaderboard](https://www.tbench.ai/leaderboard/terminal-bench/2.1); leaderboard runs typically cost $134–$2,059 vs our $24 |
| Typical small feature/fix run | **~$0.05–0.15** | DeepSeek V4 list: $0.435/M input (cache-miss), $0.87/M output; heavy cache reuse |
| Medium multi-file task | **~$0.30–0.60** (estimate; pilot will produce your real distribution) | internal cost ledgers; every run itemized |
| Reference point: same class of task on Devin | ~$4.50–6.75 (2–3 ACUs) | Devin's published ACU model |
| Reference point: seat cost elsewhere | Cursor Teams $40/seat + metered overages; Claude Code Max $100–200/seat with weekly caps | public pricing pages, July 2026 |

**Illustrative pilot-scale math:** 10 engineers × 20 runs/mo at a 70/30 small/medium mix ≈ **$25–50/mo total model spend** — on your own DeepSeek key, itemized per run, per team, per engineer. Every run produces a token ledger (estimate → live meter → final provider-cost ledger) your finance team can audit.

### Pilot structure [DECIDE — defaults below]

- **Scope:** 2 teams, 5–10 engineers, 2–3 non-critical-path repos (any mix of Java/TS/Python/Go).
- **Duration:** 60 days, free. Founder-level support; <24h turnaround on issues.
- **Your keys:** runs execute on CommerceIQ's own DeepSeek (and optionally Anthropic) API keys — code and spend stay under your control; workspaces are wiped at run end; we never train on your code.
- **Success criteria (decided together at kickoff, measured weekly):** e.g., ≥30 completed runs, ≥15 merged PRs, median cost/merged-PR under an agreed threshold, engineer satisfaction ≥7/10.
- **Co-build:** weekly 30-min feedback loop; CommerceIQ's cost-governance requirements (org caps, per-team budgets, approval thresholds) directly shape the roadmap; early access to everything we ship during the pilot.
- **After 60 days:** adopt on Team plan at **$50/seat/month + your own provider spend (zero markup)** — or walk away, with a data export. Design-partner pricing lock for 12 months [DECIDE: offer 20–30% founding-partner discount?].
- **What we ask in return:** an anonymized-or-named case study with real numbers, and honest feedback.

### What we are and aren't claiming

We're pre-launch and we treat evidence carefully: benchmark claims above are k=5 public runs you can inspect; anything we haven't proven (cross-model parity, enterprise SSO, SOC 2 — in progress) we'll tell you before you ask. The product's core promise — plan-gated, cost-transparent, test-verified PRs — is what the pilot exists to prove on your codebase, at your task mix, on your keys.

---

*Contact: Navneet Prabhakar · navneet.prabhakar007@gmail.com · executespec.ai*
