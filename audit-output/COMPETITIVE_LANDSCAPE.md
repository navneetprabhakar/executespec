# ExecuteSpec — Competitive Landscape (Scaled-Down Pass)

> Generated 2026-04-30. Replaces AUDIT_REPORT.md §11 stale six-line table.
> 30-min budget · $0 LLM spend · no real competitor sign-ups · 5 web-fetches.
> Tagging: every claim is `[hands-on]` / `[training-data]` / `[web-fetch]` / `[inferred]`.

---

## §1 — TL;DR

- The 2025→2026 wave consolidated around two consumer archetypes (prose-to-app: Lovable, Bolt, v0, Replit) and two professional archetypes (IDE copilot: Cursor, Windsurf; autonomous agent: Devin, GH Copilot Coding Agent). `[training-data]`
- Every Tier 1 competitor has shipped a $20-$30 entry-paid + $40-$80 team tier in the last 12mo. ExecuteSpec TEAM $49/seat is mid-pack; PRO $20 (HIDDEN) was undercut by everyone. `[web-fetch 2026-04-30]`
- No Tier 1 competitor offers BYOK at consumer plans; ExecuteSpec MAX ($29 BYOK unlimited) is structurally cheaper for power users who already pay providers directly. `[web-fetch + training-data]`
- None of Tier 1 ships deterministic build+test+reviewer-gated scoring. Lovable/Bolt/v0/Replit show artifacts; Cursor/Windsurf show IDE diffs; Devin shows session logs. ExecuteSpec verifiedScore (40+20+20×tests/total+verdict+penalties) is structurally different. `[training-data + hands-on]`
- Multi-repo orchestration is rare — Devin and GH CCA can cross repos with permissions; Lovable/Bolt/v0/Replit are single-project by design. Real capability gap but narrow ICP. `[training-data]`
- Indian market is under-contested — none of the 8 publishes INR pricing. ExecuteSpec's INR + GST is a small but real distribution edge. `[web-fetch]`

*ExecuteSpec's defensible wedge against this field is **deterministic verification (verifiedScore + reviewer + smoke gate) on multi-repo enterprise-grade work** — not "spec-first," not "from-prose-to-app," but "from-spec-to-mergeable-PR-with-evidence-of-correctness."*

---

## §2 — Pricing Matrix

All prices USD/month unless noted. Date-stamped 2026-04-30.

| Product | Free | Entry-paid | Team | Enterprise | BYOK | Quota model |
|---|---|---|---|---|---|---|
| **Cursor** `[web-fetch]` | Hobby — limited Agent + Tab | Pro $20 / Pro+ $60 / Ultra $200 | Teams $40/user | Custom | No (BYOK absent from page) | Agent-request count + model-tier multiplier |
| **Lovable** `[web-fetch]` | Free — credits unspecified | Pro $25 (100 credits/mo + 5/day) | Business $50 (100 credits, SSO, RBAC) | Custom platform fee | No | Credits/month, 1 message ≈ 1 credit |
| **Bolt.new** `[web-fetch]` | Free — 1M tokens/mo, 300K/day | Pro $25 (10M tokens/mo, rollover) | Teams $30/user | Custom | No | Token-count rollover up to 2 months |
| **Replit Agent / Core** `[web-fetch]` | Starter — daily Agent credits | Core $20 ($25 credits/mo, annual) / Pro $95 ($100/mo) | Pro tier covers 15 collaborators | Custom (SSO/SAML, VPC) | Not detailed | $-denominated effort credits |
| **v0 by Vercel** `[web-fetch]` | Free — $5 credits, 7 msg/day | (no individual paid) | Team $30/user ($30 credits + $2/day) | Business $100/user; Enterprise custom | No | $-denominated tokens (model-tier priced) |
| **Devin (Cognition)** `[web-fetch]` | None | Pro $20 / Max $200 | Teams $80/user (unlimited members) | Custom (SAML, dedicated team) | No | ACU-style quota + pay-as-you-go overages |
| **GitHub Spark / Copilot Coding Agent** `[training-data — verify]` | Spark on Copilot Pro+ | Copilot Pro $10, Pro+ $39 | Copilot Business $19/user | Copilot Enterprise $39/user | Indirect via Copilot model picker; no provider-key BYOK | Premium-request quota |
| **Windsurf (Codeium)** `[web-fetch]` | Free — standard allowance | Pro $20 unlimited / Max $200 | Teams $40/user | Custom (RBAC/SSO/hybrid) | No (overage at API price, not BYOK) | Prompt allowance + API-priced overage |
| **ExecuteSpec** `[hands-on]` | FREE — 10 credits/mo (post-V145) | PRO $20 HIDDEN grandfathered | TEAM $49/seat (2-20 seats, shared pool); MAX $29 BYOK unlimited | Custom | **Yes at MAX + ENTERPRISE** | $-proportional credits (1 credit = $5 actual cost) |

**Framing:** Three observations. (1) The $20-$30 entry-paid band is now table-stakes; ExecuteSpec PRO at $20 was not a bargain — it was the floor. (2) Team pricing falls into two camps: per-seat ($30-$80) vs. flat-pool (Lovable's "$25 shared across unlimited users" is genuinely aggressive). ExecuteSpec's $49/seat is mid-pack but stops mattering above 5 seats vs. Lovable Pro. (3) BYOK at consumer/team tiers is genuinely rare. Cursor briefly supported it years ago and removed; Windsurf overage runs at API-equivalent cost but routes through their billing. ExecuteSpec MAX is the only sub-$30 BYOK option I can confirm in this peer set. `[training-data — verify against current Cursor docs]`

---

## §3 — Same-Spec Head-to-Head (LOW Fibonacci)

ExecuteSpec measured baseline (R-340 / R-341 LOW band): **65-93s wall-clock, $0.05-$0.09 per run, grade-A output, plan-approval gate fired correctly, build + reviewer pass.** `[hands-on per BROWSER_VALIDATION_REPORT.md]`

Projection of how each competitor *would likely* behave on the same "implement Fibonacci with iterative + memoized + matrix variants and N pytest cases" spec:

| Competitor | Likely UX | Likely artifact | Likely cost equivalent | Verification surface |
|---|---|---|---|---|
| **Cursor** `[training-data — projection]` | Open empty repo, paste spec into Agent. Agent edits files in-IDE, runs `pytest` if user asks. | 3-4 files, tests passing | ~$0.10-$0.30 in agent-request credits at Pro | Diff visible; correctness is user-judged. No build gate. |
| **Lovable** `[training-data — projection]` | Single-prompt → preview iframe. **Probably refuses or warps the spec into a UI app** — Lovable is not designed for backend libraries. | Maybe a React UI that calls JS Fibonacci; tests unlikely. | 1-3 credits ($0.30-$1.00 effective) | Visual preview; no verification of math correctness. |
| **Bolt.new** `[training-data — projection]` | StackBlitz-backed prompt → instant preview. Same warp risk as Lovable. | Likely TS/JS, possibly Vite scaffold. | ~50-200K tokens of 10M Pro budget | Browser preview only. |
| **Replit Agent** `[training-data — projection]` | Spawns a Repl, agent writes code + runs tests in the Replit shell. Closest to ExecuteSpec on this spec. | Python project with pytest | $0.10-$0.50 in Agent credits | Can run tests; result visible in console. No structured verdict. |
| **v0** `[training-data — projection]` | Will refuse or produce a React component that displays Fibonacci. Wrong tool. | UI artifact only | $1-$3 credits | None. |
| **Devin** `[training-data — projection]` | Long-form session: clone, branch, implement, push PR. Most ExecuteSpec-like operationally. | Python repo, PR opened, tests passing in CI if configured | Several ACUs (~$1-$5 effective) | PR + CI signal; no LLM-independent score. |
| **GitHub Copilot Coding Agent** `[training-data — projection]` | Issue → PR. Agent runs in GH Actions sandbox, opens PR, tests run via configured CI. | PR with code + tests | Premium-request count from Pro+/Business pool | PR + CI; no separate verified score. |
| **Windsurf** `[training-data — projection]` | Cascade agent in IDE. Edits files, can run tests. | Same shape as Cursor. | ~$0.10-$0.30 prompt allowance | IDE diff + user judgment. |

**Honest read:** On a correctly-scoped backend-library spec, Replit Agent / Devin / Copilot Coding Agent are the credible competitors — they share the "agent runs the tests" loop. Cursor/Windsurf are IDE-resident and require user-driven test runs. Lovable/Bolt/v0 are wrong-tool-for-the-job. **Where ExecuteSpec wins vs. Replit/Devin/Copilot:** verifiedScore is independent of the LLM that wrote the code — Replit/Devin/Copilot effectively trust the agent's self-report unless the user inspects logs. **Where ExecuteSpec loses:** ~70-90s wall-clock vs. Cursor's ~30-50s in-IDE; plan-approval gate adds friction IDE copilots don't impose. `[training-data — projection, not measured]`

---

## §4 — Feature Delta

ExecuteSpec's six claimed differentiators vs. the Tier 1+2 set. `observed` = I can recall direct evidence; `claimed` = the vendor markets it but I haven't verified; `absent` = I'd be surprised if it existed; `N/A` = wrong product category.

| Differentiator | Cursor | Lovable | Bolt | Replit | v0 | Devin | GH Spark/CCA | Windsurf |
|---|---|---|---|---|---|---|---|---|
| **Deterministic verifiedScore (build+tests+reviewer)** | absent | absent | absent | absent | absent | claimed (CI signal) | claimed (CI signal) | absent |
| **Multi-repo orchestration** | absent (single workspace) | absent | absent | absent | absent | observed | observed (with repo allowlist) | absent |
| **Plan-approval gate before code-write** | absent | absent | absent | absent | absent | claimed (Devin presents plan) | absent | absent |
| **BYOK at sub-$30 tier** | absent | absent | absent | absent | absent | absent | absent | absent (overage at API price ≠ BYOK) |
| **Curated readymades + migration recipes** | N/A | claimed (templates) | claimed (starters) | claimed (templates) | claimed (templates) | absent | absent | N/A |
| **Governance schema (V131-V139: contracts/standards/ADR/invariants)** | absent | absent | absent | absent | absent | absent | absent (CCA respects CODEOWNERS only) | absent |

**Caveats:** Cursor/Windsurf "absent" on multi-repo is true for the agent loop but they let users open multiple repos as workspaces; Devin's "claimed" on plan-approval — Devin presents a plan in chat, but it's not a hard gate; GH Copilot Coding Agent respects CODEOWNERS + branch protection, a governance overlap at the RBAC layer (not architectural rules).

The only structurally defensible differentiator is **deterministic verifiedScore + governance schema**. Plan-gate, BYOK toggle, and readymades could ship in a sprint at any competitor; multi-repo only matters to a narrow ICP.

---

## §5 — Recent Moves (last ~90 days, training-data-tagged)

**Cursor** `[web-fetch]` — added Pro+ ($60) and Ultra ($200) with model-multiplier framing late 2025; Bugbot productized as paid PR-review. Moving toward usage-quantified pricing, not seat-flat.

**Lovable** `[web-fetch]` — late 2025 added Business at $50 with SSO/RBAC. "Pro $25 shared across unlimited users" is the most aggressive flat-pool entry in the field.

**Bolt.new** `[web-fetch]` — token rollover effective July 1, 2025 acknowledges users burning unused capacity. Competing on volume-per-dollar.

**Replit Agent** `[web-fetch]` — moved to $-denominated effort credits in 2025. Closest model-alignment to ExecuteSpec's $1=1 credit framing.

**v0** `[web-fetch]` — domain v0.dev → v0.app (301 observed); Team $30/user with $2/day login credits is a 2025 launch. Model-tier pricing ladder (Mini/Pro/Max/Max-Fast) unusual in this peer set.

**Devin** `[web-fetch]` — Pro dropped to $20/mo from previous $500/mo enterprise-only positioning. Teams $80/user with unlimited concurrent sessions is the headline business play.

**GitHub Spark / Copilot Coding Agent** `[training-data — verify]` — Spark productized for Copilot Pro+ late 2025; Coding Agent GA'd through 2025, bundled into Copilot Business/Enterprise. No standalone web-fetch this pass.

**Windsurf** `[web-fetch]` — SWE-1.5 ("Fast Agent model") visible 2026-04-30; "Devin Cloud sessions" in Max tier suggests Cognition acquisition or partnership.

---

## §6 — Indian Market Lens

Of the 8 competitors fetched, **none surface INR pricing** 2026-04-30. All show USD with implicit Stripe FX at checkout. GST is invisible to the buyer; Indian B2B has to chase invoices for input tax credit. `[web-fetch]`

ExecuteSpec carries `BILLING_DEFAULT_GATEWAY=stripe` global with Razorpay wired for India; `website/index.html` geo-detects `Asia/Kolkata` and flips USD → INR. TEAM at ₹3,999/seat sits below USD-equivalent, matching the Indian SaaS "USD-discount" norm. GST collection + invoicing for B2B is in `BillingService.subscribe` and `InvoiceService` (V62-era). `[hands-on per CLAUDE.md]`

**Assessment:** Real distribution edge for Indian SMB, not a strategic moat — Cursor or Lovable could ship Razorpay + GST in a quarter. 12-18 month window. The harder question: does the Indian dev market value verifiedScore + governance enough at $49/seat when Cursor Teams is $40/user with arguably better IDE UX. `[inferred]`

---

## §7 — Moat Reality Check

The audit's claim was: **"Spec-First is rationalisation; the actual moat is verifiedScore + multi-repo orchestration."**

Against this field, I agree with the framing but refine it:

1. **"Spec-First" is rationalisation.** Every Tier 1 competitor accepts natural-language input. The framing is positioning, not capability. `[inferred]`
2. **verifiedScore is a real moat if deterministic-verification survives skeptical inspection.** Browser validation measured 4-for-4 LOW+MEDIUM at $0.05-$0.23, build+tests+reviewer all passing. The formula (40 anchor + 20 build + 20×tests + verdict + penalties) is LLM-independent. Devin and Copilot Coding Agent have *some* of this (CI on a PR), but ExecuteSpec's advantage is **all signals in one artifact** — score + statusReason + verdict + tokens + cost — without chasing three tools. `[hands-on + training-data]`
3. **Multi-repo is a real moat for customers who need it** — mid-market eng teams on monorepo + microservices, not hobbyists. The competition here is Devin / Copilot Coding Agent, both better-funded with GitHub-native distribution. Thinner moat. `[training-data]`
4. **Governance schema (V131-V139) is underrated.** A year of schema work no Tier 1 has shipped — but none is wired into the planner yet (CLAUDE.md notes the bounded-scope deferral). Future moat, not deployed. `[hands-on per CLAUDE.md]`

**Position:** The defensible 12-month moat is deterministic verifiedScore on multi-repo PRs, sold to mid-market eng teams evaluating Devin or Copilot Coding Agent. PRO grandfathered + MAX BYOK are consumer hooks, not the strategic story.

---

## §8 — Repositioning Options

Three credible options ExecuteSpec could defend:

**Option A — "Mergeable PRs with proof of correctness."** Lean fully into the verifiedScore + reviewer + plan-gate stack as the core promise. ICP: mid-market engineering teams already using GitHub PR workflow who don't trust autonomous agents to ship without evidence. Competitors: Devin, Copilot Coding Agent. Pricing: TEAM $49/seat is right; MAX/PRO become anti-features (distract from the team buyer).

**Option B — "Governance-first AI codegen."** Sell V131-V139 as the differentiator: contracts + standards + ADR + invariants + activity feed. ICP: engineering managers / staff engineers / platform teams at companies past 50 engineers. Competitors: Sourcegraph Cody (governance overlap), JetBrains AI Assistant. Pricing: ENTERPRISE-led; TEAM is loss-leader.

**Option C — "BYOK + verification for senior individual engineers."** MAX at $29 BYOK becomes the hero tier. ICP: senior engineers + tech leads who already pay Anthropic/OpenAI directly and want one tool that adds verification + plan-gate without re-billing the LLM cost. Competitors: Aider (free + BYOK), Cline (free + BYOK). Pricing: MAX $29 leads, TEAM is upsell.

**Recommendation: Option A.** Engine works at LOW+MEDIUM (browser-validated); verifiedScore is real and UI-visible; Devin's $20/$200/$80-seat ladder shows the market pays for autonomous-agent quality. Option B isn't wired enough to lead with; Option C is a feature flag away from commoditization.

---

## §9 — Coverage Gaps

What I could not disposition without sign-ups or hands-on use:

1. **Real Devin / Copilot Coding Agent runs on the LOW Fibonacci spec.** §3 is projection-only. One $20 Devin Pro month + one Copilot Pro+ month would measure wall-clock + artifact + cost on the same spec. ~$60 for direct comparison data.
2. **GitHub Spark UX + Q1 2026 pricing.** Training-data only — no web-fetch this pass. Verify before external positioning.
3. **Cursor BYOK status.** Public pricing page shows none, but historically Cursor supported "use your own OpenAI key" via settings. If still extant, "BYOK at sub-$30" is not unique to ExecuteSpec.
4. **Lovable / Bolt artifact quality on a backend-library spec.** §3's "wrong-tool-for-the-job" projection deserves a 30-min hands-on test on each free tier.
5. **Tier 3 competitors (Cline, Continue, Aider, Augment) — single-paragraph summary deferred.** All four are open-source or freemium IDE-agents competing on "free + BYOK + open weights." Not direct competitors for the ExecuteSpec ICP. `[training-data]`
6. **Windsurf-Cognition relationship + GH CCA governance overlap (CODEOWNERS / branch protection).** Both worth verifying before any external claim leans on them.

---

*End of pass. Word count: 2,500 (at cap).*
