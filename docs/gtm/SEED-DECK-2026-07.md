# ExecuteSpec — Seed Deck v1 (DRAFT, 2026-07-16)

Target: $1–2M seed, India angels + US funds. 13 slides. Each slide = headline, content, and *(notes)* for delivery. Claims follow the internal discipline doc — items marked ⛔ are gated on evidence not yet sealed and MUST be updated or cut before the deck is shown.

---

## Slide 1 — Title

**ExecuteSpec**
**We predict what your CI will say before the code is written — then prove it after.**

Spec in → plan approved with a price → verified, test-backed PR out.
executespec.ai · Navneet Prabhakar, Founder

*(Notes: the one-liner is the world-model claim fused with the product. If the scaled world-model eval isn't sealed by pitch time, fall back to: "AI that ships pull requests your team actually merges — at 1/20th the cost.")*

## Slide 2 — Problem: the trust and billing crisis in AI coding

- 96% of developers don't fully trust AI-generated code; only 48% verify it (Sonar, 1,100+ devs, Jan 2026)
- Review time rises 91% when teams adopt AI coding; devs now spend ~24% of the week verifying AI output
- Billing is the other wound: Cursor issued public refunds after its credit switch; Replit users hit "$20 plan → $350/day"; Copilot's own community calls its credits "a scam"
- Net effect: engineering orgs are buying code generators and inheriting **verification debt + unbudgetable bills**

*(Notes: every claim here is third-party sourced — this slide should feel like the investor's own portfolio companies complaining.)*

## Slide 3 — Product: a delivery pipeline, not a copilot

1. Engineer writes a plain-English spec
2. System plans it and shows a **cost estimate — human approves before a single token is spent**
3. Agents execute across one or many repos through controlled tools
4. Build + your test suite + reviewer passes run automatically
5. A real PR opens with a **verification report**: what was built, what passed, what it cost — every claim labeled deterministic vs LLM-judged
6. Failed LLM calls are never charged; BYOK = zero markup on tokens

*(Notes: live demo beats this slide — offer it in every meeting.)*

## Slide 4 — Proof: frontier-adjacent results at commodity cost

**Terminal-Bench 2.1, official dataset, k=5, public run:**

| | ExecuteSpec + DeepSeek | Claude Code + GLM-5.1 | Claude Code + Fable 5 (#1) |
|---|---|---|---|
| Score | **0.59** | 0.587 | 0.838 |
| Full-run cost | **$24** | $277 | $553 |

Our harness extracts near-identical performance from a model that costs $0.435/M tokens. Public artifact: hub.harborframework.com/jobs/b71b0ed4… ⛔ *upgrade to "official leaderboard entry" once the TB submission PR is verified*

*(Notes: the honest frame — we don't beat frontier stacks on raw score; we match mid-leaderboard entries at 1/10th–1/20th cost. That IS the enterprise pitch.)*

## Slide 5 — The breakthrough: a world model for software engineering ⛔

**We trained a model that predicts CI outcomes on real PRs — better than the frontier.**

- Task: given a PR, predict whether CI passes — ground truth = actual CI results
- Pilot: 200+ time-split open PRs (post-cutoff, contamination-controlled), labels dual-verified (Fable 5 + GPT-5.6 adversarial pass, audit trail preserved)
- Result: beat Fable 5, GPT-5.6 et al. by ~30% margin ⛔ *[exact metric + CIs from scaled 1,000-PR run — REQUIRED before showing this slide]*
- Why it matters: every competitor verifies *after* execution; we verify **before** — pass-probability at the plan gate, doomed runs never funded

*(Notes: this is the venture-scale slide. If the scaled eval isn't done, show the pilot as "early result, replication in progress" — never as a sealed claim.)*

## Slide 6 — Architecture: the three-layer system nobody else is building

**Tuned SLM (Gemma 4 12B) + SE world model + verification harness**

- The harness makes small models punch up (Slide 4 is the proof)
- The world model makes verification predictive, not forensic
- Owned SLM weights make the free tier near-zero marginal cost and the system immune to provider price shocks
- Competing on **software engineering** — predict, plan, execute, verify, maintain — not on codegen tokens, where frontier labs' economics (sell more tokens) work against them

*(Notes: this answers "what happens when OpenAI ships your feature" — their business model IS the vulnerability: every efficiency we ship cuts their revenue; every one they ship cuts their own.)*

## Slide 7 — Market: validated, enormous, and structurally open at our position

- Category validation: Cognition $492M ARR/$26B val; Cursor ~$4B annualized; Copilot in ~90% of F100
- **The unoccupied intersection** (verified across 11 competitors, July 2026): pre-spend price gate + failed-work-free billing + typed evidence ledger + zero-markup BYOK + multi-repo PRs — every competitor holds ≤2 of these
- Why it stays open: usage-metered incumbents can't copy it without cutting their own margins; autonomy-branded players (Devin) can't copy it without reversing their story
- Enterprise pull: Gartner top-10 2026 trend "digital provenance"; auditors now require AI-code audit trails; 92% of security leaders concerned about AI code

*(Notes: threat honesty if asked — AWS Kiro is closest, ~1–2 quarters if they prioritize it; our moat is speed + the world model they'd have to train from scratch.)*

## Slide 8 — Business model: margin-safe by construction

| Tier | Price | Role |
|---|---|---|
| Free | $0 · 200 credits | acquisition; served by owned SLM at near-zero marginal cost |
| Pro | $20/mo | solo monetization, managed keys |
| BYOK | $50/mo | solo power users, zero markup |
| **Team** | **$50/seat/mo** | **the wedge: BYOK, zero token markup — provider spend lives on customer keys** |
| Enterprise | Custom | governance, model matrix, audit — post-SOC 2 |

We never resell tokens at markup → gross margin doesn't degrade with usage. Illustrative: 10-engineer team ≈ $500/mo platform fee + ~$25–50/mo of their own DeepSeek spend.

## Slide 9 — Go-to-market: pilot-led, evidence-armed

- **Now:** design-partner motion — CommerceIQ pilot in motion (Head of Eng + CEO engaged); target 5–10 partners in 90 days via concierge offer ("send one real spec, get a PR + evidence report")
- **Next:** public launch anchored on the world-model technical report + open dataset (research-led launch, HN-native) + official TB leaderboard entry
- **Then:** Cursor/Copilot billing refugees (documented, targetable communities) + agencies (verification report = client deliverable)
- Enterprise inbound captured, sold only after SOC 2/SAML (funded by this round)

## Slide 10 — Traction & pipeline (honest, pre-launch)

- Product: ~2 years of build; run pipeline, multi-repo PRs, billing, BYOK, CLI + VS Code clients shipped; 2,372/2,372 backend tests green
- Benchmarks: public k=5 TB run (Slide 4); SWE-bench Pro sample-20 at 60% (internal, full-731 run scheduled) ⛔ *update when run*
- World model pilot result (Slide 5) ⛔
- Pipeline: CommerceIQ pilot conversation active (eng head + CEO); [add partners as they land]

*(Notes: zero revenue, zero users today — say it before they ask, then point at the 90-day partner plan. Investors forgive pre-launch; they don't forgive hiding it.)*

## Slide 11 — Roadmap (18 months)

- **Q3 '26:** world-model report + dataset published; CIQ pilot converts; 5–10 design partners; official TB entry; SOC 2 process starts
- **Q4 '26:** public launch (research-led); Gemma-served free tier; first 100 paying seats
- **H1 '27:** world model in-product (pass-probability plan gates, risk-priced runs); enterprise tier opens with SOC 2; foundry expansion #1 (DevOps/governance agent — closest adjacency)
- North star: **$1M ARR run-rate by mid-'27** ⛔ *[pressure-test this number with use-of-funds model before pitching]*

## Slide 12 — Team

**Navneet Prabhakar** — solo technical founder. Built the entire system: pipeline, 76-package backend, multi-provider router, benchmark harnesses, billing, clients. Ex-CommerceIQ [add prior roles].

- Solo-founder answer, delivered head-on: the build velocity above *is* the evidence; this round hires 2 founding engineers (inference/infra + product) and buys GPU budget
- Advisors: [Guru Hariharan — pending] [add as they commit]

*(Notes: US funds will push hardest here. The counter is specific: name the two hires, show the hiring pipeline exists.)*

## Slide 13 — The ask

**Raising $1.5M seed** (SAFE) ⛔ *[final number after use-of-funds model]*

| Use | ~% |
|---|---|
| Founding engineers ×2 (India) | 40% |
| GPU/training (world model scaling + SLM serving) | 25% |
| SOC 2 + enterprise readiness | 10% |
| GTM (benchmarks, launch, partner support) | 10% |
| Founder + runway buffer → 18 months | 15% |

**Milestones this buys:** published world-model report · public launch · 10 design partners → 100 paying seats · SOC 2 Type I · full SWE-bench Pro + official TB entries

---

## Appendix slides (build on demand)
A1: Verification report walkthrough (screenshot) · A2: Competitive gap map (full table from COMPETITIVE-ANALYSIS-2026-07) · A3: World-model eval methodology · A4: Unit economics detail · A5: Security architecture · A6: Expansion roadmap (foundry: DevOps → SRE → data pipelines)

## Pre-flight checklist (before ANY investor sees this)
1. ⛔ Slide 5 metric + scaled-run status — the deck's credibility lives here
2. ⛔ TB leaderboard submission status (changes Slide 4 wording)
3. ⛔ $1.5M and $1M-ARR numbers validated against a real use-of-funds model
4. Fill team slide gaps; confirm Guru's advisor status only with his explicit consent
5. Never present: SOC 2 certified, SAML enforced, leaderboard rank (until real), "beats GPT-5.6 at software engineering" as a blanket claim
