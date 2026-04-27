# ExecuteSpec — Status Page Reference

> Phase 3 deliverable (deferred items batch 2). Companion to
> [`enterprise-sla.md`](enterprise-sla.md). Captures the public status-page
> commitment + the per-component health signals that should drive the page.

## What "live" means

A status page lives at **status.executespec.ai** (CNAME →
**Better Stack** per parked decision #1 in
[`docs/execution-checkpoint.md`](execution-checkpoint.md)). Every
published incident maps to a component below; SLA credits in
`enterprise-sla.md` reference component-level uptime measured here.

## Components

| Component | What it measures | Health signal | Severity rules |
|---|---|---|---|
| **API** (REST surface) | `GET /actuator/health` returns 2xx within 2s | External Pingdom probe (60s interval) from 3 regions. 2/3 must succeed for the minute to count as up. | 1 minute down → **Investigating**. 5 minutes down → **Identified** + Slack page. 15 minutes down → **Major Outage**. |
| **Realtime runs** (V2 pipeline) | A canary run kicks off every 15 minutes; succeeds end-to-end within 8 minutes (LOW spec, anthropic provider) | `executespec.v2.run.outcome` Prometheus counter — alert when `failure / total > 10%` over 30 min | Yellow when canary fails once; Red when 2 consecutive fail. |
| **Plan approval gate** | Median time-to-plan-rendered (PLANNING → AWAITING_APPROVAL transition) | `executespec.v2.planner.latency` p50 — alert when >180s | Yellow > 180s, Red > 600s. |
| **Webhooks (Stripe + Razorpay + GitHub)** | Webhook signature verification rate + processing success | `webhook_event` table inspection (failed_count vs total over 5 min) | Red when failure rate > 5% over 10 min. |
| **Auth (login + OAuth)** | `/api/auth/login` p95 < 500ms; OAuth callback success rate > 99% | Pingdom + 5xx rate from access logs | Yellow > 1s p95; Red on auth failure spike. |
| **Preview environments** | Cloud Run preview start success rate | `executespec.preview.start` outcome counter | Yellow on start-failure rate > 5% over 30 min. |
| **GCP infrastructure** | Cloud SQL availability, Cloud Run platform status | Inherits from GCP status page (proxied as a sub-component) | Mirrored from cloud.google.com/status. |

## Incident severity matrix

| Severity | Description | Customer impact | Response SLA |
|---|---|---|---|
| **P1 — Critical** | Service unavailable for >50% of customers; data integrity at risk; auth flow broken | Customers cannot log in or submit runs | 15-min page → Slack `#incidents` + on-call PagerDuty. Status page banner published within 30 min of detection. |
| **P2 — Major** | Single component degraded, partial customer impact | Slow runs, intermittent failures | 30-min ack → on-call engineer engaged. Status page note within 1 hour. |
| **P3 — Minor** | One non-critical component degraded | Cosmetic, intermittent error in a niche feature | Triaged next business day; status page only if customer-reported. |
| **P4 — Notice** | Planned maintenance / scheduled downtime | None or minimal | Posted ≥7 days in advance. |

Cross-references the [`enterprise-sla.md`](enterprise-sla.md) credit
table — credits accrue based on component-level uptime as reported on
this status page, not subjective "felt slow" reports.

## Subscription mechanics

- Customers can subscribe to status updates via email or webhook on
  status.executespec.ai (Statuspage.io's built-in subscription flow).
- Enterprise customers also get auto-CC on every P1/P2 incident via
  the contact email on their org row.
- Internal Slack `#executespec-status` mirrors public posts.

## Authoring incidents

1. **Detect** — alert fires (Prometheus / Pingdom / customer report).
2. **Acknowledge** in Statuspage within the SLA above. Use the
   templated incident text — Investigating / Identified / Monitoring
   / Resolved.
3. **Update** at least every 30 minutes during P1, every hour during
   P2.
4. **Resolve** with a one-paragraph summary + remediation note.
5. **Postmortem** within 5 business days for P1; within 10 for P2.
   Posted internally + linked from the resolved incident page (no
   customer auto-email — postmortems are pull, not push).

## Status page provider — Better Stack (parked decision #1)

Going with **Better Stack** at $29/mo — cheaper than Statuspage.io's
$79/mo Audience tier, modern UI, native component groups, and
Slack/email subscriptions out of the box. Provider swap is config-only
should we ever need to revisit (a single Pingdom + status-page
re-config day per the parked decision rationale).

### Better Stack setup runbook (M-018)

> Ops-side setup; this section captures the exact steps so the
> first oncall doesn't have to re-derive them. Skip if Better Stack
> is already provisioned.

1. **Account creation** (one-time, billing admin only)
   - Sign up at <https://betterstack.com/uptime> with the
     `admin@executespec.ai` mailbox.
   - Pick the **Team** plan ($29/mo) — covers up to 50 monitors,
     unlimited subscribers, public status page, custom domain.
   - Enable 2FA on the workspace (Better Stack supports TOTP +
     WebAuthn).

2. **Status page** at status.executespec.ai
   - In Better Stack → **Status pages** → **Create status page**.
   - Page title: "ExecuteSpec status".
   - Slug: `executespec` (gives `executespec.betteruptime.com`
     until the CNAME flips).
   - Custom domain: `status.executespec.ai`. Add the suggested
     DNS records to Cloudflare → CNAME `status` →
     `*.betteruptime.com` (Better Stack issues a Let's Encrypt cert
     automatically).
   - Branding: upload the ExecuteSpec wordmark + favicon from
     `website/press-kit/`. Use brand colour `#5B8DEF` (Signal Blue
     per `docs/design/ui-tokens.md`).

3. **Component groups** (mirror the table above)
   - Create one **Component group** for each row in the Components
     table: API · Realtime runs · Plan approval gate · Webhooks
     (Stripe + Razorpay + GitHub) · Auth · Preview environments ·
     GCP infrastructure.
   - Inside each group, create one **Monitor** per probe target.

4. **Monitors** (per group)
   - **API** monitor: HTTP probe `https://api.executespec.ai/actuator/health`
     every 60s from 3 regions (us-east, eu-west, ap-south). Expect
     200 + body containing `UP|DEGRADED`. Alert after 1 failure;
     escalate after 2.
   - **Realtime runs** monitor: Heartbeat URL — receives a `POST` from
     the canary-runner cron in our infra (separate from Better
     Stack; see § Canary runner below). Expect a heartbeat every
     15 minutes; alert after 30 minutes silent.
   - **Plan approval gate** monitor: not directly probable; use a
     Better Stack **Custom alert** that listens for a Prometheus
     webhook posting (see § Prometheus integration).
   - **Webhooks** monitor: Custom alert — Prometheus webhook on
     `webhook_event` failure-rate metric.
   - **Auth** monitor: HTTP probe
     `https://api.executespec.ai/api/auth/health` (returns 200 if
     OAuth providers are reachable). Same cadence as API.
   - **Preview environments** monitor: Custom alert on
     `executespec.preview.start` outcome counter.
   - **GCP infrastructure** monitor: Better Stack's built-in GCP
     status integration (Settings → Integrations → Google Cloud).

5. **Subscriptions**
   - Enable **email subscriptions** on the public page.
   - Enable **Slack incoming webhook** mirror to `#executespec-status`
     (Settings → Integrations → Slack).
   - Enable **Atom/RSS feed** for customer-side scrapers.

6. **Postmortem template**
   - Better Stack → Settings → **Templates** → Add a
     "Postmortem" template (markdown body) matching the structure
     in § Authoring incidents.

7. **Smoke check**
   - Trigger a manual test incident: Better Stack → Incidents →
     **Create incident** → component "API", severity "Investigating".
     Confirm the status page renders the banner + the Slack mirror
     fires + a subscriber test email arrives. Resolve immediately.

### Canary runner

The realtime-runs heartbeat needs an external scheduler. Use
**Cloud Scheduler** in our GCP project (`us-east4`) firing every 15
minutes:

```
HTTP target: https://api.executespec.ai/api/admin/canary/run
Auth header: Bearer <CANARY_BEARER_TOKEN> (rotated quarterly)
Frequency:   */15 * * * *
```

The endpoint kicks a LOW spec into V2RunOrchestrator on the anthropic
provider, polls to terminal, and on success pings Better Stack's
heartbeat URL.

### Prometheus integration (for the metric-driven monitors)

The metric-driven monitors (Plan approval gate, Webhooks, Preview
environments) read off our local Prometheus stack at
`prometheus.executespec.internal`. Better Stack supports
**Alertmanager webhooks** — register Better Stack's webhook URL as
a Prometheus AlertManager receiver, then declare per-metric alerts
using the rules in § Components.

### What this doc DOES NOT do

- Doesn't create the Better Stack account (needs billing-admin
  hands).
- Doesn't write the canary-runner code (separate ticket; the spec
  above is the contract).
- Doesn't change the in-app health endpoints — they already
  return the right thing for Better Stack to probe.

---

*Companion: [`enterprise-sla.md`](enterprise-sla.md) for the SLA + uptime credit schedule that this status page enforces.*
