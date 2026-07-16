# ExecuteSpec — GTM Strategy v2 (July 16, 2026)

**Supersedes `GTM-STRATEGY-2026-07.md` (v1).** v1 was written from the website + market research alone. This version is grounded in the full internal record: business docs, release-2.0 gate status, adversarial assessments, benchmark reason-logs, and competitive analysis. Where v1 and internal reality conflict, this document wins.

---

## 1. Ground truth (what changed my mind)

| v1 assumed | The docs say |
|---|---|
| Launch in weeks 3–6 | Release is **BLOCKED** (release-2.0-risk-readiness, 2026-07-16). Open P0s: cross-node recovery, replay parity, live visual/UX signoff, prod DB migration. Independent principal-engineer verdict (2026-07-01): "do not buy yet for team-wide rollout." |
| "Verified score" is the wedge | Internal audit (2026-07-13): the score is "a harness score, not a reliable quality score." Three inflation paths found and patched; rule on record: don't call a run correct from verifiedScore alone. |
| Benchmarks pending but promising | Sealed shared-30 comparator: **ExecuteSpec 13/30 vs Claude Code 20/30 vs Codex 16/30** — lowest of the three, non-publishable. Old 59%/52% and 7%→67% numbers formally invalidated. |
| Product works end-to-end | The one authenticated web run in the 2026-07-01 adversarial eval scored 43/100 (scaffold files only, worker errored at iteration 1). CLI extreme QA strict score 0/100 (69 failed assertions). Tier UX renders paid Team orgs as personal workspaces. |
| Enterprise later, capture inbound | No SOC 2, no working SAML, no SCIM, app-layer-only tenant isolation. Enterprise isn't "later," it's off the table until these exist. |
| — (unknown to v1) | Real progress exists: SWE-bench Pro sample-20 **60%** after harness fixes (k=1, DeepSeek, official Scale scorer, non-publishable until full-731); Terminal-Bench 2.1 full-89 **61.8% strict**; 2,372/2,372 backend tests green; press kit + PH kit + ad budgets already prepared (2026-07-13). |

Two structural observations:

**Marketing is ahead of engineering evidence.** Press kit, PH launch kit, ad budgets, launch-day targets — all built, all dated July, all waiting on a product whose own evidence ledger says "not yet." The discipline that stamps every benchmark "non-publishable" is right; the risk is the reflex to compensate by preparing more launch assets instead of closing the evidence gap.

**The internal release bar and the customer-contact bar are different bars.** The 2.0 gate (1,120-cell adversarial matrix, 8 personas, cross-node recovery) is an engineering-release bar — appropriate for public launch claims. It is NOT the bar for putting the product in front of 10 consenting design partners who are told exactly what's proven and what isn't. Conflating the two means zero external contact until an unbounded date. The company dies of no-users before it dies of an unfixed P0.

## 2. Strategic verdict

**Do not launch publicly now. Do not wait for the full gate either. Run two tracks:**

- **Track A (now): private design-partner motion.** 5–10 partners, scoped to what the principal-engineer verdict itself endorses — the local CLI/TUI lane plus BUILD-mode runs on real but non-critical repos, with honest labeling. Goal: first external usage evidence, activation data, and testimonials-in-waiting.
- **Track B (parallel): evidence lane.** Close the *customer-relevant* subset of P0s and buy the benchmark number. Public launch happens when Track B's three proofs exist — not before, and not later.

### The three proofs that gate public launch

1. **A publishable benchmark number.** Full SWE-bench Pro 731 (or Lite 300 / Verified 500 per benchmark-strategy.md, ~$450–$750, official scorer, trajectories published, k≥3 where feasible). Without this, the "verified" positioning loses its first public argument. The 60% sample-20 says this is worth funding immediately — it's the cheapest highest-leverage marketing spend available, far better than the planned $155/day ad budget.
2. **A clean end-to-end web run, repeatedly.** The 43/100 adversarial run and raw provider JSON surfacing to users are launch-killers regardless of P0 status. Signup → spec → plan gate → PR → report must work 20 times in a row on fresh accounts across three providers, witnessed and recorded.
3. **Score honesty shipped as product.** The evidence ledger (`verificationEvidence[]`) already labels deterministic vs LLM-judged claims — surface it as the hero, and rename the pitch from "verified score" to "evidence, summarized by a score." That converts the internal audit's criticism into the differentiator: nobody else shows their work at all.

Explicitly NOT launch-gating (engineering may disagree; this is the GTM view): cross-JVM provider-call recovery (document it as a known limitation — design partners will accept "a crash mid-call may retry" if told), SOTA comparator parity (never lead with resolve-rate comparisons anyway — see §3), FastContext, subagent quality uplift, semantic index.

## 3. Positioning v2

Internal docs already converged on the right line — keep it: **"AI that ships pull requests your team actually merges."** Complement, not superiority. The adjustments:

**Never compete on resolve rate.** Your own comparator says Claude Code wins head-to-head on the same model. The honest and strategically correct frame, already in COMPARISON.md: single-loop harnesses extract more from one model; ExecuteSpec's value is the layer no CLI has — plan gate with cost estimate, multi-repo PRs, evidence trail, team governance, per-task multi-provider routing, BYOK economics. "Claude Code in your terminal. ExecuteSpec in your browser, your team's audit log, your customer's PR" is the sentence; use it everywhere.

**Lead with the gate and the ledger, not the score.** The plan-approval gate ("you see the plan and the price before a token is spent") and the token ledger are deterministic, demonstrable today, and unique. The score is the summary at the end of the evidence, not the proof. This survives hostile HN scrutiny; "trust our 0–100" does not.

**The honesty brand is the moat — make it public.** The reason-log culture (publishing discarded oracle-mode numbers, stamping results non-publishable, "we cite real cost data including when unflattering") is the most differentiated marketing asset the company owns, because it can't be faked quickly. Engineering-transparency posts drawn from the reason-logs are the content engine. The launch story writes itself: "We benchmarked ourselves against Claude Code and lost. Here's what we built instead, and why the losing number was the wrong metric."

**ICP (unchanged from internal docs, now with conviction):** teams of 2–20 already using AI coding tools whose bottleneck is PR review and cost opacity. Land on Team BYOK (margin-safe, zero-markup story) with Pro as the solo on-ramp. Agencies second. Enterprise: capture inbound, sell nothing until SAML + SOC 2 exist.

## 4. Track A — design-partner motion (start this week)

- **Offer:** free Pro/BYOK access + founder-level support for 60 days, in exchange for weekly 20-minute feedback calls and permission to publish anonymized results. Scope stated in writing: what's proven (deterministic gates, cost ledger, test execution), what isn't (cross-node recovery, provider-quality parity), what's known-broken (per the assessments).
- **Sourcing:** your network first, then the concierge motion from v1 — "send me one real spec, I'll return a PR + evidence report" — via personalized LinkedIn/email at 10–15/week. This works pre-launch; no public claims needed.
- **Who runs the product during trials:** you watch every run. Each failure becomes either a fix or a reason-log entry. Ten partners × real repos is worth more than the entire 1,120-cell matrix for finding what actually breaks buyers.
- **Success bar (day 45):** ≥5 partners with ≥3 completed runs each, ≥1 merged PR per partner, and the funnel instrumented (signup → first run → plan approved → PR merged). If fewer than 3 partners reach a merged PR, the problem is product, not marketing — stop recruiting and fix.

## 5. Track B — evidence lane (parallel)

Priority order, GTM view:

1. Fund the full SWE-bench Pro run on the dedicated VM (owed per RL-2026-07-002). Budget ≤$800. If ≥50% holds at 731 with official scorer, that's a publishable, differentiated number (Pro is the harder, less-contaminated set — better story than a crowded Lite leaderboard).
2. The 20-consecutive-clean-runs proof (web, three providers), plus kill the raw-provider-JSON-to-user bug and the Team-org-renders-as-personal UX bug — these two are buyer-trust killers named in your own assessments.
3. Ship the evidence-ledger-first report UI (rename/reframe per §3).
4. Recompute unit economics from live `run_step_usage` ledgers — the only margin math on file is from the dead April pricing model and is explicitly unusable. Needed before founder-pricing discounts (PH kit's 50%-off-lifetime for 100 Pro users) can be safely honored.
5. Production Cloud SQL migration rehearsal (RR-06) — the one internal P0 that is also a customer-facing catastrophe if wrong.

## 6. Launch (when the three proofs exist)

The prepared assets are good — hold them, don't rewrite them. Sequence per v1 (Show HN centerpiece, Dev Hunt, PH as echo; Tue/Wed PT), with one change: the launch post is the benchmark-honesty story (§3), not a product announcement. Targets stay as the PH kit defines (day-0: 200 signups, 10 Pro; day-30: 2,000 signups, 150 Pro, $4,500 MRR) but are now conditioned on the design-partner activation data — if Track A shows activation <20%, launch waits regardless of proofs.

Pricing: unchanged (Free 200 credits/5 runs, Pro $20, Pro+ hidden, BYOK $50, Team $50/seat, Enterprise custom). Two flags before launch day: verify the Stripe top-up path actually works (currently routes only to Razorpay per billing assessment), and reconcile the PH kit's "1 credit ≈ $5" line with the canonical $1.25 = 100 credits before anyone screenshots it.

## 7. What to stop, freeze, or ice

- **Stop:** ad spend plans ($155/day across Google/Meta/LinkedIn) — nothing to send traffic to yet; redirect the first $800 to the benchmark run. Feature expansion (DevOps agent / SRE / data-pipeline roadmap) — investor-deck material only, zero build hours until PMF signal.
- **Freeze:** PH/press kits (done, waiting), enterprise outbound (until SAML+SOC 2), the 50%-lifetime founder discount (until unit economics recomputed).
- **Ice:** Gemini in all copy (done in the July 9 site pass), any resolve-rate comparison vs Claude Code/Codex, any use of the invalidated 59%/52%/67% numbers, "verified" as a standalone adjective without the evidence ledger behind it.

## 8. Operating rhythm (revised)

| Cadence | Claude | Navneet |
|---|---|---|
| Daily | Monitor design-partner runs/funnel, triage inbound, draft partner follow-ups | Watch partner runs; 30 min community presence |
| Weekly | 1 reason-log-derived engineering post (draft), partner-recruit shortlist + drafts (10–15), funnel + evidence-lane status report | Approve/send everything, 2+ partner calls, decide fix-vs-log on failures |
| Biweekly | Track B checklist review against §5 | Go/no-go on next evidence-lane spend |

Same ground rules: every outbound is approved by you; your voice on HN/calls/LinkedIn is yours, not mine; no public claim the evidence ledger can't back.

## 9. The one-sentence version

Get ten real teams merging PRs privately while you buy one defensible benchmark number and twenty clean runs in a row — then launch with the honesty story, because it's the only claim in this market nobody can copy in a quarter.

---

## 10. Model-strategy addendum (v2.1 DRAFT, 2026-07-16 — pending eval details)

New inputs since v2: (a) k=5 public Harbor run — ExecuteSpec harness + DeepSeek V4 Pro at **0.59 avg reward on TB 2.1** ($24.08 total vs $134–$2,059 for leaderboard entries; ≈ Claude Code + GLM-5.1 at 58.7%); (b) fine-tuned **Gemma 4 12B** claimed near Haiku 4.5 — intended free-tier model; (c) **software-engineering world model** (~3 weeks out) — beat Fable 5, GPT-5.6 et al. at CI-outcome prediction on 200+ time-split open PRs by a 30% margin, ground truth = real CI outcomes, Fable 5 used only for label verification, data in GCS.

### USP reframe (draft)

From "verified PRs" to: **the first software-engineering system with a predictive world model — it estimates what your CI will say before the code is written, then proves it after.**

Architecture story: **SLM (tuned Gemma) + world model + ES harness.** The three legs reinforce: the harness extracts frontier-adjacent reliability from small models (TB evidence); the world model makes verification *predictive* (pass-probability at the plan gate, skip-doomed-runs routing, cheaper continuation decisions); owned weights make the free tier near-zero marginal cost. Competing on "software engineering" (predict → plan → execute → verify → maintain), not codegen tokens — the one axis where frontier labs' own economics (sell more tokens) work against them.

This *strengthens* rather than replaces the wedge: plan gate + evidence ledger + BYOK stay; the world model becomes the reason the gate is smart, not just safe.

### Claim gates (nothing ships in marketing until its gate passes)

| Claim | Gate |
|---|---|
| TB 2.1 59% @ $24 | Submit to official leaderboard (PR to harbor-framework/terminal-bench-2-1); claim only after TB-team verification. Reconcile 59 (k=5) vs 62 (single run) — the k=5 number is the claim. |
| World model beats frontier at CI prediction | Scale eval to 1,000+ PRs, time-split, contamination-documented, CIs published, metric defined (state whether "30%" is relative error reduction or absolute); publish technical report + eval dataset. |
| Gemma 4 12B ≈ Haiku 4.5 | Named public benchmark, k≥3, same-harness comparison; also verify Gemma license terms for commercial serving. |
| "Beats X at software engineering" | Never as a blanket claim — always per-benchmark, per-metric, with cost attached. |

### Sequencing impact

The world-model technical report (if the scaled eval holds) becomes the **launch centerpiece** — a research artifact, not a product ad: HN-native, investor-legible, and un-copyable in a quarter. Design-partner motion (§4) unchanged and still starts now; partners become the first users of pass-prediction at the plan gate. Free-tier switch to Gemma gated on serving-economics draft + license check + quality gate.

Companion docs: `WORLD-MODEL-TECH-REPORT-OUTLINE.md`, `FREE-TIER-ECONOMICS-DRAFT.md`.
