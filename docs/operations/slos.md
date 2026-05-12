# Service-Level Objectives (SLOs)

> **Created:** 2026-05-06.
> **Last reviewed:** 2026-05-12 (1.0.0-M1).
> **Status:** PROPOSED — alert rules + Grafana dashboard land alongside the
> on-call rotation in the next pass.
> **Audience:** founding eng team + incoming on-call rotation.

These are the production health targets ExecuteSpec commits to. Each SLO
has a clearly stated indicator (the metric we measure), a target (the
acceptable level over a 30-day rolling window), an alert threshold (when
we page someone), and an owner.

The bar: **all 5 SLOs must be GREEN over 30 consecutive days before
GA cutover** (per §11 of the GA-readiness plan).

---

## SLO 1 — Run reaches a terminal status within 15 minutes

**Why.** A run stuck in `WORKING` / `REVIEWING` past 15 minutes is a
poor customer experience and almost always indicates a JVM crash, an
LLM timeout cascade, or a stuck plan-approval gate. Hierarchical
planner LARGE-band timeout is 20 minutes, so we set the SLO at 15
minutes which is still safely above the 95% historical p95.

**Indicator.** Of all `pipeline_run` rows that started in the window,
percent that reached a terminal status (`COMPLETE` / `PARTIAL` /
`FAILED` / `CANCELLED`) within 15 minutes of `started_at`.

```sql
-- Run this query on a 7-day rolling window for the SLO calc.
SELECT
  100.0 * SUM(CASE WHEN ended_at IS NOT NULL
                   AND ended_at - started_at <= INTERVAL '15 minutes'
                   THEN 1 ELSE 0 END)
        / COUNT(*) AS pct_within_15min
FROM pipeline_run
WHERE started_at >= NOW() - INTERVAL '7 days'
  AND status IN ('COMPLETE', 'PARTIAL', 'FAILED', 'CANCELLED');
```

| Bar | Target |
| --- | ------ |
| **GA cutover requirement** | ≥ 95% over 30d rolling |
| **Stretch (post-GA)** | ≥ 99.5% |

**Alert.** Page on-call when 3 consecutive 1h windows fall below 90%.
Dashboard: `executespec-slos.json` panel "Run terminal in <15min".

**Metric source.** `pipeline_run.ended_at` (DB) +
`executespec.v2.run.latency` Prometheus histogram.

**Owner.** Backend/AI lead.

---

## SLO 2 — Verified score reflects true delivery

**Why.** Audit Risk #1 (catastrophic): if the dashboard shows 45/100
on working code, customers churn. After the P1.1 + P1.2 fixes, we
need confidence the score is now anchored on real signals.

**Indicator.** On a weekly random-sample audit of 10 COMPLETE runs,
what fraction of `verifiedScore` values fall within ±10 points of the
human-graded score (manual review by the on-call engineer).

| Bar | Target |
| --- | ------ |
| **GA cutover requirement** | ≥ 90% (i.e. ≥ 9 of 10 in agreement) |
| **Stretch** | ≥ 95% |

**Process.** Friday afternoon — engineer pulls 10 random runs from
the past week, walks the validation report + the actual diff in
GitHub, scores 0-100 by hand. Disagreements logged; recurring
disagreement patterns drive a follow-up to the formula.

**Alert.** No automatic alert (manual process); track in a `metrics`
sheet shared with the team.

**Metric source.** Manual + `validation_report.verified_score`.

**Owner.** Backend/AI lead.

---

## SLO 3 — Cost estimate vs actual within 30%

**Why.** Audit Risk #6 (medium-high): customers will dispute a $20
charge if the pre-approval estimate said $5. The 2026-04-19 estimator
recalibration closed the gap from −42% to a documented band; we
need to confirm it stays there.

**Indicator.** Per row in `run_cost_log` (which logs estimate +
actual at run-end), percent where `|actual - estimate| / estimate <=
0.30`.

```sql
SELECT
  100.0 * SUM(CASE WHEN ABS(actual_cost_usd - estimated_cost_usd)
                          / NULLIF(estimated_cost_usd, 0) <= 0.30
                   THEN 1 ELSE 0 END)
        / COUNT(*) AS pct_within_30pct_band
FROM run_cost_log
WHERE created_at >= NOW() - INTERVAL '7 days';
```

| Bar | Target |
| --- | ------ |
| **GA cutover requirement** | ≥ 80% within ±30% |
| **Stretch** | ≥ 90% |

**Alert.** Page when more than 20% of runs in any rolling 24h window
are >50% off (either direction).

**Metric source.** `run_cost_log` (DB).

**Owner.** Backend/AI lead.

---

## SLO 4 — Webhook processed within 60 seconds

**Why.** GitHub PR webhooks drive the merge-status update + the
post-merge cascade banner. Razorpay/Stripe webhooks drive subscription
state. A webhook delayed > 60s leaves customers staring at stale UI
and risks billing-state desync.

**Indicator.** Per row in `webhook_event` (V147 hash chain audit
log), percent where `processed_at - received_at <= 60s`.

| Bar | Target |
| --- | ------ |
| **GA cutover requirement** | ≥ 99% |
| **Stretch** | ≥ 99.9% |

**Alert.** Page when any single webhook takes > 5 minutes (real lag,
not just slow), OR when the 99% target slips below 95% in a 1h window.

**Metric source.** `webhook_event` (DB) + `executespec.v2.webhook.*`
meters.

**Owner.** Full-stack lead.

---

## SLO 5 — Hot-path 5xx rate stays below 0.5%

**Why.** "Just keep the lights on." A page rendering `/projects/{id}/dashboard`
that 500s makes the product look broken regardless of what the agent
is doing.

**Indicator.** Per Prometheus `http_server_requests_seconds_count`
filtered to:
- Method: `GET` (read paths only — POST/PUT/DELETE have legitimate
  user-input-driven errors that we don't count toward this SLO).
- URI: in the hot-path allowlist (`/api/projects/*/dashboard`,
  `/api/runs/*`, `/api/runs/*/report`, `/api/billing/plans`,
  `/api/billing/status`, `/api/auth/me/permissions`).
- Status: 5xx vs 2xx ratio.

| Bar | Target |
| --- | ------ |
| **GA cutover requirement** | < 0.5% over 30d rolling |
| **Stretch** | < 0.1% |

**Alert.** Page on > 2% sustained for 10 minutes.

**Metric source.** Prometheus `http_server_requests_seconds_count` +
Spring Boot Actuator.

**Owner.** Backend/AI lead.

---

## Burn-down rules

1. Track each SLO on a 30d rolling window. Mark each day RED / AMBER /
   GREEN based on the alert thresholds above.
2. Two consecutive RED days on any SLO → file an incident, post in
   `#exec-spec-eng`, schedule a fix in the current sprint.
3. Three consecutive RED weeks → SLO becomes a sprint blocker until
   GREEN-trajectory restored.
4. Stretch targets are aspirational; missing them is not an incident,
   but they drive the post-GA SLO ratchet.

## SLO ownership matrix

| SLO | Owner | Backup |
| --- | ----- | ------ |
| 1 — Run terminal in 15min | Backend/AI lead | Founder |
| 2 — Verified score correct | Backend/AI lead | Frontend lead |
| 3 — Cost estimate within 30% | Backend/AI lead | Founder |
| 4 — Webhook in 60s | Full-stack lead | Backend/AI lead |
| 5 — Hot-path 5xx < 0.5% | Backend/AI lead | Founder |

## What's NOT an SLO yet

- LLM provider success rate (depends on Anthropic / OpenAI / Gemini —
  we'd need to commit to a target we can't actually control).
- Frontend bundle size (a build-time concern, not runtime SLO).
- TTFB / Core Web Vitals (worthy goals; track separately on the
  marketing site, not the app).
- Semgrep static-analysis runtime (best-effort — graceful degradation
  is the contract, see `codereview/SemgrepRunner`).

These may be promoted to SLOs post-GA once we have baseline data.

---

## Alert rules — to be added in `observability/grafana/alerts/`

Each SLO above will spawn a corresponding YAML rule in the Grafana
config. This file is the canonical source — the alert YAML is
generated/maintained to match. P4.2-followup: wire each rule + page
target (PagerDuty integration key TBD; ops to confirm pricing tier).

## Customer-facing rollup

A subset of these (1, 4, 5) appears on `status.executespec.ai` (P4.3)
as up/down/degraded indicators. Customers don't see the actual SLO
percentage — that's an internal trust commitment. They see "API
healthy / degraded / outage" derived from the SLO state.
