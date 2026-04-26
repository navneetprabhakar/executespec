# ExecuteSpec — Status Page Reference

> Phase 3 deliverable (deferred items batch 2). Companion to
> [`enterprise-sla.md`](enterprise-sla.md). Captures the public status-page
> commitment + the per-component health signals that should drive the page.

## What "live" means

A status page lives at **status.executespec.ai** (CNAME → Statuspage.io
or Better Stack — provider TBD by ops). Every published incident maps
to a component below; SLA credits in `enterprise-sla.md` reference
component-level uptime measured here.

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

## Status page provider — open decision

Statuspage.io ($79/mo for the audience tier we'd start with) vs.
Better Stack ($29/mo, more modern UI, supports component groups).
Both expose the API we need. Pick whichever ops prefers; this doc
doesn't lock the choice.

---

*Companion: [`enterprise-sla.md`](enterprise-sla.md) for the SLA + uptime credit schedule that this status page enforces.*
