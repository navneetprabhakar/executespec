# Tier Re-assessment — Project View & Platform Features

_Last updated: 2026-04-21_
_Decided as part of the UX overhaul on branch `feature/ux-overhaul-v1`._

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
| **Monthly runs** | 5 (1-credit caps) | 50 (was 10) | Unlimited fair-use | Unlimited + SLA |
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
