# Activation instrumentation — 6-event funnel

> **Scope:** instrument the signup → first-run-success funnel so every pricing / tier / feature decision stops being vibes-based. First 6 events only — not a full analytics project.
> **Origin:** PM-view persona analysis 2026-04-22. Ranked P0 #1 because it **unblocks every other decision** — we can't measure if TEAM is converting, we can't measure the TTFV-reduction impact, we can't measure PH launch without this.
> **Target ship:** 2026-05-07 → 2026-05-20 (2 dev-weeks, 10 working days). Starts after TEAM tier ships so we measure the new funnel, not the old one.
> **Status:** PLANNED, not started.
> **Last updated:** 2026-04-22.
> **Related:** [`team-tier-plan.md`](team-tier-plan.md), [`ttfv-reduction-plan.md`](ttfv-reduction-plan.md), [`persona-roadmap-2026-04-22.md`](persona-roadmap-2026-04-22.md).

## 1 · Why this exists

From the PM-view persona analysis:
> "I don't see evidence of an instrumented signup → first-run → first-verified-score-above-80 → second-run → invite-teammate funnel. That's the single most important dashboard for a SaaS PM in year 1."

Without this, we cannot answer any of:
- Is TEAM converting? (are orgs on TEAM plan actually running specs?)
- Does the TTFV-reduction work? (is signup→first-run time shorter?)
- Does persona-first pricing A/B win? (paid conversion delta?)
- Did Product Hunt convert? (day-7 return rate by signup source?)
- Where's the single biggest drop-off in the funnel? (where to invest next?)

GA4 is partially wired (`G-Y6JSSQ2Y83` in `website/index.html`) for marketing-site traffic but nothing tracks in-app events. This plan adds in-app instrumentation focused on the 6 events that matter most.

## 2 · The 6 events

| # | Event | Fires when | Why it matters |
|---|---|---|---|
| 1 | `signup` | `AuthService.register()` succeeds OR `findOrCreateFromOAuth()` creates a new user | top of funnel — baseline for every other rate |
| 2 | `project_created` | first `ProjectRepoService.createProject` succeeds for a user | activation step 1 — did they even try to use the product? |
| 3 | `spec_submitted` | `POST /api/runs` returns 202 for a user's first run | activation step 2 — did they write a real spec? |
| 4 | `plan_approved` | user approves the plan gate (`POST /api/runs/{id}/approve`) on their first run | activation step 3 — did the plan look credible enough to spend a credit? |
| 5 | `run_complete` | pipeline terminates with status `COMPLETE` or `PARTIAL` on their first run | activation step 4 — did they see a verified score > 0? |
| 6 | `pr_merged` | GitHub webhook confirms merge of a PR originating from their run | **the "aha"** — their AI-generated code is now in their repo |

**Rule:** each event fires exactly once per user (the *first* time). Repeat actions (user's 2nd run, 3rd project) are counted in a separate `repeat_action` event with a `nth` property — tracked but not in the headline funnel.

## 3 · Cohort dimensions

Every event carries:

```
{
  user_id,           // app_user.id
  org_id,            // app_user.org_id, nullable
  tier,              // FREE | PRO | TEAM | MAX | ENTERPRISE (at event time)
  signup_method,     // 'email' | 'google' | 'github'
  signup_source,     // 'organic' | 'ph' | 'paid-google' | 'paid-meta' | referral domain | null
  signup_date,       // date user created account (for time-to-event measurement)
  ai_provider,       // project's default provider (for spec_submitted onwards)
  run_complexity,    // LOW | MEDIUM | HIGH | MOBILE (for spec_submitted onwards)
  project_type,      // spring-boot | node-frontend | etc. (for spec_submitted onwards)
  experiment_cohort, // A/B test bucket if applicable
  ts,                // ISO 8601 timestamp
}
```

Cohort on any dimension to ask questions like:
- "Of users who signed up via GitHub OAuth on the PH launch day, what % hit `run_complete` within 7 days?"
- "TEAM tier users — average time from signup to plan_approved?"
- "Anthropic-provider runs vs OpenAI-provider runs — does verified-score gap affect repeat runs?"

## 4 · Stack — PostHog Cloud

**Why PostHog Cloud:** open-source, SaaS-hosted (no infra to maintain), $0 on the free tier up to 1M events/month (we're well under). Clean product-analytics UX. Already-wired client library for JS + Node. Trivial to self-host if we outgrow free tier.

**Alternatives considered:**
- **Mixpanel** — similar product, more expensive at scale. No strong reason to prefer.
- **Amplitude** — strong for SaaS. Free tier tighter.
- **Plausible / Simple Analytics** — privacy-first web analytics, not product analytics. Wrong tool.
- **Self-hosted (OSS) PostHog** — possible but adds infra maintenance. Revisit if >1M events/mo.
- **Roll our own via `run_event_log`** — we have a `run_event_log` table (V68). Could extend to capture signup/project/PR events, but rebuilding the cohort-analysis UI over SQL is 10× the work. Use PostHog.

**Data residency:** PostHog Cloud has US + EU regions. US for now (matches US-first pivot). If EU customers request data residency later, we migrate.

## 5 · Scope — what ships

**IN scope:**
1. PostHog project created on Cloud, API key in env var `POSTHOG_API_KEY` + `POSTHOG_HOST`
2. Backend `EventTrackingService` using `posthog-java` SDK
3. Event firing from 6 exact code sites (listed in §6)
4. Per-event cohort property resolution (look up tier, signup method, source at event time)
5. Idempotency — each event fires at most once per user (track via new `user_activation_event` table OR PostHog's built-in `$set_once`)
6. PostHog dashboard — 4 panels: funnel (6 events), conversion rate, time-to-event, cohort breakdown by signup_method
7. `signup_source` attribution — capture `utm_source` / `ref` from signup URL, persist to `app_user.signup_source` (new column)
8. Frontend: PostHog JS SDK only for pageview tracking (supplements but does not replace backend events)
9. Admin-facing panel at `/admin/analytics` with live funnel numbers (for the founder, not customers)
10. Documentation: new `docs/reports/activation-funnel-baseline.md` captured on Day 10 for post-launch comparison

**OUT of scope for v1:**
- Revenue analytics (separate doc if we go deep — Stripe dashboard handles basic MRR/churn)
- Retention curves (PostHog default retention view is enough; custom later)
- Session replay (privacy concern, defer to v2 with explicit opt-in)
- Frontend feature usage tracking (clicks, hovers — overkill for now)
- Slack alerts on funnel drops (nice to have, defer to v2)

## 6 · Exact code surfaces

### 6.1 Backend — EventTrackingService (new)

```
src/main/java/com/navneet/executespec/analytics/EventTrackingService.java
src/main/java/com/navneet/executespec/analytics/EventName.java (enum SIGNUP, PROJECT_CREATED, SPEC_SUBMITTED, PLAN_APPROVED, RUN_COMPLETE, PR_MERGED)
src/main/java/com/navneet/executespec/analytics/UserActivationEvent.java (JPA entity — idempotency)
src/main/java/com/navneet/executespec/analytics/UserActivationEventRepository.java
```

Service signature:
```java
@Async
public void track(String userId, EventName event, Map<String, Object> properties);

public boolean isFirstTime(String userId, EventName event); // uses user_activation_event table
```

### 6.2 Event firing sites

| Event | File | Method | Call |
|---|---|---|---|
| `signup` | `AuthService.java:~93` | `register()` end | `eventTracking.track(userId, SIGNUP, propsFor(user))` |
| `signup` | `AuthService.java:~395` | `findOrCreateFromOAuth()` branch 3 | same |
| `project_created` | `ProjectRepoService.java::createProject` | end of method | `if (isFirstTime) track(...)` |
| `spec_submitted` | `V2RunOrchestrator.java::submitRun` | after persistence | `if (isFirstTime) track(...)` |
| `plan_approved` | `V2RunOrchestrator.java::approve` | after gate release | `if (isFirstTime) track(...)` |
| `run_complete` | `V2RunOrchestrator.java::terminate` | when status = COMPLETE or PARTIAL | `if (isFirstTime) track(...)` |
| `pr_merged` | `GitHubWebhookController.java::handle` | when PR merge confirmed | `if (isFirstTime) track(...)` |

### 6.3 Schema

```sql
-- Flyway V124
CREATE TABLE user_activation_event (
  user_id VARCHAR(64) NOT NULL REFERENCES app_user(id),
  event_name VARCHAR(32) NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  properties_json JSONB,
  PRIMARY KEY (user_id, event_name)
);
CREATE INDEX idx_uae_event_occurred ON user_activation_event(event_name, occurred_at);

ALTER TABLE app_user
  ADD COLUMN signup_source VARCHAR(64),
  ADD COLUMN signup_utm_source VARCHAR(64),
  ADD COLUMN signup_utm_campaign VARCHAR(128);
```

### 6.4 Frontend

- `frontend/index.html` — PostHog JS SDK `<script>` (10 lines, lazy-loaded)
- `frontend/src/lib/analytics.ts` (new) — thin wrapper exposing `identify(userId, traits)`, `page()`, `capture(event, props)`
- `frontend/src/pages/auth/RegisterPage.tsx` + `LoginPage.tsx` — read `?utm_source=` / `?ref=` from URL, forward to `/api/auth/register` as extra fields
- `frontend/src/App.tsx` — on auth state change, call `analytics.identify(user.id, {tier, orgId})`

### 6.5 Admin UI

- `frontend/src/pages/admin/AnalyticsPage.tsx` (new, `/admin/analytics`)
- Two iframes embedding PostHog dashboards via shareable URLs (simpler than re-implementing the UI)
- One table: per-day signups from `user_activation_event` for last 30 days (direct DB read, bypasses PostHog for the raw number)

## 7 · Day-by-day execution (2026-05-07 Thu → 2026-05-20 Wed, 10 working days)

Days assume Mon-Fri; if weekends get used it's a bonus.

**Day 1 (May 7, Thu):** PostHog setup
- Create PostHog Cloud project + capture API key
- Add `POSTHOG_API_KEY` + `POSTHOG_HOST` to `application.yml` + `application-prod.yml`
- Add `posthog-java` to `pom.xml`
- Smoke test: `curl posthog capture` endpoint with a test event
- `analytics` package skeleton + `EventName` enum

**Day 2 (May 8, Fri):** Schema + EventTrackingService
- Flyway V124 migration
- `UserActivationEvent` JPA entity + repository
- `EventTrackingService` with `@Async` `track()` + `isFirstTime()` methods
- Unit tests: double-firing same event for same user only records once

**Day 3 (May 9, Sat):** Event firing — signup + project_created
- Hook `SIGNUP` into both `AuthService.register()` and `findOrCreateFromOAuth()`
- Hook `PROJECT_CREATED` into `ProjectRepoService.createProject`
- `signup_source` + `utm_source` capture on `RegisterRequest` + `OAuth` paths
- End-to-end test on local: register → create project → 2 events in PostHog + 2 rows in `user_activation_event`

**Day 4 (May 10, Sun):** Event firing — spec_submitted + plan_approved
- Hook `SPEC_SUBMITTED` into `V2RunOrchestrator.submitRun`
- Hook `PLAN_APPROVED` into `V2RunOrchestrator.approve`
- Edge cases: SINGLE_SHOT classification (no plan gate — `plan_approved` auto-fires with `autoApproved: true`)

**Day 5 (May 11, Mon):** Event firing — run_complete + pr_merged
- Hook `RUN_COMPLETE` into `V2RunOrchestrator.terminate` (only on `COMPLETE` or `PARTIAL`)
- Hook `PR_MERGED` into `GitHubWebhookController.handle`
- End-to-end smoke on local: full pipeline signup → PR merged, verify all 6 events captured

**Day 6 (May 12, Tue):** Frontend instrumentation
- PostHog JS SDK in `frontend/index.html`
- `frontend/src/lib/analytics.ts` wrapper
- `identify()` on login + register success
- `page()` on route change (React Router hook)
- UTM capture on marketing landing → app signup flow

**Day 7 (May 13, Wed):** Admin UI + baseline dashboard
- `/admin/analytics` page with PostHog iframes
- Build the PostHog funnel dashboard (drag 6 events, step chart)
- Cohort breakdown: signup_method, signup_source, tier
- Time-to-event: median + p75 + p95 for each step

**Day 8 (May 14, Thu):** QA + deploy to dev
- Full E2E test on dev with a clean test user
- Verify no PII leaks (no email, no IP, no user-entered spec content) — only user_id + event + tier
- Verify idempotency: re-run the full flow with the same user, events don't double-count
- Deploy to dev.executespec.ai

**Day 9 (May 15, Fri):** Prod cutover + baseline
- PR dev → main, merge, deploy prod
- Verify events fire in PostHog prod project
- Let events accumulate for ~24h

**Day 10 (May 20, Wed):** Baseline + handoff
- (Gap of weekend + Monday/Tuesday lets real user data accumulate for 5 days)
- Capture baseline in `docs/reports/activation-funnel-baseline.md`:
  - Signup rate, project_created rate, spec_submitted rate, plan_approved rate, run_complete rate, pr_merged rate
  - Median time per step
  - Cohort breakdown by signup_method
- Set baseline as the "before" side of every future pricing / tier / feature A/B test

## 8 · Privacy + compliance

- **No PII in event properties** — `user_id` is the only identifier. No email, no name, no IP, no spec content.
- **User can opt out** — add `POST /api/auth/me/analytics-opt-out` endpoint that sets `app_user.analytics_opted_out = true`. When true, `EventTrackingService.track()` becomes a no-op for that user.
- **Retention** — PostHog default 7 years (configurable). We retain `user_activation_event` locally for 2 years then aggregate to daily counts.
- **Privacy policy** — add "We use PostHog for product analytics. No personal data is sent. You can opt out in Settings." to `website/privacy.html` + `frontend/src/pages/PrivacyPage.tsx`.
- **GDPR DSAR** — on user delete request, delete the PostHog `user_id` via PostHog's delete endpoint + DELETE from `user_activation_event`.

## 9 · Success metrics (meta — did the instrumentation work?)

| Metric | Target | How measured |
|---|---|---|
| Events fire on expected actions | 100% (no missing hooks) | End-to-end tests + manual spot-check for 5 users |
| Event idempotency | 100% | Replay a full pipeline for a user, verify no double-count |
| Latency impact on signup/run | < 10ms added | Compare mean response time before/after deploy |
| No PII in PostHog events | 0 PII fields | Manual audit on Day 10 |
| Baseline captured | Yes, with all 6 rates + medians | `docs/reports/activation-funnel-baseline.md` exists and is current |

## 10 · After baseline — decisions this unlocks

The first questions we'll be able to answer with data (instead of guesses):

1. **Where's the biggest drop?** Hypothesis: `spec_submitted → plan_approved` because the plan looks intimidating to a first-time user. Data will confirm or reroute.
2. **Does signup method matter?** Hypothesis: Google OAuth signups convert 2× email. If true, push Google OAuth harder in the signup CTA.
3. **Does PH traffic convert?** Hypothesis: PH signups churn 85% by day 7. If true, de-prioritize PH-style launches in favor of content/SEO.
4. **Is TEAM converting?** Hypothesis: TEAM orgs hit `run_complete` 4× faster than PRO (team velocity). Data drives the tier-focus copy on the landing page.

## 11 · Pre-flight checklist

- [ ] PostHog Cloud account created (Navneet)
- [ ] Project + API key noted (Navneet)
- [ ] `POSTHOG_API_KEY` env var plumbed through `application-prod.yml` (Navneet or ops)
- [ ] Decision: track by `user_id` only, or also `distinct_id` that survives logout? (recommend user_id-only)
- [ ] Flyway V124 column names confirmed (no conflict with existing schema)
- [ ] Privacy page copy draft reviewed

## 12 · What this plan deliberately does NOT do

- **No revenue dashboard.** Stripe dashboard + `run_cost_log` handle it. Don't build a third surface.
- **No session replay.** Privacy risk, low ROI at this scale.
- **No customer-facing "your usage" analytics.** That's a product feature, not an instrumentation project.
- **No Slack alerts on funnel drops.** Nice, but requires tuning and a pager rotation. Defer.
- **No A/B test framework.** We'll hand-roll cohort comparisons for the first few experiments. If we run 10+ experiments, revisit with GrowthBook or PostHog's native A/B feature.
