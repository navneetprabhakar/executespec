# ExecuteSpec — UI User Journeys (by Tier)

> Last reviewed: 2026-04-25. Source: frontend routes, `TierPermissionService`, `LockedFeature`, `UpgradeModal`, sidebar nav.
> Tiers (v2.6.1): **FREE → PRO (HIDDEN — grandfathered) → TEAM → MAX → ENTERPRISE**. RBAC roles (SUPER_ADMIN, ORG_ADMIN, FINANCE, DEVELOPER) are orthogonal to tier.
>
> **v2.6.x additions:**
> - **TEAM tier (Wave 1, v2.6.1):** $49/seat/mo, 2-20 seats, 50 credits/seat shared pool. Self-serve seat picker on `/plans`. PRO grandfathered to HIDDEN — existing subscribers untouched, hidden from new buyers.
> - **Sandbox onboarding (Wave 2, v2.6.1):** new `/welcome` page after first signup. 3 sample specs (web-api, frontend, cli) provision a sandbox project + pre-filled spec — target median TTFV under 3 minutes. `FirstRunCelebration` component fires on first complete run.
> - **Theme toggle (v2.6.0):** Sun/Moon `ThemeToggle` button in header — toggles `<html data-theme="light|dark">` with `localStorage` persistence.
> - **Exec summary page (v2.6.0):** new `/exec` route (Business Owner / PgM persona view). MTD spend, success rate, needs-attention count, spend-by-project bars, cross-project awaiting-approval digest. Gated behind `canViewCostBreakdown` (PRO/MAX/ENTERPRISE → FREE sees upgrade wall).
> - **Project dashboard IA (v2.6.0):** flat 10 tabs collapsed to 5 top-level groups (Overview / Work / Knowledge / Settings / Chat).

---

## 0. Shared journeys (all tiers)

### 0.1 Sign up
1. Land on `/register` (public).
2. Submit email + password.
3. Receive verification email → click link → `/verify-email` → account active.
4. Redirected to `/login` → then to `/` (Runs page).
5. Default tier: **FREE**, role: org owner of a solo org.

### 0.2 Sign in (email or OAuth)
1. `/login` — email + password, OR Google / GitHub OAuth2 button.
2. OAuth: `/auth/oauth2/authorize` → provider → `/auth/callback` → JWT issued → `/`.
3. Session: access token 1h, refresh cookie 7d.

### 0.3 Recover password
1. `/forgot-password` → submit email.
2. Link in email → `/reset-password?token=…` → set new password → `/login`.

### 0.4 View own profile + settings
- `/settings` — name, password, notification prefs, connect GitHub PAT, connect Slack (if org-admin).

### 0.5 Read shared validation report
- `/reports/shared/:token` — public, no auth. Anyone with the link sees the report.

---

## 1. FREE tier

Goal: prove the product works, ship one trivial project, nudge to PRO.

### 1.1 Create first run (happy path)
1. Click **"Create Run"** from `/`.
2. `NewRunModal` opens. Provider selector locked to `OpenAI gpt-5.4-mini`. No batch toggle. No BYOK option. No "existing GitHub repo" option unless they've pasted a PAT in `/settings`.
3. Enter spec (text). Submit → 202.
4. Redirect to run detail. Plan generates → `AWAITING_APPROVAL` gate banner shows estimated cost + credit count.
5. Click **Approve**. Run executes → tasks complete → `COMPLETE`.
6. Preview panel auto-starts (5-min TTL).

### 1.2 Hit a tier limit
- **Run #6 in a month**: submission returns `403 QUOTA_EXCEEDED` → `UpgradeModal` opens: "FREE tier allows 5 runs/month. Upgrade to PRO for 100."
- **Spec estimated at >$3**: submission rejected with "Spec exceeds FREE tier cost cap ($3)". Upgrade CTA.
- **Batch toggle**: not visible at all in the UI.
- **Deploy button** (on `/deployments`): renders `LockedFeature` — "Deployments available on PRO+".
- **Integrations page**: sidebar entry hidden. Direct URL → `UpgradeModal`.
- **BYOK** in `/settings`: section hidden.

### 1.3 Preview + download
- Max **3 preview starts per run**; 4th click shows "Preview limit reached for FREE tier".
- Download zip: always available.

### 1.4 Push to GitHub
- If user pasted a PAT in `/settings`, same flow as higher tiers (see §5). **No tier gating on GitHub push.**

### 1.5 Upgrade journey
1. Sidebar shows blue **"Upgrade to Pro"** button.
2. Click → `/plans` → select PRO → Razorpay or Stripe checkout.
3. On success → tier updates live → batch / deploy / integrations unlock within seconds.

---

## 2. PRO tier

Goal: regular individual / small-team use, managed API keys, 100 runs/mo.

### 2.1 Delta from FREE
- 100 runs/month, 2 concurrent.
- Cost cap $5/run.
- Provider selector: Anthropic / Google (Gemini) / OpenAI — model matrix unlocked.
- Batch toggle visible at approval time.
- 5 preview starts per run.
- Apply suggestions: up to 10 per run.
- Auto-retries on task failure: up to 2.
- Invite org members (up to 10). Chat copilot on run detail (10 messages/run).

### 2.2 Create run with execution mode choice
1. Same start as FREE.
2. At plan approval: two cards side-by-side — **Run Now (Realtime)** vs **Batch Process (saves 50%)** with both cost estimates.
3. Choose batch → status moves through `QUEUED_BATCH` → `IN_PROGRESS_BATCH` → `BATCH_COMPLETE` → normal post-exec flow.
4. Realtime path unchanged from FREE.

### 2.3 Deploy to Cloud Run
1. On a COMPLETE run → **Deploy** button (run-detail toolbar) → `DeployWizardModal`.
2. Choose region, env vars, custom domain (optional).
3. Click deploy → run moves to `DEPLOYING` → permanent Cloud Run URL issued.
4. `/deployments` page lists active deployments. Cap: **5 active**.

### 2.4 Integrations (ORG_ADMIN only)
- `/integrations` sidebar entry visible.
- Connect: Slack, JIRA, Confluence, ServiceNow, PagerDuty, Bitbucket, GitHub (via PAT, not OAuth), Figma (enterprise/max only).
- On connect: org-wide; all members can trigger outbound notifications.

### 2.5 Billing view
- `/billing`: plan card "PRO — $X/mo", usage bar, **Top-up Runs** button for overage credits.
- `/invoices`: self-service list.

### 2.6 Top-up when low on credits
1. Sidebar quota badge turns amber at <10 runs remaining.
2. Click **Top-up Runs** → buy N credits (Razorpay / Stripe).
3. Credits arrive instantly; run submission resumes.

---

## 3. MAX tier

Goal: heavy individual / power-user with their own API keys.

### 3.1 Delta from PRO
- **Unlimited** runs per month.
- Cost cap lifted (still subject to silent hard cap = credits × $5 × 1.5).
- **BYOK** unlocked — `/settings` shows "AI Keys" card; user can paste Anthropic/OpenAI/Gemini keys per provider.
- Concurrent runs: 3.
- Apply suggestions + preview starts: unlimited.
- Model selection UI exposes all available models in the matrix.

### 3.2 BYOK connect flow
1. `/settings` → **AI Keys** tab.
2. Paste provider API key → saved (encrypted at rest).
3. Key source badge on run submission switches to **"Your key"** instead of platform key.
4. Run cost billed at $0 to platform wallet (user pays provider directly). Credit usage: 1 credit per run (flat) for platform services.

### 3.3 Figma integration (MAX-gated)
- If org has enabled Figma (via ORG_ADMIN) and user's tier is MAX or ENTERPRISE → `NewRunModal` shows optional **"Figma URL"** field.
- Behind the scenes: `FigmaIntegrationGate` blocks non-MAX users. (Plan: [figma-integration-v2-plan.md](figma-integration-v2-plan.md)).

---

## 4. ENTERPRISE tier

Goal: large org, centralised billing, compliance.

### 4.1 Delta from MAX
- Up to 10 concurrent runs.
- Org member cap lifted.
- Domain enforcement (SAML/SSO) — ORG_ADMIN sets allowed email domain; new sign-ups outside the domain are rejected.
- Dedicated org analytics at `/admin/analytics`.
- Figma + all enterprise integrations available.

### 4.2 ORG_ADMIN journeys
- `/org/settings` — members, invites, role assignment (ORG_ADMIN / DEVELOPER / FINANCE), domain enforcement, billing profile.
- `/org/projects` — project-scoped run filtering; assign members to specific projects.
- `/integrations` — connect integrations for the entire org.
- `/billing` — master billing profile (GST for India, invoice email).
- `/audit` — view audit log (who did what).
- `/accounting` — view accounting exports. Requires **FINANCE** role.

### 4.3 FINANCE role journeys
- Read-only. Can view `/invoices`, `/accounting`, `/audit`, `/billing`. Cannot submit runs, cannot manage members.

### 4.4 DEVELOPER role journeys
- Can submit runs, view live logs, use chat copilot, download artifacts. Cannot see cost breakdown, cannot manage integrations, cannot invite members.

---

## 5. GitHub push journey (all tiers)

No tier gating. Runs on both FREE and ENTERPRISE.

### 5.1 Connect GitHub
1. `/settings` → GitHub card.
2. Paste Personal Access Token (PAT) + GitHub username → **Save**.
3. Card badge changes to green **"Connected"**.

### 5.2 Submit run against existing repo
1. `NewRunModal` → repo mode **"Start from existing repo"**.
2. Paste `https://github.com/owner/repo`.
3. Run workspace is initialized from a shallow clone. Generated commits go to a feature branch named `executespec/<runId-short>`.
4. On run completion → PR opened automatically back to the repo's default branch. URL surfaces on Overview tab as `scmPrUrl`.

### 5.3 Submit run that creates a new repo
1. `NewRunModal` → **"Create new repo"**.
2. On completion → private repo created under `githubOwner`, code pushed to default branch. No PR (nothing to merge against).

### 5.4 Push a completed run manually
1. Open a COMPLETE run → toolbar **Publish / Push to GitHub**.
2. If no PAT configured → inline modal prompts for one.
3. PR URL appears on the Overview tab after success.

### 5.5 Error paths (GitHub)
- No PAT → 400 "GitHub credentials not configured" → prompt to paste one.
- Invalid PAT → 401 "Authentication failed. Check your token."
- Token lacks repo scope → 403 "Insufficient permissions for this repository".
- Repo not found → 404 "Repository not found or not accessible".
- Rate limited → 429 "GitHub API rate limit exceeded — retry in N minutes".
- Run still executing → 409 "Only COMPLETE or PARTIAL runs can be pushed".
- Default branch locked → merge conflict banner, suggested resolution.

---

## 6. Admin journeys (SUPER_ADMIN only)

Orthogonal to tier; SUPER_ADMIN is a role granted out-of-band.

- `/admin` — landing with org analytics, recent activity.
- `/admin/pricing` — edit tier_config live (monthly_run_limit, concurrent cap, preview cap, cost cap, deployment cap).
- `/admin/feature-flags` — toggle any feature flag, per-org or global.
- `/admin/analytics` — cross-org aggregate metrics.
- `/admin/payment-audit` — payment audit log, CSV export.
- `/admin/webhook-events` — inbound webhook event log, filter by source.

---

## 7. Notification bell (all tiers)

- Header bell icon polls `/api/notifications/unread-count` every 30s.
- Dropdown lists run completion / failure / batch events. Mark-read, mark-all-read, click-to-navigate.

---

## 8. Journey matrix (quick reference)

| Journey | FREE | PRO | MAX | ENTERPRISE |
|---|---|---|---|---|
| Create run | ✓ (5/mo, $3 cap) | ✓ (100/mo, $5 cap) | ✓ (∞) | ✓ (∞) |
| Approve plan realtime | ✓ | ✓ | ✓ | ✓ |
| Approve plan batch | ✗ | ✓ | ✓ | ✓ |
| Provider selector | OpenAI-mini only | 3 providers | All | All |
| BYOK | ✗ | ✗ | ✓ | ✓ |
| Preview | 3/run | 5/run | ∞ | ∞ |
| Deploy | ✗ | ✓ (5 max) | ✓ (∞) | ✓ (∞) |
| Invite org members | ✗ | ✓ (10 max) | ✓ (∞) | ✓ (∞) |
| Integrations | ✗ | ORG_ADMIN | ORG_ADMIN | ORG_ADMIN |
| Figma integration | ✗ | ✗ | ✓ | ✓ |
| Domain enforcement | ✗ | ✗ | ✗ | ✓ |
| Audit log | ✗ | ORG_ADMIN | ORG_ADMIN | ORG_ADMIN |
| Accounting | ✗ | ✗ | ✗ | FINANCE |
| Admin panel | ✗ | ✗ | ✗ | SUPER_ADMIN |
| Chat copilot on runs | ✗ | ✓ (10/run) | ✓ (10/run) | ✓ (10/run) |
| Apply suggestions | 2/run | 10/run | ∞ | ∞ |
| Data retention | 30d | 90d | 365d | 365d |

---

## 9. Dead-end journeys worth testing

- Token expired mid-run (JWT force-logout via feature flag epoch bump).
- Payment failed — webhook lands → run credits not granted → UI shows retry CTA.
- OAuth callback with mismatched state → redirected to `/login` with error toast.
- Plan approval after pricing change → 409 conflict, user must re-confirm credit count.
- Attempting to view another user's run via direct URL → 403 access denied.
- Sign-up with email outside enforced domain (ENTERPRISE) → rejected.
