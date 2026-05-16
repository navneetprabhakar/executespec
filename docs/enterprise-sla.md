# ExecuteSpec ENTERPRISE — Service Level Agreement

> Engineering-honest SLA reference. Code-backed values are pinned to a file
> path; policy-only values are flagged **policy (commercial)**. Sales should
> negotiate the policy-only values per deal; engineering enforces the
> code-backed half.

## Quick reference

| Item                                        | Value                                                    | Source                                                  |
| ------------------------------------------- | -------------------------------------------------------- | ------------------------------------------------------- |
| Uptime target                               | 99.9% monthly (commercial) — **policy (commercial)**     | Not enforced in code                                    |
| Region                                      | `us-east4` (GCP), single-region today                    | `infra/main.tf` + `application-prod.yml`                |
| Data at rest — DB                           | Cloud SQL Postgres 15, GCP-managed encryption            | `infra/modules/database/main.tf`                        |
| Data at rest — object                       | GCS deploy + workspace buckets, GCP-managed encryption   | `infra/modules/storage/main.tf`                         |
| DB backup                                   | Daily 03:00 UTC; PITR + 7d txn log retention when HA on  | `infra/modules/database/main.tf`                        |
| DB high availability                        | `db_ha_enabled` Terraform flag — currently `false`       | `infra/variables.tf`, `infra/modules/database/main.tf`  |
| Cache                                       | Memorystore Redis 7.0, `BASIC` tier (no HA)              | `infra/modules/cache/main.tf`                           |
| Maintenance window                          | Sunday 04:00 UTC (DB + Redis maintenance)                | `infra/modules/database/main.tf`, `cache/main.tf`       |
| TLS                                         | Cloudflare front + GCP managed cert (LB) when enabled    | `infra/modules/loadbalancer/main.tf`                    |
| Incident reporting channel                  | `admin@executespec.ai` + Telegram operator bridge        | `app.contact.fallback-email` in `application.yml`       |
| Operational SLOs (5 numerical commitments)  | See `docs/operations/slos.md`                            | Code-backed via Micrometer + dashboards                 |

---

## 1. Uptime target — **policy (commercial)**

We commit, by contract, to a **99.9% monthly uptime** for the ENTERPRISE
control plane (REST API + dashboard) once the customer is provisioned.

**This SLA target is not currently enforced by code.** The platform exposes
five operational SLOs (see §6 below) but the 99.9% uptime number is a
commercial commitment, not an automated guarantee.

What "down" means:

- The Spring Boot app at `https://app.executespec.ai` / `https://dev.executespec.ai`
  returns 5xx on `/api/health/ready` for ≥5 consecutive minutes; OR
- The Cloud SQL Postgres instance is unreachable from the VM; OR
- The marketing surface (`executespec.ai`) is unreachable through Cloudflare.

What does **not** count toward downtime:

- Scheduled maintenance (see §4).
- Force majeure (GCP region-wide outage, Cloudflare outage).
- Customer-induced rate-limit / quota / billing failures.
- LLM provider outages (Anthropic, OpenAI, Gemini upstream). The platform
  fails over via `model-matrix.failover-order` in `application.yml`; if all
  three providers are down simultaneously, that's force majeure.

Health endpoints:

- Spring Boot Actuator: `/actuator/health/{liveness,readiness}` (public).
- Public health: `/api/health/ready` (LB health check target).
- Cloudflare-fronted status page is **policy (commercial)** — not yet wired
  to an automated probe.

---

## 2. Operational SLOs (code-backed)

Five SLOs are formally tracked in `docs/operations/slos.md` and instrumented
via Micrometer. Quoting the root architecture doc:

> 5 published SLOs in `docs/operations/slos.md`: run terminal in 15min ≥95% /
> verified-score correctness ≥90% / cost estimate within 30% on ≥80% /
> webhook in 60s ≥99% / hot-path 5xx <0.5%.

These are observable in real time:

- Local stack: Prometheus + Tempo + Grafana via `observability/docker-compose.yml`.
  Dashboards `executespec-overview.json` and `jvm-http-overview.json`.
- Production: Cloud Trace + Cloud Monitoring via `@Profile("gcp")`.
- Metric prefix: `executespec.v2.*` (e.g. `executespec.v2.run.outcome`,
  `executespec.v2.run.latency`).
  - Source: `src/main/java/com/navneet/executespec/observability/Metrics.java`.
- Actuator scrape: `/actuator/prometheus` (public).
- Dashboard scrape config: `observability/prometheus.yml` (15s interval).

Refer to `docs/operations/slos.md` for the formal SLO contract, error budgets,
and breach behaviour. ENTERPRISE customers receive the same five SLOs as the
shared platform — the commercial uptime target in §1 sits on top of them.

---

## 3. Service availability outside the control plane

Three asynchronous surfaces have their own commitments, all instrumented:

| Surface                       | Commitment                                       | Code path                                                              |
| ----------------------------- | ------------------------------------------------ | ---------------------------------------------------------------------- |
| Run terminal status           | 95% within 15 minutes (SLO)                      | `RunOrchestrator` + `SequentialExecutor`                               |
| Webhook ingestion (GitHub)    | 99% processed within 60 seconds (SLO)            | `POST /webhooks/github` (HMAC SHA-256)                                 |
| Webhook ingestion (Stripe)    | 99% processed within 60 seconds (SLO)            | `POST /api/webhooks/stripe` + `BillingService.processStripeWebhook`    |
| Webhook ingestion (Razorpay)  | 99% processed within 60 seconds (SLO)            | `POST /api/webhooks/razorpay` + `BillingService.processRazorpayWebhook`|
| Preview environments          | Provisioned within 60s of plan approval          | `agent.preview.*` config + Cloud Run preview engine                    |

Webhook idempotency is enforced via the `(gateway, event_id)` unique
constraint on `webhook_event_log` and `billing_webhook_event` — Stripe and
Razorpay retries are absorbed without duplicate side-effects.

---

## 4. Maintenance windows

| Window                          | Purpose                                                       |
| ------------------------------- | ------------------------------------------------------------- |
| Sunday 04:00 UTC                | Cloud SQL Postgres maintenance (auto-applied)                 |
| Sunday 04:00 UTC                | Memorystore Redis maintenance (auto-applied)                  |
| Daily 03:00 UTC                 | DB backup (Cloud SQL managed)                                 |
| Daily 03:00                     | Data-retention sweep (per-tier expiry, soft + hard delete)    |
| Daily 03:30                     | Workspace disk-cleanup job (orphan / TERMINAL_OLD / TOMBSTONED)|
| Daily 03:45                     | Stale SCM cleanup (close stale PRs, delete stale branches)    |
| Daily ~03:00                    | Activity-feed partition retention (default 365d, dry-run ON)  |
| Daily ~02:30                    | Activity-feed partition lookahead refresh (3 months ahead)    |
| 30-minute granularity           | Subscription period-end downgrade scheduler                   |
| Daily 09:00 IST                 | Dunning + renewal reminders (billing)                         |
| Weekly Monday 09:00 IST         | Admin summary email                                           |
| Monthly 00:00 IST 1st of month  | Monthly run-counter reset (`MonthlyRunCounterResetJob`)       |

Scheduled cron sources:

- `auth/service/MonthlyRunCounterResetJob.java` — `@Scheduled(cron = "0 0 0 1 * *", zone = "Asia/Kolkata")`.
- `compliance/DataRetentionService.java` — `@Scheduled(cron = "0 0 3 * * *")`.
- `compliance/WorkspaceDiskCleanupJob.java` — `@Scheduled(cron = "0 30 3 * * *")`.
- `compliance/StaleScmCleanupJob.java` — `@Scheduled(cron = "0 45 3 * * *")`.
- `activity/ActivityFeedRetentionJob.java` — `@Scheduled(cron = "0 0 3 * * *")`.
- `activity/ActivityFeedPartitionJob.java` — `@Scheduled(cron = "0 30 2 * * *")`.
- `billing/scheduler/PeriodEndDowngradeScheduler.java` — every 30 min.
- `billing/scheduler/DunningScheduler.java` — daily 09:00.
- `billing/scheduler/RenewalReminderScheduler.java` — daily 09:15.

All schedulers use [ShedLock](https://github.com/lukas-krecan/ShedLock) to
guarantee single-leader execution if the platform ever scales horizontally.
Today, prod is a single GCE VM, so the lock is more of a safety net than a
necessity.

ENTERPRISE customer impact during these windows is expected to be **zero** —
the jobs operate on internal tables and disk, never on hot-path request
handling.

Emergency or breaking-change maintenance: **policy (commercial)** — typically
announced ≥7 days in advance via the admin email channel
(`admin@executespec.ai`).

---

## 5. Data residency

| Item                       | Location / region                              | Source                                          |
| -------------------------- | ---------------------------------------------- | ----------------------------------------------- |
| Postgres (Cloud SQL)       | `us-east4` (GCP, dev + prod)                   | `application-prod.yml` `agent.deployment.gcp-region` |
| Redis (Memorystore)        | `us-east4` (GCP)                               | `infra/modules/cache/main.tf`                   |
| Object storage (GCS)       | `us-east4` (GCP)                               | `infra/modules/storage/main.tf`                 |
| Artifact Registry (Docker) | `us-east4-docker.pkg.dev/executespecv1/...`    | `application-prod.yml`, `infra/modules/storage/main.tf` |
| GCE VM (Spring Boot app)   | `us-east4` (configured per Terraform `zone` var; default `us-central1-a` in `infra/variables.tf`, overridden to `us-east4-*` in prod) | `infra/main.tf`, `infra/variables.tf`           |
| LLM call routing           | Provider-controlled (Anthropic, OpenAI, Google)| `model-matrix.failover-order` in `application.yml`   |
| Object storage retention   | Workspace bucket: 30 days lifecycle delete; deploy bucket: keep latest 20 versions | `infra/modules/storage/main.tf` |

**EU / UK / IN data-residency commitments are policy (commercial).** Today,
the platform runs in a single GCP region (`us-east4`). If a customer's data
must remain inside a specific jurisdiction, that has to be negotiated as a
commercial term — there is no per-tenant region routing in the code today.

The Terraform default `region = us-central1` in `infra/variables.tf` is a
defensive default; the prod deployment is overridden to `us-east4` via
profile config and the artifact-registry host. If you provision a new
environment from scratch, **explicitly pass `-var region=us-east4`** to keep
all resources in the same region.

LLM provider data handling is governed by each provider's terms (Anthropic,
OpenAI, Google). ExecuteSpec does not control where the providers process
prompts — this is disclosed to ENTERPRISE customers during onboarding.

---

## 6. Backup and disaster recovery

### Postgres (Cloud SQL)

Configured in `infra/modules/database/main.tf`:

- **Daily automated backups** at 03:00 UTC.
- **Point-in-time recovery (PITR)** enabled when `db_ha_enabled = true`.
- **Transaction log retention**: 7 days; backup retention: 1 day binary +
  PITR window.
- **Deletion protection**: enabled when `db_ha_enabled = true`; otherwise
  off (dev VM is unprotected to allow re-provisioning).
- **Availability type**: `REGIONAL` (HA across zones) when
  `db_ha_enabled = true`; otherwise `ZONAL`.
- **Maintenance window**: Sunday 04:00 UTC, stable release track.
- **`max_connections`**: 100 (Hikari pool max-pool=30 leaves headroom for the
  pgvector worker plus migration shells).

The HA flag defaults to `false` in `infra/variables.tf`. **For ENTERPRISE
deployments, set `db_ha_enabled = true` at provisioning time** to enable
REGIONAL HA + PITR. The flag is not flippable without recreating the
instance.

### Redis (Memorystore)

Configured in `infra/modules/cache/main.tf`:

- Memorystore **Redis 7.0**, `BASIC` tier (single node — no HA).
- `maxmemory-policy = allkeys-lru` (oldest keys evicted under pressure).
- Maintenance: Sunday 04:00 UTC.
- Private service access only (no public IP).

Redis is treated as a cache, not a system of record. All durable state lives
in Postgres. A Redis outage degrades:

- Run-context replay (`v2:agent-ctx:{runId}:{taskId}`, 24h TTL).
- SSE cross-instance fanout (single-instance fallback works fine).
- Rate-limit counters (`rl:*` keys with 60s window) — filter fails open at
  50% capacity per `RateLimitFilter`.
- Force-logout JWT epoch.

The platform survives a Redis outage with graceful degradation; durability is
not compromised.

### Object storage (GCS)

Configured in `infra/modules/storage/main.tf`:

- **`executespec-deploy` bucket**: versioning enabled, lifecycle deletes
  objects when `num_newer_versions = 20` is reached. Holds release JARs +
  config blobs.
- **`executespec-workspaces` bucket**: lifecycle deletes objects after 30
  days. Holds long-term-archived run workspaces (only when
  `agent.deployment.workspace.gcs-enabled = true`).
- Uniform bucket-level access (no per-object ACLs).
- GCP-managed encryption at rest (default).

Workspace shipping to GCS is **not enabled by default**
(`workspace.gcs-enabled = false`). Workspaces today live on the local VM disk
and are reaped by `WorkspaceDiskCleanupJob` after 14 days for terminal runs.

### Artifact Registry (Docker)

Configured in `infra/modules/storage/main.tf`:

- `executespec-docker` Docker repo, keeps 10 most recent versions per
  cleanup policy.

### Disaster recovery — what happens on full VM loss

1. New VM provisioned via Terraform (single VM, no MIG today; instance group
   is unmanaged).
2. Startup script (`infra/scripts/startup.sh`) installs Java 21 + Maven +
   Docker + Node.js + Python + gcloud, pulls the latest JAR by `CURRENT_SHA`
   from the GCS deploy bucket, and starts the `executespec.service` systemd
   unit.
3. Cloudflare Origin Certificate is preloaded on the VM at
   `/etc/caddy/origin.pem` / `origin-key.pem`; Caddy fronts the Spring Boot
   app on port 8080.
4. Cloud SQL is unaffected (separate resource); the new VM reconnects via
   the existing private IP.
5. Redis is unaffected.
6. In-flight runs at the moment of crash: V2's `StartupRunResumer` scans
   resumable statuses (`PLANNING`, `WORKING`, `REVIEWING`,
   `AWAITING_APPROVAL`) on application-ready, salvages where possible, and
   marks the rest FAILED with `status_reason = REVIEWER_INTERRUPTED_CODE_PRESERVED`.
   - Source: `src/main/java/com/navneet/executespec/recovery/StartupRunResumer.java`.

**Estimated recovery time objective (RTO)** for a clean VM rebuild: ~15–25
minutes (Terraform apply + startup script + first health probe). This is a
**policy (commercial)** target — not formally measured every cycle.

**Recovery point objective (RPO)** for Postgres: <5 minutes when PITR is on
(HA mode); up to 24 hours otherwise (daily backup only).

Workspace data loss on VM failure: in-flight workspaces not yet shipped to
GCS are lost. The customer's git remotes remain authoritative — every
completed task commits via `gitCommit` and pushes via `ScmPushService`
before the run is marked terminal.

---

## 7. Incident reporting

### Customer → ExecuteSpec

Primary channel: email to `admin@executespec.ai`
(`app.mail.admin-address` in `application.yml`).

For confirmed P1 incidents (control plane down, data loss suspected, security
breach suspected), ENTERPRISE customers should follow up by phone if no
response within 1 hour — **policy (commercial)** — contact details supplied
during onboarding.

### ExecuteSpec → customer

Internal monitoring channels:

- **Telegram operator bridge** (not customer-facing).
  - Operator chat id `6475810501`, bot `8489535450`.
  - Heartbeat every 600s + on every status-count change.
  - Pull every 120s via `getUpdates`.
- **In-app notifications** for billing and run lifecycle events:
  - `auth/service/MailService.java` (subscription activated, payment failed,
    downgraded, cancellation, top-up, dunning, renewal reminder, run
    completion).
  - `billing/service/BillingNotificationService.java` (async Gmail-API
    transactional emails; degrades to WARN log if Gmail unavailable).
- **Customer-facing status page**: **policy (commercial)** — see
  `docs/status-page.md` for the current commitment.

### Incident response steps (internal)

1. Alert lands in the Telegram operator chat.
2. Operator pulls `/actuator/prometheus` + Grafana dashboards.
3. If JVM-level, `journalctl -u executespec` on the GCE VM (or via
   `gcloud compute ssh`).
4. Audit chain verifier (`GET /api/admin/audit-chain/verify`) confirms no
   tamper.
5. If data-loss suspected, the SUPER_ADMIN runs
   `POST /api/admin/executions/reconcile` to mark orphaned non-terminal runs
   FAILED.
6. Communication: customer-affecting incidents are summarized in the next
   admin summary email (Monday 09:00 IST) plus an out-of-cycle email if P1.

---

## 8. Security incident response

- **Suspected breach contact**: `admin@executespec.ai`.
- **Cleanroom isolation** for SWE-bench / ProgramBench worker containers:
  `--rm --network none -v <hostDir>:/workspace`.
- **Audit chain** (`activity_audit`) is tamper-evident; the chain verifier
  endpoint reveals any rewrite within seconds:
  - `auth/service/ActivityAuditService.java` — `verifyChain()`.
- **Force-logout** on suspected JWT compromise: per-user `token_invalidated_at`
  flip via `POST /api/auth/invalidate-sessions`, plus a global feature-flag
  epoch (`FeatureFlagService`).
- **AES-256-GCM encryption** for sensitive Redis values via `KeyEncryptor`.
- **BCrypt(strength=12)** for password hashes; JWT secret validator fails
  fast at startup in prod.

Disclosure timelines are **policy (commercial)**.

---

## 9. Quotas, throttling, and rate limits

The platform rate-limits `/api/**` traffic via `RateLimitFilter`. ENTERPRISE
tier limits per `RateLimitPolicy.LIMITS`:

| Group   | Limit (req/min) |
| ------- | --------------- |
| AUTH    | per-subtype (login=10, register=5, forgot=3, default=10) |
| RUNS    | 60              |
| READ    | 2000            |
| WRITE   | 1000            |
| PREVIEW | 200             |
| DEPLOY  | 60              |
| WEBHOOK | 100 (default; IP-keyed) |

Source: `src/main/java/com/navneet/executespec/ratelimit/RateLimitPolicy.java`.

429 responses include `Retry-After-Seconds` and follow the canonical error
envelope. Bypass for SUPER_ADMIN ops users is not implemented; rate-limit
breaches surface in the monitoring counter `executespec.redis.failopen` when
Redis is the failure cause.

---

## 10. Termination and data-deletion

- **Customer-initiated termination** (off-boarding): the customer should
  export data via `POST /api/projects/{id}/export` (per project) and
  `GET /api/auth/me/data-export` (per user) before requesting account
  closure.
- **Right-to-erasure** (`DELETE /api/auth/me/data`): soft-deletes the user
  and schedules hard-delete via the daily `DataRetentionService` sweep
  (7-day grace).
- **Project deletion**: soft-delete sets `deleted_at`; 30-day undo window;
  then hard-delete via `DataRetentionService.hardDeleteTombstonedProjects`.
- **Archived run purge**: 90 days after `archived_at` per
  `DataRetentionService.hardDeleteArchivedRuns`.

Post-termination data residency: backups remain in Cloud SQL's managed
backup retention window (1 day binary + 7 days PITR transaction log when HA
on); object-storage objects follow each bucket's lifecycle rule.

---

## 11. Liability and exclusions — **policy (commercial)**

Commercial terms in the master agreement govern:

- Liability cap.
- Service credits for SLA breach.
- Force majeure scope (GCP region outage, Cloudflare outage, LLM provider
  outage).
- Termination for material breach.
- Survival clauses.

None of these are enforced in code; they belong to the signed contract.

---

## 12. Source-of-truth file paths

| Topic                                | Path                                                                                          |
| ------------------------------------ | --------------------------------------------------------------------------------------------- |
| Infrastructure (Terraform)           | `infra/main.tf`, `infra/variables.tf`, `infra/modules/*/`                                     |
| GCE startup script                   | `infra/scripts/startup.sh`                                                                    |
| Cloud SQL config                     | `infra/modules/database/main.tf`                                                              |
| Memorystore Redis config             | `infra/modules/cache/main.tf`                                                                 |
| GCS storage config                   | `infra/modules/storage/main.tf`                                                               |
| Load balancer + managed SSL          | `infra/modules/loadbalancer/main.tf`                                                          |
| Spring Boot prod config              | `src/main/resources/application-prod.yml`                                                     |
| Spring Boot dev config               | `src/main/resources/application-dev.yml`                                                      |
| Health endpoints                     | `/actuator/health/{liveness,readiness}`, `/api/health/ready`                                  |
| Metrics + dashboards                 | `src/main/java/com/navneet/executespec/observability/Metrics.java`, `observability/grafana/dashboards/` |
| Prometheus scrape config             | `observability/prometheus.yml`                                                                |
| Operational SLOs                     | `docs/operations/slos.md`                                                                     |
| Data retention sweeper               | `src/main/java/com/navneet/executespec/compliance/DataRetentionService.java`                  |
| Workspace cleanup job                | `src/main/java/com/navneet/executespec/compliance/WorkspaceDiskCleanupJob.java`               |
| Stale SCM cleanup job                | `src/main/java/com/navneet/executespec/compliance/StaleScmCleanupJob.java`                    |
| Rate limit policy                    | `src/main/java/com/navneet/executespec/ratelimit/RateLimitPolicy.java`                        |
| Startup run resumer                  | `src/main/java/com/navneet/executespec/recovery/StartupRunResumer.java`                       |
| Webhook idempotency                  | `WebhookEventLog` + `BillingWebhookEvent` entities + `(gateway, event_id)` unique constraint  |
| Activity audit (tamper-evident)      | `src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java`                |
