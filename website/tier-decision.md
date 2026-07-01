# Tier Re-assessment — Project View & Platform Features

> **HISTORICAL REFERENCE (superseded by the 2026-06-30 business packet).** The
> current ladder is **FREE / PRO / PRO+ / BYOK / TEAM / ENTERPRISE**: Free has
> 200 AI credits on native DeepSeek, Pro is $20/month with 2,000 AI credits,
> hidden Pro+ is $100/month with 125,000 AI credits, BYOK is $50/month, Team is
> $50/seat/month from 2 seats with BYOK, and Enterprise is custom. Do not use the MAX tier, old score claims
> below for current pricing or investor copy. See `website/llms.txt`,
> `website/index.html`, and `docs/business/billing-plan-assessment.md`.
>
> Original doc kept below for the analytical reasoning that produced the
> ladder — pricing numbers and tier names are stale.

_Last updated: 2026-04-21 (v3 — real eval + credit model locked)_
_Decided as part of the UX overhaul on branch `feature/ux-overhaul-v1`._

## Credit model (v3 — locked)

**$1 = 1 credit** is the base unit, same for subscription allocation and top-ups.

Credits per run are flat by complexity band (not per-provider):

| Band | Real cost range (Apr 21 eval, across 3 providers) | Credits charged | User pays | Margin (best / worst provider) |
|---|---|---|---|---|
| LOW | $0.087 – $0.134 | **1** | $1.00 | 91% (Anthropic) / 87% (Gemini) |
| MEDIUM | $0.332 – $0.482 | **1** | $1.00 | 67% (OpenAI) / 52% (Gemini) |
| HIGH | $1.28 – $2.65 | **4** | $4.00 | 68% (Gemini) / **-32% (Anthropic)** |
| Mobile (Android/iOS) | $0.67 – $1.23 | **2** | $2.00 | 67% (OpenAI) / 39% (Anthropic mobile-iOS) |

**On the Anthropic HIGH loss: we eat it as CAC.** The $2.65 Anthropic HIGH vs $4 user charge loses $0.65 (-32% margin). We accept this because:

1. The user picked Anthropic on purpose (quality signal — Anthropic HIGH averaged 87 accuracy vs Gemini 85 and OpenAI 78 in the Apr 21 eval). Penalising them for that choice undercuts the "your provider, your choice" positioning.
2. The blended margin across providers on HIGH is still positive: weighted 40% OpenAI / 35% Gemini / 25% Anthropic → $1.64 blended cost on a $4 charge = 59% margin.
3. PRO is capped at 20 credits/mo. Worst-case an Anthropic-preferring PRO user burns all 20 credits on HIGH runs — 5 runs × $2.65 = $13.25 cost on $20 plan = 34% margin. Still positive on the plan level.

Credits are shown at plan-approval so the user always sees what a spec will consume, but the count does not vary by provider — complexity band is the only axis.

### Why flat-per-band (Option C) over per-provider (Option B)

We considered "3 credits for Gemini HIGH / 4 for OpenAI / 6 for Anthropic". Rejected because:
- **Perverse incentive**: user picks the cheapest provider for every run, erasing the quality signal we rely on for the verified score distribution.
- **UX noise**: plan-approval chip flickers on provider change (already a live field in `NewRunModal`).
- **Arbitrage**: users would pick Gemini, hit the 10× token regression, see a bad run, blame the platform.

Flat-per-band means the user decision is 100% about spec complexity, not about which LLM gets cheaper this month. We absorb the provider variance as a platform cost.

## Current (pre-overhaul) state

| Tier | Monthly runs | Keys | Project view | Execution view | Notes |
|---|---|---|---|---|---|
| **FREE** | 5, 1-credit only | Managed | ❌ | ❌ | "Prototype" marketing label |
| **PRO** | 10 | Managed | Soft/implicit | ❌ | $20/mo; top-up at $2/run |
| **MAX** | Unlimited fair-use (≤500/mo) | BYOK | ✅ | ❌ | $29/mo; zero API markup |
| **ENTERPRISE** | Unlimited | BYOK | ✅ | ✅ | Custom pricing |

**Problems with the current state:**

1. The most differentiating feature of the whole platform — the **Project workspace** (multi-repo, architecture docs, memory, validation v3, tech debt, scoring trend, launches) — is gated behind MAX+. That means FREE and PRO users never see the thing that makes ExecuteSpec actually different from Bolt / Cursor.
2. Conversely, the two BYOK tiers (MAX + ENTERPRISE) are hard to differentiate on the pricing page — both "unlimited runs, all models, BYOK". Enterprise reads as "MAX with a sales call".
3. PRO at 10 runs/month is **way** too low once you consider a run can be a two-minute spec check. It punishes the highest-velocity PMs/leads who would otherwise be strong word-of-mouth.
4. FREE is "3 runs / month, single-credit only" on the landing page but the app backfills as 5 — mismatch.

## Proposed new tier matrix

Every tier now gets **visibility into the Project workspace** (so new users can see what the platform is capable of), but deep features gate at PRO / MAX / ENTERPRISE.

| Feature | FREE | PRO | MAX | ENTERPRISE |
|---|---|---|---|---|
| **Monthly allocation** | 5 credits (1-credit caps) | **20 credits** ($20/mo) | Unlimited fair-use | Unlimited + SLA |
| **Effective runs/mo** | 5 LOW-only | 20 LOW / 5 HIGH / 10 Mobile (mix) | Unlimited | Unlimited |
| **Top-up pricing** | — | $1 = 1 credit (any pack size) | — | Custom |
| **Managed keys (we pay)** | ✅ | ✅ | — | — |
| **BYOK** | — | — | ✅ | ✅ |
| **Spec templates (save/reuse)** | 1 personal | Unlimited personal | Unlimited personal | Unlimited org-wide |
| **GitHub integration (push)** | — | ✅ | ✅ | ✅ |
| **Run detail: plan / tasks / logs / preview** | ✅ | ✅ | ✅ | ✅ |
| **Validation report (verified score + signals)** | ✅ view | ✅ view | ✅ view | ✅ view + CSV export |
| **Run archive / history retention** | 30 days | 90 days | 90 days | Unlimited |
| **Provider choice (Anthropic / OpenAI / Gemini)** | Default only | ✅ | ✅ | ✅ |
| **Advanced models (Opus, GPT-5.4, Sonnet for planning)** | — | Rate-limited | ✅ | ✅ |
| **Project view: read-only demo project** | ✅ (seeded) | — | — | — |
| **Project view: create + manage** | — | ✅ up to 2 | ✅ up to 10 | Unlimited |
| **Linked repos per project** | 1 (seeded) | 2 | 10 | Unlimited |
| **Architecture doc generation** | — view seed only | ✅ manual | ✅ manual + auto-on-sync | ✅ + scheduled + diff-alerts |
| **Per-repo codemap** | — | ✅ | ✅ | ✅ |
| **Tech debt panel** | — view seed only | — | ✅ | ✅ |
| **Workspace health / staleness** | — | — | ✅ | ✅ |
| **Project memory + compaction** | — | — | ✅ | ✅ |
| **Project chat (RAG over repos + docs)** | — | — | ✅ | ✅ |
| **Generate Docs (explanation / setup)** | — | ✅ 1 per project | ✅ | ✅ |
| **TDD mode toggle** | — | ✅ | ✅ | ✅ |
| **Figma URL → design-compliance score** | — | ✅ | ✅ | ✅ |
| **React Native + Expo codegen** | ✅ | ✅ | ✅ | ✅ |
| **Deploy to Cloud Run (preview)** | ✅ 5 previews/run | ✅ 10 | ✅ 20 | Unlimited |
| **Deploy to Cloud Run (permanent)** | — | — | ✅ 5 active | Unlimited + custom domain |
| **Execution View (multi-repo, multi-solution launches)** | — | — | — | ✅ |
| **Cross-repo handover / dependency graph** | — | — | — | ✅ |
| **Spec templates org-wide** | — | — | — | ✅ |
| **JIRA + Confluence inbound** | — | — | — | ✅ |
| **Slack notifications** | — | ✅ (DMs only) | ✅ (channels) | ✅ (channels + routing rules) |
| **ServiceNow / PagerDuty** | — | — | — | ✅ |
| **SSO (Google / GitHub OAuth)** | ✅ | ✅ | ✅ | ✅ + SAML |
| **Org members / RBAC / invitations** | — | — | — | ✅ |
| **Audit trail** | — | 7-day | 30-day | Unlimited |
| **Accounting (MRR/ARR/GST)** | — | — | — | ✅ |
| **Priority support** | Community | Email | Email priority | Dedicated slack + custom SLA |
| **Super-admin tools (feature flags, pricing manager)** | — | — | — | Internal only |

### Rationale per gate

- **Demo project on FREE** is the single biggest proposed UX shift. Today, FREE users never experience the Project workspace. We seed a read-only "Hello ExecuteSpec" demo project so they see what they're missing — no feature flag hacks, just a real project scoped to their account with a locked edit state. This is the strongest possible conversion surface.
- **PRO gets 50 runs/mo** — the 10-run cap was punishing the target ICP. 50 maps to ~2/workday which still keeps heavy users climbing to MAX.
- **PRO gets 2 projects, 2 repos each** — enough to prove value (a personal + a work side-project); not enough to run a team off.
- **Tech-debt + project memory + project chat are MAX** — these cost real compute (LLM calls to generate + summarise) and are most valuable at scale. Gating here makes the PRO→MAX jump obvious.
- **Execution view + cross-repo + JIRA/Confluence + SSO + accounting + org/RBAC are ENTERPRISE** — these are literally org-level features. Today execution view is gated on ENTERPRISE+MAX; narrowing to just ENTERPRISE makes the ladder cleaner.
- **Slack on PRO** — cheap to provide, big habit-builder. Today Slack is only on ENTERPRISE. Wrong.

## Implementation impact

- `FeatureFlagService` + `TierPermissionService` on the backend needs new keys: `PROJECT_CREATE`, `PROJECT_MEMORY`, `PROJECT_CHAT`, `TECH_DEBT`, `FIGMA_DESIGN_SCORE`, `GENERATE_DOCS`, `SLACK_CHANNELS`, `JIRA_INBOUND`, `CONFLUENCE_INBOUND`, `SSO_SAML`, `EXECUTION_VIEW`.
- Frontend `useTierPermissions` returns booleans + "nudge target" (upgrade to TIER) so locked UIs can route users to the right upgrade page.
- Run limit for PRO flipped 10 → 50 (backend migration).
- FREE users: seed "Hello ExecuteSpec" project via a registration post-hook.
- Pricing page + landing page pricing table need new copy.

## Conversion story

```
FREE
  → see a working project (demo) + do 5 runs
  → hit the "Create your own project" wall
    → PRO ($20/mo) unlocks 2 projects, 50 runs, GitHub push, Figma, TDD
      → hit "generate tech debt audit" / "project memory" / "unlimited projects" wall
        → MAX ($29/mo) unlocks project intelligence + BYOK economics
          → hit "multi-repo launches / JIRA / SSO / RBAC" wall
            → ENTERPRISE (sales) unlocks the org platform
```

Each tier opens the **next** wall — no dead-ends, no "I paid but still don't see X".
