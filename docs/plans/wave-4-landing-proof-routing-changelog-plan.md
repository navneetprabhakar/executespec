# Wave 4 — Landing + enterprise proof + smart provider routing + changelog

> **Window:** 2026-06-04 → 2026-06-17 (2 weeks)
> **Status:** PLANNED. Schema + scaffolding shipped; fuller implementation incremental.
> **Origin:** [persona-roadmap-2026-04-22.md §5.4](persona-roadmap-2026-04-22.md).

## W4.1 — Live-demo verified-score hero on landing page

**What ships:** replace the current static landing-page hero with an embedded
runner — user types a sample spec, hits "Run demo", sees the plan render in
~5s, then verified score appears in ~90s. Falls back to video if the demo
runner is saturated.

**Implementation path:**
- Add `/api/public/demo-run` endpoint — unauthenticated, rate-limited
  (3 requests/hour per IP), runs against a dedicated sandbox workspace pool
  (pre-provisioned 10 workspaces, round-robin, reset on each use)
- `website/index.html` hero swaps static image for an iframe pointing at
  `demo.executespec.ai/embed` (new subdomain, stateless)
- `demo.executespec.ai` is a thin React SPA that wraps the `/api/public/demo-run`
  SSE stream — shows plan → task progress → verified score on a graduated ring

**Why new subdomain:** CORS + cookie scope isolation. Demo user never touches
the real app's auth state.

**Fallback:** if the workspace pool is exhausted or the API is down, the
iframe swaps to a 60s looped MP4 of a pre-recorded successful demo run.

**Estimated code size:** ~400 LOC backend (pool manager + endpoint) + ~250
LOC frontend (SPA).

## W4.2 — Persona-first pricing page A/B test

**Current state:** 4-tier ladder (FREE / TEAM / MAX / ENTERPRISE). Every
buyer persona in the 2026-04-22 analysis bounced off the wrong tier.

**Variant A (control):** current 4-card layout.
**Variant B (treatment):** 4-persona picker first ("I'm a solo dev" /
"I'm a team" / "I'm an org" / "I'm Enterprise"), each maps to the right
tier card with tailored copy.

**Implementation:**
- PostHog feature flag `pricing.persona-first` (rollout 50/50)
- `PlansPageV2.tsx` with persona-first layout; PlansPage.tsx stays on
  variant A
- Router conditionally renders based on flag
- Measure: `signup` → paid-tier conversion at 30 days post-signup
- Minimum sample: 2,000 signups per variant or 30 days, whichever first
- Success criteria: variant B lifts paid-conversion ≥ 30% with p<0.05

**Flag gate:** safe default off; enable at 10% rollout first for smoke,
then 50/50.

## W4.3 — Smart provider routing as default

**Current:** provider is a required user decision at run-submit time. Most
users don't know which is right.

**New:** `provider: "auto"` becomes the default. Heuristic selects:

```
projectType=spring-boot | node-backend | python-cli   → OPENAI
projectType=node-frontend | react-native-expo         → ANTHROPIC
projectType=static-web | marketing-site               → GEMINI (fast)
runtimeSurface=LIBRARY                                → ANTHROPIC (accuracy)
fallback (unknown type)                               → ANTHROPIC
```

Additional signals:
- Historical success rate for this user+projectType combo (if ≥ 5 runs)
- Current circuit-breaker state (skip providers with open circuit)
- User's BYOK keys — if user has only Anthropic key, force Anthropic

**Implementation:**
- `ProviderRecommendationService` — takes project type + user keys + circuit
  breaker state, returns a single `LlmProvider`
- `PlannerAgentV2` calls service when provider=AUTO
- Frontend dropdown: "Auto" is default + top option; other providers
  listed below "Override"
- Feature flag `provider.smart-routing.enabled` — default off for opt-in
  during Wave 4, flip to default-on after 30 days of data in Wave 5

## W4.4 — Enterprise proof pack (publicly visible)

**Ship:**
- `website/enterprise.html` — single-page procurement funnel
  - SOC 2 status badge (in-progress / Type I / Type II)
  - Anchor price: "from $1,500/mo for 20 seats, $60/seat above"
  - 3 customer logos + 1 case study (placeholders until real customers
    sign off on public use)
  - MSA + DPA template download (PDF, behind email gate to capture lead)
  - Sub-processor registry table
  - Security contact: `admin@executespec.ai` with `SECURITY:` subject prefix
- Already shipped: [`docs/enterprise-one-pager.md`](../enterprise-one-pager.md)
  — checked into repo for email-attachment use

**Manual work:**
- SOC 2 vendor selection (Vanta / Drata / Secureframe) — out-of-band
  procurement decision
- Customer consent for logos (requires signed letter or recorded consent)
- MSA + DPA drafts (legal review)

## W4.5 — VS Code extension (thin)

**Scope deferred to its own plan doc + external repo:**
[`docs/plans/vscode-extension-plan.md`](vscode-extension-plan.md) — new stub
doc in this commit. Implementation happens in a separate `executespec-vscode`
TypeScript repo, published to the VS Code Marketplace. Not in-repo work.

**v1 feature set (from the plan):**
- `ExecuteSpec: Submit spec` — opens a webview with spec input, targets the
  current workspace folder
- `ExecuteSpec: Show last run` — opens a webview with run status + verified
  score
- `ExecuteSpec: Open verified report` — launches the PDF viewer
- Auth: OAuth device-code flow (no PAT paste)

## W4.6 — Changelog + status page

**Changelog:**
- `/changelog` page on `app.executespec.ai` — auto-generated from tagged
  git commits (the repo already uses `[skip ci]` commit message conventions
  well-suited to changelog extraction)
- Simple Markdown render; no CMS needed

**Status page:**
- `/status` — `statusfy.co` or `Statuspage.io` embed (external service)
- Prometheus scrape at `/actuator/prometheus` + Grafana alerts already
  exist; status page is customer-facing summary (not full ops dashboard)

## What's committed in Wave 4 (first pass)

- This plan doc
- `docs/plans/vscode-extension-plan.md` (stub)
- `website/enterprise.html` placeholder (linked from main pricing page)
- Migration V128 adding `provider_recommendation_log` table (for smart
  routing observability)
- `ProviderRecommendationService` skeleton (returns hard-coded defaults
  for now; heuristic + history-based scoring in follow-up)
- `pricing.persona-first` + `provider.smart-routing.enabled` feature
  flags seeded (both OFF)

Subsequent commits add the full demo-runner + pricing A/B + landing-page
hero swap.

## Success criteria

- [ ] `provider=auto` works on new runs (hard-coded heuristic OK for v1)
- [ ] Persona-first PlansPage renders under feature flag
- [ ] Enterprise page linked from main site, lists tier comparison + SOC 2 status
- [ ] /changelog page lists last 20 commits in user-readable format

## Non-goals

- Full SOC 2 Type I audit (external work, Q3 2026)
- Customer logos on enterprise page (requires real consent)
- Live demo runner in production (Wave 4 can stub with a video if API
  isn't ready)
- Real A/B framework (GrowthBook / Statsig) — hand-rolled cohort via
  PostHog flag is enough for v1
