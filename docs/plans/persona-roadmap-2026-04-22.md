# Persona-driven roadmap — 2026-04-22

> **Origin:** persona analysis session 2026-04-22. Three buyer personas (Engineer, VP of Tech, C-Suite at a startup) × four tiers (FREE / PRO / MAX / ENTERPRISE) rated the current product. Separately, a Product Manager view critiqued the mid-funnel. This doc is the **comprehensive** capture of every action item surfaced.
> **Status:** LIVING document. Update when items ship, slip, or are de-scoped.
> **Owner:** Navneet.
> **Target window:** 2026-04-28 → 2026-07-31 (~13 weeks of sequenced work).
> **Reference docs:** [`team-tier-plan.md`](team-tier-plan.md) · [`tier-lifecycle-plan.md`](tier-lifecycle-plan.md) · [`activation-instrumentation-plan.md`](activation-instrumentation-plan.md) · [`ttfv-reduction-plan.md`](ttfv-reduction-plan.md).

## 0 · How to read this doc

- **§1-3 are the buyer lens** — what each persona said about each tier + what would move the score.
- **§4 is the PM lens** — funnel + measurement + TTFV + positioning observations.
- **§5 is the master roadmap** — everything above sequenced into a shipping plan with owners, estimates, dependencies.
- **§6 is the accountability slate** — decisions captured, open questions, non-goals.

Items with a detailed plan doc are linked. Items without are captured as ~3-line stubs — spin into full plan docs only when the item is next-up for execution.

---

## 1 · Current state (baseline scores, 2026-04-22)

| Persona | FREE | PRO | MAX | ENTERPRISE |
|---|---|---|---|---|
| Engineer (individual) | 4 / 10 | 5 / 10 | 6.5 / 10 | N/A |
| VP of Tech (mid-size) | 2 / 10 | 3 / 10 | 6 / 10 | 5 / 10 |
| C-Suite (startup) | 5 / 10 | 4 / 10 | 8 / 10 | 4 / 10 |

**Cross-persona patterns:**
- **MAX is the strongest tier** across all three buyer types. Pricing page should lead with MAX, not PRO.
- **PRO is the weakest tier** across all three. Wins signup, loses customer. Being grandfathered via tier-lifecycle tooling (see [`team-tier-plan.md`](team-tier-plan.md)).
- **FREE underserves team evaluation.** Useless for procurement; mediocre even for individual trial because the differentiating features (multi-repo projects, architecture docs, verified score) are hidden.
- **ENTERPRISE has no proof-of-seriousness on the public surface** (no SOC2 badge, no anchor price, no customer logos, no MSA template). Treated as vapor by buyers.

---

## 2 · Per-persona × per-tier interventions

Every row is an action item. All have been captured into §5 with an ID, priority, and timing.

### 2.1 Engineer (individual buyer)

| Tier | Score → Target | Interventions |
|---|---|---|
| FREE | 4 → 7 | **E-F-1.** Swap read-only demo for a real writable sandbox project (1 repo · 1 LOW + 1 MEDIUM/HIGH + 3 LOW = 5 runs). See [ttfv-reduction-plan.md](ttfv-reduction-plan.md). **E-F-2.** 14-day "all of MAX" trial on signup, step down to FREE after. **E-F-3.** Preserve output as a permanent shareable link (like CodeSandbox / Gist). |
| PRO | 5 → 7.5 | **E-P-1.** Rebrand PRO as "Solo" / "Starter" and cap-position (self-segments team buyers out). **E-P-2.** Either bump to 40 credits at $20 OR add "Solo+" at $29/50 credits. **E-P-3.** Move project memory + tech-debt panel from MAX into Solo. |
| MAX | 6.5 → 8 | **E-M-1.** Ship thin VS Code extension. **E-M-2.** Ship CLI (`npx executespec submit`). **E-M-3.** Publish fair-use numbers ("unlimited up to 500 runs/month/user"). **E-M-4.** Credit-like usage meter for BYOK users showing monthly spend on each provider. |

### 2.2 VP of Tech (mid-sized company, 25-80 devs)

| Tier | Score → Target | Interventions |
|---|---|---|
| FREE | 2 → 5 | **V-F-1.** Procurement-ready one-pager linked from Enterprise pricing (security posture, data-flow diagram, tier compare, 2 logos). **V-F-2.** "Request team trial" CTA (5 seats, full MAX, 30 days, work-email gated + 20-min onboarding call). |
| PRO | 3 → 6 | **V-P-1.** Kill PRO as a team tier — hide via [tier-lifecycle-plan.md](tier-lifecycle-plan.md). **V-P-2.** Introduce TEAM tier at $49/seat — see [team-tier-plan.md](team-tier-plan.md). |
| MAX | 6 → 8 | **V-M-1.** Ship SSO/SAML at MAX (currently ENTERPRISE-gated). **V-M-2.** Consolidated team billing + admin console at MAX. **V-M-3.** GitHub App (not PAT) for org-wide installation. **V-M-4.** Publish a 99.5% uptime SLA on MAX tier. |
| ENTERPRISE | 5 → 8 | **V-E-1.** Anchor price on public page ("from $1,500/mo for 20 seats, $60/seat above"). **V-E-2.** SOC2 Type I badge Q3 + Type II roadmap by year-end. **V-E-3.** Downloadable MSA + DPA templates. **V-E-4.** 3+ customer logos + 1 case study. **V-E-5.** Named CSM + response-time SLA (P1 4h). |

### 2.3 C-Suite (startup, 5-20 devs)

| Tier | Score → Target | Interventions |
|---|---|---|
| FREE | 5 → 7 | **C-F-1.** "Invite 2 teammates to your free org" — lets founder drop it in Slack for engineer vetting. **C-F-2.** 1-click "upgrade whole team to MAX" from the FREE org view. |
| PRO | 4 → 7 | **C-P-1.** Startup program — 50% off MAX first year for companies < 20 employees (domain + LinkedIn check at signup). **C-P-2.** Annual commit discount ($24/seat/yr vs $29/mo). |
| MAX | 8 → 9 | **C-M-1.** Team admin view at MAX (seat assignment, shared credit pool, single invoice, audit log). Overlaps with TEAM tier work. **C-M-2.** Startup migration playbook — "switch from Cursor/Copilot in a week" — with a template spec library for common startup tasks. **C-M-3.** Weekly office hours for paying teams (30 min, any eng Q, pooled across customers). |
| ENTERPRISE | 4 → 6 | **C-E-1.** "Startup Enterprise" carve-out — minimum 10 seats, month-to-month, $99/seat flat, email support, no MSA. |

---

## 3 · Cross-persona headline patterns (not actions, framing)

Three framings the marketing + product copy should adopt:

**P-1. Lead with MAX, not PRO.** Current pricing page spotlights PRO ($20/mo) because it's the lowest-friction conversion. PRO is also the weakest tier per every persona. MAX is the differentiated, defensible offer. Rearranging the pricing page hero to anchor on MAX changes the buyer mental model.

**P-2. Positioning by persona, not by ladder.** Replace the 4-tier ladder with a 4-persona layout: "I'm a solo dev / I'm a team / I'm an org / I'm Enterprise." A/B test against current.

**P-3. Verified score is the story.** The 0-100 verified score is the #1 product differentiator. Currently buried in FAQ. Should be the landing-page hero — ideally as a live interactive demo: "submit a spec, see a score appear in 90 seconds." Closest analog: v0.dev's live prompt demo.

---

## 4 · PM-lens items (funnel + measurement)

The product-manager critique surfaced 8 items. Numbered for cross-reference.

**PM-1. No activation funnel instrumentation.** The single most important dashboard in year 1. Full plan: [`activation-instrumentation-plan.md`](activation-instrumentation-plan.md). **P0.**

**PM-2. TTFV is 10-15 min; should be under 3 min.** Largest drop-off in the funnel. Full plan: [`ttfv-reduction-plan.md`](ttfv-reduction-plan.md). **P0.**

**PM-3. Tier matrix mis-shaped for buyers.** PRO converts the wrong users and loses the right ones. Addressed in [`team-tier-plan.md`](team-tier-plan.md) + [`tier-lifecycle-plan.md`](tier-lifecycle-plan.md). **P0.**

**PM-4. Feature sprawl.** 80+ features visible in docs; a new user hits ~60 in the UI within an hour. Fix: define the critical path (`spec → plan → run → PR merged with verified score`) and hide everything else behind "Advanced" until the user has completed it twice. Ties into TTFV work; implementation lives in frontend progressive-disclosure. **P1.**

**PM-5. Provider choice exposed when it should be hidden.** Most users don't know which provider to pick. Ship "smart routing" as the default with a power-user override toggle. **P1.**

**PM-6. Landing page doesn't lead with verified score.** Replace static hero with a live "submit a spec, see a score appear" demo embed. Probably moves signup conversion 2-3×. **P1.**

**PM-7. Product Hunt launch risk.** PH audience is indie devs; MAX + multi-repo projects serve teams. Two concerns: (a) PH day-7 retention <15% for this shape; (b) PH lifts funnel top, not bottom. Action: delay PH 2-3 weeks so activation + TTFV work lands first. **Immediate / Go-No-Go for PH.**

**PM-8. No public changelog or status page.** Buyers returning after 2 weeks can't see "what's new." A changelog page signals "alive and shipping"; a status page signals "uptime seriously." Cheap to ship. **P3.**

---

## 5 · Master roadmap (sequenced shipping plan)

Organized into **4 shipping waves**. Each wave is ~2 weeks. Items move between waves if data / capacity changes — this is a living doc.

### 5.1 Wave 1 — Pricing-shape fix + first instrumentation (2026-04-28 → 2026-05-06, ~1.5 weeks)

Ships before Product Hunt launch. Unblocks the MAX-forward positioning. Must ship in this window or PH traffic bounces off the old pricing.

| ID | Item | Persona intervention IDs | Plan doc | Days | Dependencies |
|---|---|---|---|---|---|
| W1.1 | TEAM tier at $49/seat with seat-based billing, shared pool | V-P-2, C-M-1 (partial) | [team-tier-plan.md](team-tier-plan.md) | 7 | Stripe TEAM product created |
| W1.2 | Tier lifecycle tooling (LISTED / HIDDEN / SUNSETTING) | V-P-1, tier grandfathering | [tier-lifecycle-plan.md](tier-lifecycle-plan.md) | 2 (bundled in W1.1) | — |
| W1.3 | PRO → HIDDEN on day 1 of W1.1 | V-P-1 | (day-1 migration in W1.1) | 0 (migration only) | W1.2 schema |
| W1.4 | Complimentary PRO promo executed | founder thank-you (separate from persona analysis) | [complimentary-pro-promo-2026-04-30-plan.md](complimentary-pro-promo-2026-04-30-plan.md) | 0.5 (in-session) | Reminder trigger active (already scheduled) |

**Exit criteria for Wave 1:**
- TEAM tier live on prod, pricing page shows FREE / TEAM / MAX / ENTERPRISE (PRO hidden for new buyers)
- Existing PRO subscribers unchanged
- At least one SUPER_ADMIN round-trip of LISTED ↔ HIDDEN verified on prod
- Complimentary PRO promo executed on April 30 for the filtered user list

### 5.2 Wave 2 — Measurement + onboarding (2026-05-07 → 2026-05-20, 2 weeks)

These two run in parallel. Surfaces don't overlap.

| ID | Item | Persona intervention IDs | Plan doc | Days | Dependencies |
|---|---|---|---|---|---|
| W2.1 | Activation instrumentation (6-event PostHog funnel) | PM-1 | [activation-instrumentation-plan.md](activation-instrumentation-plan.md) | 10 | PostHog Cloud project created |
| W2.2 | TTFV < 3 min (sandbox + sample spec on signup) | PM-2, E-F-1 | [ttfv-reduction-plan.md](ttfv-reduction-plan.md) | 10 | — (but measurement comparison needs W2.1 baseline) |

**Exit criteria for Wave 2:**
- 6-event funnel live in PostHog; baseline captured in `docs/reports/activation-funnel-baseline.md`
- Sandbox project provisioned on signup; 3 sample specs available; median TTFV measured under 3 min on a test signup
- Celebrate-on-complete screen on first run

### 5.3 Wave 3 — Activation-data-driven iteration + MAX SSO (2026-05-21 → 2026-06-03, 2 weeks)

Parallel tracks. Second track (SSO) de-risks the VP-of-Tech persona blockage.

| ID | Item | Persona intervention IDs | Plan doc | Days | Dependencies |
|---|---|---|---|---|---|
| W3.1 | Act on biggest drop-off from activation funnel | PM-1 followthrough | none yet — write when data surfaces | 5-7 | W2.1 data |
| W3.2 | SSO/SAML at MAX + consolidated team billing | V-M-1, V-M-2 | to write — stub below | 8 | TEAM infra from W1 |
| W3.3 | GitHub App (not PAT) for org-wide install | V-M-3 | to write — stub below | 5 | SSO/SAML |
| W3.4 | "Request team trial" CTA + procurement one-pager | V-F-1, V-F-2 | lightweight — stub below | 3 | — |

**SSO/SAML stub** (W3.2):
- Backend: `Org.ssoProvider` column (`GOOGLE` | `OKTA` | `AZURE_AD` | null), `Org.samlMetadataUrl`, `Org.ssoEnforced BOOLEAN`
- Spring Security SAML2 service-provider integration (Spring Security SAML2 Service Provider)
- Google OIDC enforcement: when `ssoEnforced=true`, email-password login rejected for users in that org
- Admin UI: `/org/settings/sso` — paste SAML metadata URL / Google Workspace domain
- Consolidated billing: single invoice per org (already works for TEAM; MAX needs org-scoped billing to be enabled — audit `BillingEntityResolver.java`)

**GitHub App stub** (W3.3):
- Create ExecuteSpec GitHub App (vs OAuth) in GitHub settings
- `org.github_installation_id` column
- Install flow: admin clicks "Install GitHub App on org" → GitHub authorizes → webhook delivers installation_id
- Migrate existing PAT-authenticated repos to GitHub App tokens (background job)
- PAT auth stays as fallback for personal use

**Exit criteria for Wave 3:**
- Activation funnel has at least one data-driven intervention shipped
- SSO/SAML works end-to-end for at least Google OIDC (SAML a stretch, can slip to Wave 4)
- GitHub App installable; new-repo flow uses App token; existing PAT flow unchanged

### 5.4 Wave 4 — Landing + enterprise proof + IDE (2026-06-04 → 2026-06-17, 2 weeks)

Focused on externally-visible changes after the internal work of Waves 1-3.

| ID | Item | Persona intervention IDs | Plan doc | Days | Dependencies |
|---|---|---|---|---|---|
| W4.1 | Live-demo verified-score hero on landing page | PM-6, P-3 | to write — stub below | 6 | TTFV (W2.2) |
| W4.2 | Persona-first pricing page A/B | P-2, PM-3 followthrough | lightweight — stub below | 3 | Activation data (W2.1) |
| W4.3 | Smart provider routing as default | PM-5 | lightweight — stub below | 4 | — |
| W4.4 | Enterprise proof pack (SOC2 badge roadmap + MSA/DPA templates + anchor price + logos) | V-E-1, V-E-2, V-E-3, V-E-4 | to write — stub below | 5 | SOC2 Type I scoping (out-of-band work) |
| W4.5 | VS Code extension (thin — just "submit spec" + "show last run") | E-M-1 | to write — stub below | 8 | — |
| W4.6 | Public changelog + status page | PM-8 | lightweight — stub below | 2 | — |

**Live-demo hero stub** (W4.1):
- Landing page replaces static hero with embedded runner
- Prefilled sample spec ("Add POST /api/contacts ..."), "Submit" button
- Runs against a dedicated sandbox that bypasses auth (rate-limited per IP)
- Shows: plan preview (5 seconds) → execution status → verified score appears in ~90 seconds
- Falls back to video if runner unavailable

**Persona-first pricing page A/B stub** (W4.2):
- Implement via PostHog feature flag
- Variant A (control): current 4-tier ladder
- Variant B: "I'm a solo dev" / "I'm a team" / "I'm an org" / "I'm Enterprise" — each box routes to the right tier card
- Measure: `signup` → paid-tier conversion rate at 30 days
- Minimum sample: 2000 visits or 30 days, whichever comes first

**Smart provider routing stub** (W4.3):
- `ProviderRecommendationService` picks provider based on: project type, historical success rate, current circuit-breaker state
- UI: "Auto" is the default dropdown value; user can override to specific provider
- Heuristic: frontend-heavy → Gemini (speed); backend-heavy → OpenAI; architecture-heavy → Anthropic
- Respects user's BYOK: if user has only Anthropic keys, always use Anthropic
- Gated behind flag `provider.smart-routing.enabled`; opt-in for paying users first, default-on after 30 days of data

**Enterprise proof pack stub** (W4.4):
- `website/enterprise.html` — one-pager with SOC2 status, MSA template (PDF), DPA template (PDF), 3 logos, 1 case study
- Price anchor on main pricing page: "Enterprise — from $1,500/mo for 20 seats"
- SOC2 Type I project kickoff (external vendor — Vanta / Drata / Secureframe pick needs a separate decision)
- MSA + DPA drafts (Navneet writes or engages counsel — out-of-band)

**VS Code extension stub** (W4.5):
- TypeScript extension in a separate repo (`executespec-vscode`)
- Commands: `ExecuteSpec: Submit spec`, `ExecuteSpec: Show last run`, `ExecuteSpec: Open verified report`
- Auth: OAuth device-code flow
- Ship to marketplace

**Changelog + status page stub** (W4.6):
- `/changelog` — auto-generated from tagged git commits + CHANGELOG.md
- `/status` — self-hosted (https://statusfy.co or similar) or third-party (Statuspage.io, Instatus)
- Link both from main nav + pricing page footer

### 5.5 Wave 5 — Polish, programs, IDE depth (2026-06-18 → 2026-07-01, 2 weeks)

Per-persona finishing touches.

| ID | Item | Persona intervention IDs | Plan doc | Days |
|---|---|---|---|---|
| W5.1 | Startup program + annual discount | C-P-1, C-P-2 | lightweight — stub below | 3 |
| W5.2 | Startup Enterprise carve-out ($99/seat flat, no MSA, 10-seat min) | C-E-1 | lightweight — stub below | 3 |
| W5.3 | Migration playbook + template spec library | C-M-2 | lightweight — stub below | 4 |
| W5.4 | Weekly office hours (calendar + invite flow) | C-M-3 | lightweight — stub below | 1 |
| W5.5 | Progressive disclosure / critical-path UI | PM-4 | to write | 6 |
| W5.6 | Credit-like usage meter for BYOK users | E-M-4 | lightweight — stub below | 3 |
| W5.7 | Publish fair-use numbers for MAX | E-M-3 | copy-only | 0.5 |
| W5.8 | Output-as-shareable-link (FREE tier) | E-F-3 | lightweight — stub below | 4 |
| W5.9 | 14-day MAX trial on signup | E-F-2 | lightweight — stub below | 3 |
| W5.10 | "Invite 2 teammates to free org" | C-F-1 | lightweight — stub below | 3 |
| W5.11 | Celebrate-on-complete + 1-click "upgrade whole team to MAX" | C-F-2 | part of TTFV follow-up | 2 |

### 5.6 Wave 6 — Enterprise depth + CLI (2026-07-02 → 2026-07-15, 2 weeks)

| ID | Item | Persona intervention IDs | Days |
|---|---|---|---|
| W6.1 | ExecuteSpec CLI (`npx executespec submit`) | E-M-2 | 6 |
| W6.2 | Named CSM tier + support SLA docs | V-E-5 | 3 |
| W6.3 | Customer logo page + case study | V-E-4 | 4 |
| W6.4 | Audit log export (CSV) on ENTERPRISE | V-E side | 3 |

### 5.7 Optional: Solo rebranding (parked)

Items **E-P-1**, **E-P-2**, **E-P-3** (rebrand PRO to Solo, bump credits, move features) are parked pending Wave 2 activation data. If data shows grandfathered-PRO tail is stable and we need a new individual-buyer tier, reopen; otherwise the grandfathering + MAX-forward positioning may be enough.

---

## 6 · Accountability slate

### 6.1 Decisions captured

| Decision | Made on | By | Rationale |
|---|---|---|---|
| Pricing v3: $1 = 1 credit, flat per band | 2026-04-21 | Navneet | [website/tier-decision.md](../../website/tier-decision.md) |
| US-first pivot + Stripe prod default | 2026-04-21 | Navneet | [docs/reports/session-handoff-2026-04-21.md](../reports/session-handoff-2026-04-21.md) |
| Hide PRO, don't remove | 2026-04-22 | Navneet | industry pattern, zero churn risk on paying customers |
| Option Z (TEAM + lifecycle bundled) | 2026-04-22 | Navneet | one coherent ship, avoids disposable feature flag |
| Sample-spec + sandbox on signup (TTFV Option B hybrid) | 2026-04-22 | Navneet (planning) | faster to ship, upgrade if data justifies |

### 6.2 Open questions (resolve before the relevant wave starts)

| Question | Blocks which wave | Owner |
|---|---|---|
| Startup program eligibility rule — domain check, LinkedIn check, or both? | W5.1 | Navneet |
| SOC2 vendor — Vanta vs Drata vs Secureframe? | W4.4 | Navneet (procurement) |
| Changelog + status page — third-party (Statuspage / Instatus / Statusfy) or self-hosted? | W4.6 | Navneet |
| "Smart routing" default — opt-in or opt-out? | W4.3 | Navneet (after 30 days of activation data) |
| Is EU data residency required for SOC2 customers? | W4.4 | Navneet (after first EU enterprise ask) |
| 14-day MAX trial — auto-expire to FREE or notify and keep until reply? | W5.9 | Navneet |

### 6.3 Non-goals (items deliberately NOT on this roadmap)

- **Opus-grade planning for all tiers** — unchanged; provider selection matrix in CLAUDE.md §Model Matrix is correct.
- **Migration off Spring Boot** — no strategic reason; stays.
- **Mobile-first UI** — users are on desktop; mobile support is "functional" not "optimized."
- **Custom LLM fine-tuning** — we don't have the data scale yet; absorb provider costs instead.
- **Slack/Discord bot** — nice to have, not a buyer ask today.
- **White-label ExecuteSpec** — no buyer asked for it; too-early commitment.
- **Killing PRO entirely (vs HIDDEN/SUNSETTING)** — see [tier-lifecycle-plan.md](tier-lifecycle-plan.md) for the incremental path.

### 6.4 Review cadence

- **Weekly (every Monday 30 min):** move items between waves based on ship data, update exit-criteria status.
- **After each wave ships:** write a retrospective in `docs/reports/wave-{N}-retro.md` with measured outcomes vs. success metrics.
- **Monthly:** re-rate the 12 persona × tier scores. If any score drops, flag as regression in §1 table.

### 6.5 Kill criteria for the whole roadmap

If by 2026-06-30 (end of Wave 4):
- TEAM conversion < 25% among orgs with ≥ 2 members, OR
- Activation instrumentation shows signup → run_complete < 30% within 7 days, OR
- Support ticket volume grows > 3× from today

then pause Waves 5-6 and re-scope. Volume growth without retention growth is a signal the product is reaching the wrong buyers.

---

## 7 · One-glance shipping calendar

```
2026-04-28   Wave 1 start  (TEAM tier + lifecycle tooling)
2026-04-30    └─ Complimentary PRO promo execution (separate plan)
2026-05-05    └─ TEAM tier live
2026-05-06   Wave 1 end (buffer day)
2026-05-07   Wave 2 start  (activation + TTFV, parallel)
2026-05-20   Wave 2 end
2026-05-21   Wave 3 start  (SSO/SAML + GitHub App + data-driven iteration)
2026-06-03   Wave 3 end
2026-06-04   Wave 4 start  (landing + enterprise proof + VS Code)
2026-06-17   Wave 4 end
2026-06-18   Wave 5 start  (startup programs + polish + progressive disclosure)
2026-07-01   Wave 5 end
2026-07-02   Wave 6 start  (CLI + enterprise depth)
2026-07-15   Wave 6 end
```

**~13 weeks. 1 person. Every item mapped to a persona score improvement.**
