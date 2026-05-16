# ExecuteSpec ENTERPRISE Tier — One-Pager

> Engineering-honest summary of what an ENTERPRISE customer actually gets from
> the code on `main`. Status, caveats, and known gaps are called out inline.

## At a glance

| Dimension                    | ENTERPRISE                                                                                  |
| ---------------------------- | ------------------------------------------------------------------------------------------- |
| Monthly run quota            | Unlimited (`tier_config.monthly_run_limit = -1`)                                            |
| Concurrent runs              | Unlimited at the tier-config level; bounded by `agent.concurrency.max-runs` per JVM         |
| Cost-cap policy              | Exempt when org is BYOK (`org.api_keys_provided = true`); platform cap applies otherwise    |
| BYOK (Bring Your Own Keys)   | Supported via `Org.key_source_mode = OWN_KEYS` or `HYBRID`                                  |
| Cross-org collaboration      | Supported via per-org flag `org.external_sharing_enabled` (V198, default OFF)               |
| SSO                          | Google OIDC (live); OKTA_SAML + AZURE_AD_SAML configurable but enforcement returns 409      |
| Audit log access             | Hash-chained `activity_audit` + `payment_audit`; ORG_ADMIN reads its own org; SUPER_ADMIN all|
| Standards + invariants       | Per-org engineering standards with project-level downgrade overrides + violation reporting  |
| Project export               | Tar.gz bundle (manifest, contracts, recent activity); ORG_ADMIN / BILLING_ADMIN / SUPER_ADMIN |
| GDPR data export + erasure   | ZIP bundle (manifest, payload, README) + right-to-erasure                                   |
| SLA + dedicated support      | See `docs/enterprise-sla.md`                                                                |

---

## Runs and quotas

ENTERPRISE accounts have no monthly run quota. `tier_config.monthly_run_limit`
is set to `-1` (the unlimited sentinel used across the platform), and
`TierEnforcementService.assertAndIncrementRunCount` skips the quota check when
the resolved limit is negative.

- Quota enforcement code:
  `src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java`
- Quota check is atomic and pessimistic-locked
  (`AppUserRepository#findByIdForUpdate`, `OrgRepository#findByIdForUpdate`).
- Concurrent-run policy is governed by `tier_config.concurrent_run_limit` and
  the per-JVM ceiling `agent.concurrency.max-runs` (20 in prod, 50 local).

Quota refund (v2.5.12) is event-driven. On `RunFailed` or `PARTIAL` with
reason `CLEAN_WITH_INTERMEDIATE_WARNINGS`, credits are auto-refunded:

- `src/main/java/com/navneet/executespec/billing/service/RunQuotaRefundListener.java`

---

## BYOK and cost-cap exemption

ENTERPRISE customers are expected to provide their own LLM API keys for
Anthropic, OpenAI, and Gemini. The platform-wide per-run dollar cap is waived
when the org is on BYOK:

- `Org.api_keys_provided` (boolean) signals the BYOK posture for the org.
- `Org.key_source_mode` is `OWN_KEYS`, `HYBRID`, or `SYSTEM_ONLY`; ENTERPRISE
  typically uses `OWN_KEYS` or `HYBRID`.
- `TierEnforcementService.requiresCostCap(tier, hasByok)` returns `false` for
  BYOK orgs (R-341), so the run-cost ceiling does not apply.
- Per-org API keys are stored encrypted via `KeyEncryptor` (AES-256-GCM, key
  derived from `app.jwt.secret`):
  `src/main/java/com/navneet/executespec/security/KeyEncryptor.java`

Note: user-level keys are also encrypted and live on `AppUser`. Org-level keys
override user-level on resolution; the resolution order is documented in
`OrgService.updateKeySourceSettings`.

---

## SSO status (read the fine print)

The data model and admin endpoints for SSO exist. Today, only Google OIDC is
fully wired end-to-end through Spring Security's OAuth2 client.

| Provider       | Save config | Enforce SSO-only login | Code path                         |
| -------------- | ----------- | ---------------------- | --------------------------------- |
| GOOGLE_OIDC    | Yes         | Yes (live)             | `auth/oauth/OAuthUserMapper.java` |
| OKTA_SAML      | Yes         | Returns 409 (not live) | `auth/sso/SsoConfigController.java` (gated reject) |
| AZURE_AD_SAML  | Yes         | Returns 409 (not live) | Same gated reject                                  |

- Config endpoints: `GET|PUT|DELETE /api/auth/sso/config` (ORG_ADMIN or
  SUPER_ADMIN).
- File: `src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java`
- `Org` columns persisted: `sso_provider`, `sso_enforced`, `sso_metadata_url`,
  `sso_domain` (see `Org.java`).
- The 409 on enforce-with-SAML is intentional (M-016 lockout-prevention guard
  + the J-14 SAML enforcement gate). SAML handshake itself is on the roadmap;
  it is not in production today.

OAuth providers wired in `SecurityConfig`: Google + GitHub. OAuth client IDs
come from environment variables in prod (`GOOGLE_OAUTH_CLIENT_ID`,
`GITHUB_OAUTH_CLIENT_ID`, plus secrets).

---

## Audit log access

Two tamper-evident audit surfaces are exposed:

1. `activity_audit` — append-only, SHA-256 hash-chained
   - Entity: `src/main/java/com/navneet/executespec/auth/entity/ActivityAudit.java`
     (cols `prev_hash`, `row_hash`, `chain_seq`).
   - Writer: `src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java`
     — JVM-synchronized `record(...)`; chain verifier walks rows in
     `chain_seq` order.
   - Chain verifier endpoint: `GET /api/admin/audit-chain/verify` (SUPER_ADMIN).
   - Org-scoped reads: `GET /api/org/me/audit-logs` (ORG_ADMIN).
   - SUPER_ADMIN reads: `GET /api/admin/audit-logs` with filters.
   - Filter spec helper: `src/main/java/com/navneet/executespec/auth/repository/ActivityAuditSpec.java`.

2. `payment_audit` — payment + tier-change audit, JSONB metadata
   - Entity: `src/main/java/com/navneet/executespec/auth/entity/PaymentAudit.java`.
   - Idempotency key: `gatewayProvider:gatewayEventId`.
   - Writer: `src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java`.
   - SUPER_ADMIN listing + CSV export: `GET /api/admin/payment-audit`,
     `GET /api/admin/payment-audit/export`.
   - Org-scoped reads: `GET /api/billing/payment-history` (ORG_ADMIN |
     BILLING_ADMIN | SUPER_ADMIN).

A higher-volume activity feed lives on the partitioned `activity_feed` table
(M-019/V133) for project activity surfaced in the UI:

- Entity: `src/main/java/com/navneet/executespec/activity/ActivityFeedEntry.java`.
- Writer (single chokepoint):
  `src/main/java/com/navneet/executespec/activity/ActivityEventEmitter.java`.
- Dual-writes to `activity_audit` for events marked `Surface.AUDIT` in
  `ActivityEventType`.
- Read endpoint: `GET /api/projects/{id}/activity` — partition-pruned by
  `occurred_at` time window.
- Retention: daily 03:00 job drops partitions older than
  `activity.feed.retention.days` (default 365, dry-run default ON).
  - `src/main/java/com/navneet/executespec/activity/ActivityFeedRetentionJob.java`.
  - `src/main/java/com/navneet/executespec/activity/ActivityFeedPartitionJob.java`
    keeps the lookahead window topped up (default 3 months ahead, runs daily
    02:30).

---

## External cross-org collaboration (V175 + V198)

Two surfaces, both governed by the per-org `external_sharing_enabled` flag for
the cross-org invite path:

1. **Cross-org project membership** (`ProjectMember.is_external = true`)
   - Endpoints: `POST /api/projects/{id}/invites`,
     `GET /api/invites/preview?token=`, `POST /api/invites/accept?token=`.
   - Requirements (V198, 2026-05-16):
     - Tier PRO or higher
     - Caller is ORG_ADMIN-of-own-org (or SUPER_ADMIN)
     - `org.external_sharing_enabled = true`
   - Per-org toggle: `PUT /api/org/me/settings/external-sharing` (ORG_ADMIN of
     own org, or SUPER_ADMIN). Emits `ORG_EXTERNAL_SHARING_CHANGED` audit row.
   - Entity column: `Org.external_sharing_enabled` (default `false`).

2. **Public read-only share links** (not gated by V198)
   - Endpoints: `POST /api/projects/{id}/share-links`,
     `GET /api/shared/{token}/{meta,run,validation-report,...,architecture}`.
   - Tier-gated via `tier_config.share_link_active_cap`.

---

## Engineering standards + invariants (org-wide)

ENTERPRISE customers commonly want to encode their engineering standards once
and have ExecuteSpec apply them on every run.

- Per-org standard rows:
  `src/main/java/com/navneet/executespec/standards/OrgStandard.java` (YAML
  body, optionally `org_level_locked = true` to forbid project-level
  overrides).
- Project-level overrides:
  `src/main/java/com/navneet/executespec/standards/ProjectStandardOverride.java`
  — `SEVERITY_DOWNGRADE` (BLOCKING → WARNING → ADVISORY) or `NOT_APPLICABLE`.
  Severity upgrades are rejected (downgrade-only contract).
- Merge logic:
  `src/main/java/com/navneet/executespec/standards/EffectiveStandardsResolver.java`.
- Enforcers (deterministic):
  - Lint adapters (`eslint`, `checkstyle`):
    `src/main/java/com/navneet/executespec/standards/enforcement/lint/EslintAdapter.java`,
    `src/main/java/com/navneet/executespec/standards/enforcement/lint/CheckstyleAdapter.java`.
  - File-pattern:
    `src/main/java/com/navneet/executespec/standards/enforcement/filepattern/FilePatternEnforcer.java`.
  - AST checks (forbidden-import, naming-pattern, dependency-direction):
    `src/main/java/com/navneet/executespec/standards/enforcement/ast/AstCheckEnforcer.java`.
- LLM-review fragment composer:
  `src/main/java/com/navneet/executespec/standards/enforcement/llmreview/LlmReviewEnforcer.java`.
- Runner (used twice per V2 run — worker self-check + reviewer pass):
  `src/main/java/com/navneet/executespec/standards/StandardsRunner.java`.
- Violation persistence:
  `src/main/java/com/navneet/executespec/standards/StandardsViolation.java`
  (JSONB payload, emergency-override fields).
- Override pattern detector (recommends downgrades when threshold crossed in
  rolling window):
  `src/main/java/com/navneet/executespec/standards/override/OverridePatternDetector.java`.
  Wired as a pure-read service; daily job consumer is **not** yet scheduled
  (returns `Recommendation` records to no consumer).
- Compliance report: `GET /api/projects/{id}/standards/report` — surfaces
  effective standards + recent violations + persistent overrides
  (`src/main/java/com/navneet/executespec/standards/report/StandardsComplianceController.java`).
- Three override endpoints (≥30-char rationale required, audited):
  `src/main/java/com/navneet/executespec/standards/override/StandardsOverrideController.java`.

Sibling system: cross-repo `contracts/*` package (consumer pinning, drift
detection, coverage rollup). Surface points and APIs are listed in the root
architecture doc under "Enterprise readiness".

Wave 3 governance guards bundled into a single service:

- `src/main/java/com/navneet/executespec/governance/GovernanceGuard.java`
  - `Org.require_admin_approval_above_usd` (M-032) — non-admin approval block
    at the run-cost threshold.
  - `Project.spend_cap_usd_monthly` (M-033) — MTD spend cap; throws 402 when
    estimate would exceed.
  - `Org.allowed_github_owners` CSV (M-039) — owner allowlist for repo links.

---

## Project + run portability (export, retention)

- Project export bundle (tar.gz, no secrets, no `.executespec/` duplication —
  customer git remains authoritative):
  - `src/main/java/com/navneet/executespec/export/ProjectExportService.java`
    (`composeBundle(projectId)` returns bytes; schema version = 1).
  - `src/main/java/com/navneet/executespec/export/ProjectExportController.java`
    — `POST /api/projects/{id}/export`, `@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN','BILLING_ADMIN')")`,
    also gated by `ProjectAccessGuard.assertCanRead`.
  - Layout: `manifest.json`, `project.json`, `members.json` (placeholder),
    `contracts/index.json`, `activity/events-recent.ndjson` (last 90d),
    `README.md`.

- GDPR Article 15 + 20 export (ZIP, capped at 200 rows per table):
  - `src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java`.
  - Redacts password hash + every API key; replaces with `hasXxxKey` booleans.
  - Endpoint: `GET /api/auth/me/data-export`.

- Right-to-erasure (soft delete + asynchronous purge):
  - `DELETE /api/auth/me/data`.
  - `compliance/DataRetentionService.eraseUserData(userId)` emits
    `executespec.data.retention.deleted_runs{trigger=GDPR}`.

- Per-tier data retention (sweeps daily 03:00):
  - `src/main/java/com/navneet/executespec/compliance/DataRetentionService.java`.
    Soft-deletes expired runs per `tier_config.retention_days`, hard-deletes
    graced runs 7 days later, purges tombstoned projects 30 days after
    `deleted_at`, purges archived runs after 90 days.
  - Workspace disk reaper (nightly 03:30):
    `src/main/java/com/navneet/executespec/compliance/WorkspaceDiskCleanupJob.java`
    — ORPHAN / TERMINAL_OLD / TOMBSTONED categories.
  - Stale SCM reaper (nightly 03:45):
    `src/main/java/com/navneet/executespec/compliance/StaleScmCleanupJob.java`
    — closes OPEN PRs from CANCELLED/FAILED/ABORTED runs >14d old; deletes
    `executespec/*` branches whose PR is terminal and >30d old.

---

## Security posture (summary)

8-layer defense-in-depth per the root architecture document. Highlights
relevant to ENTERPRISE buyers:

- Input sanitization
  (`src/main/java/com/navneet/executespec/security/InputSanitizer.java`) —
  guardrail-aware (LOW / MEDIUM / HIGH); NFKC normalize + zero-width strip +
  base64 decode-and-scan; blocks injection / shell / abuse patterns.
- Agent-code abuse scanner
  (`src/main/java/com/navneet/executespec/security/CodeAbuseScanner.java`) —
  flags crypto-miner / reverse-shell / data-exfil / cloud-metadata /
  obfuscated payloads; promotes to 400 at MEDIUM+.
- Context sanitizer
  (`src/main/java/com/navneet/executespec/security/ContextSanitizer.java`) —
  strips XML system tags, neutralizes injection phrases, redacts secret
  patterns (OpenAI / Anthropic / Google API keys, Bearer tokens, `password=`,
  etc.).
- AES-256-GCM encryption-at-rest for sensitive Redis values
  (`src/main/java/com/navneet/executespec/security/KeyEncryptor.java`).
- BCrypt(strength=12) password hashing
  (`src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java`).
- JWT signature validator at startup (prod profile) fails fast on missing /
  short / dev-placeholder secret:
  `src/main/java/com/navneet/executespec/auth/security/JwtSecretValidator.java`.
- Cleanroom container hardening for SWE-bench + ProgramBench:
  `--rm --network none -v <hostDir>:/workspace`.
- Per-org cost approval threshold + project spend cap + repo-owner allowlist
  (`src/main/java/com/navneet/executespec/governance/GovernanceGuard.java`).

---

## What ENTERPRISE does **not** automatically get

We are deliberately conservative here:

- **SAML SSO enforcement.** Configurable, but enforcing SSO-only login on a
  SAML provider returns 409 in `SsoConfigController`. SAML handshake itself
  is on the roadmap.
- **SCIM provisioning.** Not implemented.
- **CMK (customer-managed encryption keys).** All encryption uses a key
  derived from `app.jwt.secret`. Rotation makes prior Redis entries
  unreadable; the system falls back to `SYSTEM_ONLY` gracefully but there is
  no per-customer key today.
- **Dedicated tenant / single-tenant deployment.** ExecuteSpec is
  multi-tenant on a single GCE VM in `us-east4`. Single-tenant is out of
  scope today.
- **Active-active multi-region failover.** The VM lives in one region; Cloud
  SQL is zonal in dev, regional-capable in prod (`db_ha_enabled` Terraform
  flag — currently `false` by default).

If any of these is required for a deal, treat it as a contractual gap and
escalate.

---

## Where this fits with adjacent docs

- SLA, uptime targets, maintenance windows, data residency, backup +
  disaster-recovery: `docs/enterprise-sla.md`.
- Architecture overview: `docs/ARCHITECTURE.md`.
- RBAC matrix: `docs/RBAC.md`.
- Deployment + infra: `docs/DEPLOYMENT.md`.
- Operations runbook + SLOs: `docs/operations/slos.md`.

---

## Source-of-truth file paths

| Topic                          | Path                                                                                                          |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------- |
| TierConfig entity              | `src/main/java/com/navneet/executespec/auth/entity/TierConfig.java`                                           |
| Org entity                     | `src/main/java/com/navneet/executespec/auth/entity/Org.java`                                                  |
| Tier enforcement               | `src/main/java/com/navneet/executespec/auth/service/TierEnforcementService.java`                              |
| Tier permissions               | `src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java`                               |
| SSO config controller          | `src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java`                                     |
| Activity audit                 | `src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java`                                |
| Activity feed (UI surface)     | `src/main/java/com/navneet/executespec/activity/`                                                             |
| Payment audit                  | `src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java`                                 |
| Standards merge resolver       | `src/main/java/com/navneet/executespec/standards/EffectiveStandardsResolver.java`                             |
| Standards runner               | `src/main/java/com/navneet/executespec/standards/StandardsRunner.java`                                        |
| Governance guards              | `src/main/java/com/navneet/executespec/governance/GovernanceGuard.java`                                       |
| Project export                 | `src/main/java/com/navneet/executespec/export/ProjectExportService.java`                                      |
| GDPR data export               | `src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java`                               |
| Data retention sweeper         | `src/main/java/com/navneet/executespec/compliance/DataRetentionService.java`                                  |
| Workspace disk cleanup         | `src/main/java/com/navneet/executespec/compliance/WorkspaceDiskCleanupJob.java`                               |
| Stale SCM cleanup              | `src/main/java/com/navneet/executespec/compliance/StaleScmCleanupJob.java`                                    |
| Key encryptor (AES-256-GCM)    | `src/main/java/com/navneet/executespec/security/KeyEncryptor.java`                                            |
| External-sharing toggle (V198) | `OrgService.updateExternalSharing` + `OrgController` `PUT /me/settings/external-sharing`                      |
