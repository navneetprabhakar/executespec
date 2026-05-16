# Enterprise SLA — ExecuteSpec

> Service Level Agreement summary for customers on the **ENTERPRISE** tier.
> Last refreshed: 2026-05-16. Code-backed claims cite the file or migration
> that implements them. Anything not citable is marked **policy /
> contractual** — verify against the executed customer agreement before
> quoting.

PRO and TEAM customers receive best-effort email support; this document does
not apply to them.

---

## 1. Uptime target

**Policy / contractual.** The platform aims for 99.9 % monthly uptime on
the API + UI surface (`*.executespec.ai`), measured by the same probe
endpoints that back the public status page. The contracted target lives in
the executed Master Services Agreement; this number is informational.

**Code-backed monitoring**: the platform exports liveness / readiness via
Spring Boot Actuator. `SecurityConfig` (`auth/security/SecurityConfig.java`
lines 167-170) leaves `/actuator/health` and `/actuator/prometheus` open
without authentication, so external probes can poll them. The Prometheus
scrape feeds the Grafana dashboards under `observability/grafana/` and the
GCP Cloud Monitoring exporter (`@Profile("gcp")`).

The status page itself is rendered from the same probe data plus the most
recent five published SLO observations.

---

## 2. Performance SLOs

The five published Service Level Objectives are in
[`docs/operations/slos.md`](operations/slos.md). The headline targets are:

1. **Run terminal in 15 min** — ≥95 % of submitted runs reach a terminal
   status within 15 minutes.
2. **Verified-score correctness** — ≥90 % of `verifiedScore` values must
   agree with a manual re-grade within ±5 points.
3. **Cost estimate accuracy** — the buffered cost estimate quoted at the
   plan gate must be within 30 % of the realised cost on ≥80 % of runs.
4. **Webhook delivery** — ≥99 % of GitHub PR webhooks update the linked
   `pull_request_record` within 60 seconds of arrival.
5. **Hot-path 5xx** — overall 5xx rate on the customer-facing API surface
   must be under 0.5 %.

The SLO breach-handling workflow (alerting, incident-runbook, postmortem
cadence) is documented alongside the targets in the linked file. This SLA
does not duplicate it.

---

## 3. Data residency

**All customer data is stored in Google Cloud us-east4** (Ashburn, Virginia,
US). The region is hard-coded in `application.yml` (lines 482-485) and
duplicated in the dev / prod profiles (`application-dev.yml:97-100`,
`application-prod.yml:99-102`):

```yaml
executespec:
  gcp-region: us-east4
  artifact-registry:
    host: us-east4-docker.pkg.dev
```

Storage components:

| Component                  | Service                   | Region                            |
| -------------------------- | ------------------------- | --------------------------------- |
| Database                   | Cloud SQL (PostgreSQL 15) | us-east4                          |
| Cache + queues             | Memorystore / Redis       | us-east4                          |
| Workspace files (runtime)  | GCE VM persistent disk    | us-east4-a                        |
| Workspace ship (long-term) | Google Cloud Storage      | us-east4 (multi-region default off) |
| Container images           | Artifact Registry         | us-east4 (`us-east4-docker.pkg.dev`) |
| Cloud Run previews         | Cloud Run                 | us-east4                          |

**Outbound traffic to sub-processors** (Anthropic / OpenAI / Google AI /
Stripe / Razorpay / GitHub) crosses the public internet. See
[`docs/dpa-flow.md`](dpa-flow.md) §6 for the data-per-processor breakdown.
Each provider's regional commitment is **policy / contractual** with that
provider and outside ExecuteSpec's direct control.

**EU residency**: not offered today. Enterprise customers requiring EU-region
data residency should treat this as a contractual gap; the technical
feasibility (deploying a parallel europe-west cluster) exists but the
single-region commit is hard-coded throughout `application*.yml`.

---

## 4. Support response time

**Policy / contractual.** The standard ENTERPRISE response-time matrix:

| Severity                                       | Target initial response | Target resolution / workaround |
| ---------------------------------------------- | ----------------------- | ------------------------------ |
| S1 — production down, customer business impact | 1 business hour         | 8 business hours                |
| S2 — major feature degraded, workaround exists | 4 business hours        | 2 business days                 |
| S3 — minor degradation                         | 1 business day          | 5 business days                 |
| S4 — question or change request                | 2 business days         | Best-effort                     |

"Business hour" means weekday 09:00–18:00 US Eastern.

The customer contact for support is `admin@executespec.ai`. There is no
ticketing system in code; tickets are tracked in the customer's contracted
JIRA project (configured via `app.contact.jira.*` in `application.yml:687-694`
when ExecuteSpec is the JIRA tenant) or by email if no JIRA is configured.

---

## 5. Maintenance windows & change management

**Policy.** Routine maintenance (rolling restarts, schema migrations, library
upgrades) is performed during the **Sunday 03:00–05:00 US Eastern** window.
ENTERPRISE customers are notified at least 48 hours in advance for any
maintenance window expected to cause ≥30 seconds of user-visible
unavailability.

Emergency maintenance (security patches, P0 bug fixes) is performed
immediately with retrospective notification.

**Code-backed change management**:

- Every database schema change ships as a Flyway migration under
  `src/main/resources/db/migration/V*.sql`. Migration head is V199.
  Migrations are immutable after apply (operational rule:
  never modify an already-applied migration; add a follow-up
  `V_next` instead).
- Every backend / frontend change ships through the CI pipeline at
  `.github/workflows/deploy.yml`. The deploy artifact is a single Spring
  Boot fat JAR + the built frontend bundle served from the same JAR's
  `static/` directory.
- Feature flips that require runtime control go through `FeatureFlagService`
  (`config/featureflag/`) — no need for a deploy. Examples: `v2.pause.enabled`,
  `ci.autofix.enabled`, `security.guardrail_level`, `force_logout_epoch`.

---

## 6. Incident reporting

**Code-backed pieces**:

- **Operator alerting** — the platform's operator (internal staff) is paged
  via the Telegram bridge (`chat_id=6475810501`, bot `8489535450`) on every
  status-count change. This is internal-only and is not a customer-facing
  channel.
- **Customer alerting on run lifecycle** — `Notification` rows are inserted
  on run terminal, gate-open, quota-warning events. ENTERPRISE customers
  using Slack get the same data piped via the Slack integration
  (`/api/integrations/slack/*`).
- **Webhook delivery** — GitHub PR + `check_run` events flow into
  `/webhooks/github` (HMAC SHA-256 verified). When CI fails on a PR the
  `ci.autofix.enabled` flag governs whether the platform auto-attempts a
  fix.

**Policy / contractual**:

- **Customer-facing incident page**: hosted at `https://status.executespec.ai`
  (out of repo). The five published SLOs feed this page.
- **Email notifications for S1/S2 incidents**: sent to the customer's
  designated admin contacts within the response time matrix above
  (§4).
- **Postmortems**: a written postmortem is delivered within 5 business days
  for any S1 incident. Format follows the same template the internal team
  uses for `docs/operations/postmortems/`.

There is no PagerDuty integration today. Customers who need their own
PagerDuty / Opsgenie webhook can configure it against the Slack integration
output or by polling `/api/notifications`.

---

## 7. Backup & disaster recovery

**Code-backed**:

- **Database** — Cloud SQL automated backups run daily at 03:00 UTC
  (default Cloud SQL retention 7 days; ENTERPRISE deployments override to
  35 days — **operator configuration, not in repo**).
- **Workspace files** — runtime workspaces are ephemeral on the GCE VM.
  Long-term run artefacts ship to GCS with `tier_config.retention_days` as
  the soft-delete window (ENTERPRISE = 365 days,
  `auth/entity/TierConfig.java:85-87`).

**Policy / contractual**:

- **Recovery Time Objective (RTO)**: ≤4 hours for the platform API + UI.
- **Recovery Point Objective (RPO)**: ≤24 hours for transactional data
  (matches the daily Cloud SQL backup cadence).
- DR exercises are run quarterly internally; results are not published but
  are available on request.

---

## 8. Security incident response

The reporting channel is `security@executespec.ai` (**policy** — verify the
mailbox is monitored). Anyone discovering a vulnerability should email
this address before any public disclosure.

The platform's defence-in-depth controls (see project `CLAUDE.md` "Security"
section for the 8-layer summary) are code-enforced. Key items:

- 3 guardrail levels (`LOW | MEDIUM | HIGH`) flipped via the
  `security.guardrail_level` feature flag (`config/featureflag/`).
- Container sandbox: `ShellTool` and `BuildProjectTool` route shell + build
  invocations through `--read-only --cap-drop ALL` containers with an
  env-var whitelist. Cleanroom runs (SWE-bench / ProgramBench) additionally
  use `--network none`.
- Input sanitisation pipeline (`InputSanitizer`) — NFKC normalisation,
  zero-width strip, base64 decode-and-scan before LLM ingest.
- LLM prompt-injection defence + code-abuse scanning before any
  generated artefact lands on disk.
- Webhook signatures: GitHub HMAC SHA-256, Razorpay HMAC, Stripe
  `Stripe-Signature`.

For the GDPR / DPDP / CCPA data-subject rights, see
[`docs/dpa-flow.md`](dpa-flow.md).

---

## 9. Exclusions

The uptime target (§1) does **not** apply during:

- Scheduled maintenance windows announced ≥48 hours in advance.
- Force majeure events (regional cloud-provider outages, internet routing
  failures upstream of the GCP edge).
- Customer-caused incidents (e.g. an ORG_ADMIN flipping `sso_enforced =
  TRUE` without first passing the M-016 lockout-prevention guard — see
  `docs/sso-saml-wiring.md` §5).
- Suspension for non-payment or material breach of the customer agreement.

**Sub-processor downstream availability** (Anthropic / OpenAI / Google AI /
GitHub / Stripe / Razorpay) is excluded from the ExecuteSpec uptime
calculation. The platform's fallback chain (`model-matrix.failover-order`
in `application.yml`) cushions short-lived provider outages by routing to
an alternate model, but a full multi-provider outage will surface as a
failed run.

---

## 10. Service credits

**Policy / contractual.** The standard remedy for a missed uptime target
is a service credit against the next monthly invoice, scaled by the
severity of the miss. Exact percentages live in the executed customer
agreement. This SLA does not enumerate them.

There is **no automated service-credit issuance** in code — credit
adjustments go through `PaymentAuditService` as a `MANUAL_OVERRIDE` event,
applied by a SUPER_ADMIN after the customer reports a miss.

---

## 11. Termination & data return

**Policy / contractual.** On termination, ExecuteSpec retains data for 30
days before purging, during which the customer can:

- Self-serve export their personal data via `GET /api/auth/me/data-export`
  (ENTERPRISE quota-cap not applied to this endpoint — it always returns
  the full bundle up to the 200-row per-list cap).
- Self-serve org-scoped export via `POST /api/projects/{id}/export` for
  each project they own.
- Request DSAR JSON via `GET /api/dsar/me`.
- Request a custodial copy of workspace files for any specific runs by
  ticket.

After 30 days, retention cleanup happens via `DataRetentionService`
(`compliance/DataRetentionService`) — runs older than the tier window
(ENTERPRISE = 365 days) are soft-deleted; the customer's account itself
requires manual closure via `admin@executespec.ai`.

---

## 12. References

- `docs/operations/slos.md` — the five published SLOs with measurement
  windows + breach handling.
- `docs/dpa-flow.md` — data export, retention, sub-processor list.
- `docs/sso-saml-wiring.md` — SSO setup runbook + known gaps.
- `auth/entity/TierConfig.java:85-87` — ENTERPRISE retention window.
- `application*.yml` — `executespec.gcp-region`, artifact registry host,
  OAuth2 redirect URIs (us-east4 throughout).
- `.github/workflows/deploy.yml` — deploy pipeline (referenced; not read
  for this document).
- `config/featureflag/FeatureFlagService.java` — runtime flag mechanism.
