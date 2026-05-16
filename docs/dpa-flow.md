# Data Processing & DSAR Flow

> Source-of-truth for how ExecuteSpec handles a Data Subject Access Request (DSAR), what data is exposed by export endpoints, the live sub-processor catalog, retention policy, and at-rest encryption. Engineering-honest: this document records only what is implemented in code today; gaps (e.g. full account closure) are called out explicitly.

## 1. Public-surface endpoints

Two distinct endpoints today serve DSAR-shaped requests. They differ in scope and packaging:

| Method | Path | Format | Owner |
| ------ | ---- | ------ | ----- |
| `GET`  | `/api/auth/me/data-export` | `application/zip` (manifest + payload + README) | [`AuthController`](../src/main/java/com/navneet/executespec/auth/controller/AuthController.java), service [`UserDataExportService`](../src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java) |
| `GET`  | `/api/dsar/me` | `application/json` | [`DsarController`](../src/main/java/com/navneet/executespec/auth/dsar/DsarController.java) |
| `GET`  | `/api/dsar/users/{userId}` | `application/json` | [`DsarController`](../src/main/java/com/navneet/executespec/auth/dsar/DsarController.java) — `SUPER_ADMIN` only |

Both endpoints sit behind the same authentication filter chain (`/api/auth/me/**` and `/api/dsar/**` require a valid JWT — see [`SecurityConfig`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java)).

### Erasure / right-to-be-forgotten

One endpoint exists:

- `DELETE /api/auth/me/data` — declared on [`AuthController`](../src/main/java/com/navneet/executespec/auth/controller/AuthController.java). It performs **soft-delete** semantics by setting deletion timestamps; it does not nuke billing rows, audit-chain rows, or accounting journal entries (those are subject to statutory retention — see §5).

Honest gap: there is no automated hard-purge pipeline that wipes every personal-data column after the soft-delete window expires. Account closure is currently a soft-delete + policy promise, not a code-enforced purge.

## 2. `GET /api/auth/me/data-export` — ZIP bundle

Implemented in [`UserDataExportService.exportForUser(userId)`](../src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java) (263 lines).

The ZIP is built inside a `@Transactional(readOnly = true)` block; row caps (`EXPORT_ROW_LIMIT = 200`) prevent unbounded payloads. Schema version is currently `2`.

### Bundle shape

```
data-export-{userId}-{yyyyMMddHHmm}.zip
  manifest.json     # metadata: schemaVersion=2, userId, exportedAt, counts per section
  payload.json      # the data
  README.md         # human-readable description of what's inside
```

### Sections in `payload.json`

| Section | Source repository / service | Cap |
| ------- | --------------------------- | --- |
| `user`  | `AppUserRepository.findById` — passed through `redactUser(AppUser)` which **drops** `passwordHash` and every encrypted API key column, replacing them with `hasOpenAiKey`/`hasAnthropicKey`/`hasGeminiKey`/`hasGithubPat`/`hasJiraToken`/`hasConfluenceToken`/`hasBitbucketAppPassword` booleans | 1 |
| `orgs`  | `OrgRepository` for `org_id` (if any) | bounded by membership |
| `projects` | `ProjectRepository.findByOrgId(orgId)` — includes tombstoned (soft-deleted) rows so the export is faithful to "all data held about you" | 200 |
| `runs` | `PipelineRunRepository` filtered by `createdByUserId` | 200 |
| `notifications` | `NotificationRepository` for the user | 200 |
| `subscription` | active `BillingSubscription` for the user | 1 |
| `invoices` | `InvoiceRepository.findByUserIdOrderByInvoiceDateDesc` | 200 |

The 200-row cap is documented in the `README.md` written into the ZIP. If a user exceeds this, the README directs them to contact support for a full export — there is no automated paginated export today.

### Redaction contract

The redaction function in `UserDataExportService.redactUser(AppUser)` is **deny-by-default**: it whitelists serialised fields and replaces every sensitive column. The fields blanked:

- `password_hash`
- `anthropic_api_key`, `openai_api_key`, `gemini_api_key`
- `github_pat`
- `jira_api_token`, `confluence_api_token`
- `bitbucket_app_password`

Each replaced column gets a boolean flag (`hasXxxKey`) so the user can confirm whether a credential was stored without seeing the credential itself.

## 3. `GET /api/dsar/me` — JSON variant

Implemented in [`DsarController`](../src/main/java/com/navneet/executespec/auth/dsar/DsarController.java) (198 lines). Returns `Map<String,Object>` with the same conceptual sections as the ZIP endpoint, but:

- Pure JSON (no ZIP packaging, no manifest, no README).
- Includes `payments` (from `PaymentAuditRepository.findByUserIdOrOrgId(userId, orgId)` — the M-017 DSAR-aware lookup).
- Includes `notifications` (from `NotificationRepository`).
- Includes `runs` (`serializeRun` helper).
- Includes profile fields direct from `AppUserRepository.findById`.

Notable: `PaymentAuditRepository` has a dedicated `findByUserIdOrOrgId(userId, orgId)` query (annotated M-017) specifically so a DSAR can return every payment audit row that touches either the user directly or any of their orgs. This is the right behaviour for a JSON DSAR but is not exposed via the ZIP endpoint above.

`GET /api/dsar/users/{userId}` is the `SUPER_ADMIN`-only variant for admin-driven legal requests.

## 4. Sub-processor catalog

The platform sends customer-identifiable data to the following third parties. Sources are configured via environment variables and `application*.yml`. The "trigger" column says under what user action data leaves our perimeter.

| Sub-processor | Purpose | Env vars | Trigger | Code path |
| ------------- | ------- | -------- | ------- | --------- |
| **OpenAI** | LLM inference (workhorse, lightweight, embeddings) | `OPENAI_API_KEY`, `spring.ai.openai.api-key` | Every run dispatch when provider=OpenAI; every always-on RAG document embed | `OpenAiNativeToolClient`, embedding service in always-on RAG |
| **Anthropic** | LLM inference (planning, workhorse, lightweight) | `ANTHROPIC_API_KEY`, `spring.ai.anthropic.api-key` | Every run dispatch when provider=Anthropic; default provider since `1.0.0-M1` | Spring AI Anthropic client via `LlmRouter` |
| **Google (Gemini API)** | LLM inference | `GOOGLE_API_KEY`, `spring.ai.google.genai.api-key` | Every run dispatch when provider=Gemini | `GeminiNativeToolClient` via `LlmRouter` |
| **GCP — Cloud SQL, GCS, Cloud Run, Artifact Registry, Cloud Trace, Cloud Monitoring** | Infrastructure (DB, workspace blob archive, on-demand previews, deploy targets, observability backend) | `POSTGRES_HOST/PORT/DB/USER/PASSWORD`, `agent.deployment.gcp-project=executespecv1`, `agent.deployment.workspace.gcs-bucket=executespec-workspaces` | All persisted state; long-lived workspaces (archive-after-hours=24); deployments; trace export when running with `@Profile("gcp")` | `application-prod.yml`, `application-dev.yml` |
| **Cloudflare** | TLS termination + DNS + WAF in front of `*.executespec.ai` | (infra, not in code) | Every HTTP request to a public hostname | Headers handled by Cloudflare; Spring sees `X-Forwarded-*` (`server.forward-headers-strategy: framework`) |
| **Stripe** | Payments (US-first default in prod) | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_TEAM_MONTHLY_PRICE_ID`, `STRIPE_ENTERPRISE_PRICE_ID`, `BILLING_DEFAULT_GATEWAY=stripe`, `BILLING_STRIPE_ENABLED` | Checkout, subscription, invoice PDF hosting, customer portal, webhooks | `StripePaymentGateway`, `BillingWebhookController` |
| **Razorpay** | Payments (INR default; dev default gateway) | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `webhook-secret`, `webhook-url=https://executespec.ai/api/webhooks/razorpay` | Checkout, recurring subscriptions, top-up orders, webhooks | `RazorpayGateway`, `RazorpayRestClient`, `BillingWebhookController` |
| **PostHog** | Product analytics, optional | `analytics.posthog.api-key`, `analytics.posthog.host=https://us.i.posthog.com`, `analytics.posthog.enabled` | Default `false` in `application.yml`; flip to true to capture events client-side and server-side | YAML config (sub-processor wiring only loads when enabled) |
| **GitHub** | Source-of-truth for customer repos, PR opening + check-run webhooks, OAuth login | `GITHUB_PAT`, `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`, OAuth callbacks under `/api/auth/oauth2/callback/*` | Every run that targets a GitHub repo; OAuth sign-in; check-run webhook delivery | `GitHubRepoService`, `ScmPushService`, `OAuthSuccessHandler`, `OAuthUserMapper` |
| **Bitbucket** | Optional source-of-truth for customer repos, BYOK per-user | `bitbucket_username` + `bitbucket_app_password` columns on `app_user` (encrypted) | When user configures a Bitbucket workspace + repo slug | `BitbucketRepoService` |
| **Google OAuth** | Identity provider for sign-in | `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`, redirect URI under our domain | Sign-in via Google | `OAuthSuccessHandler`, `OAuthUserMapper` (Google branch) |
| **Slack** | Optional integration for run-completion notifications | `SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET` | When user installs Slack app via `/api/integrations/slack/install` | `SlackIntegrationController`, `SlackOAuthService`, `SlackClient`, `SlackNotificationService` |
| **Atlassian JIRA / Confluence** | Optional integration for ticket-driven runs and spec ingestion | per-user `jira_base_url`, `jira_email`, `jira_api_token` (encrypted) + same for Confluence | When user wires JIRA/Confluence creds via `PUT /api/auth/settings/jira` etc. | `IntegrationController`, `IntegrationManager` |
| **Gmail (SMTP or Gmail API)** | Outbound transactional email | `app.mail.transport={smtp|gmail-api}`, SMTP creds or `GmailOAuthService` | Every account email (verify, reset, invoice, run-completion, dunning) | `MailService` (`sendVia{Smtp,GmailApi}`), `BillingNotificationService` |
| **Telegram** | Out-of-band operator bridge — **not customer-facing** | hardcoded `chat_id=6475810501` / bot `8489535450` | Internal operations only; not invoked by user actions | Internal scripts |

### Notes on the catalog

- **Multi-provider LLM routing.** A single run can hit one or more of {OpenAI, Anthropic, Google Gemini}, controlled by `application.yml model-matrix.failover-order:`. Provider selection is recorded per-call in `RunCostTracker`.
- **BYOK organisations.** Orgs with `org.key_source_mode = OWN_KEYS` use customer-supplied API keys (encrypted columns `anthropic_api_key`, `openai_api_key`, `gemini_api_key` on both `app_user` and `org`). Their LLM traffic still leaves our perimeter to the same sub-processors but is billed to the customer's own account.
- **Stripe vs Razorpay.** `billing.currency-gateway-map`: `INR → RAZORPAY; USD,EUR,GBP → STRIPE`. The default gateway is `stripe` in prod, `razorpay` in dev (per `application-prod.yml` / `application-dev.yml`).

## 5. Retention

### Tier-driven, per-account
`tier_config.retention_days` (column on [`TierConfig`](../src/main/java/com/navneet/executespec/auth/entity/TierConfig.java)) is nullable and read by the per-tier retention paths (e.g. activity-feed query-time enforcement). The column is part of the SUPER_ADMIN-editable `tier_config` row so retention can be tuned per tier without redeploy.

### Platform-wide floors
The activity-feed table is partitioned per month (`activity_feed_yYYYYmMM`) by [`ActivityFeedPartitionJob`](../src/main/java/com/navneet/executespec/activity/ActivityFeedPartitionJob.java) and pruned by [`ActivityFeedRetentionJob`](../src/main/java/com/navneet/executespec/activity/ActivityFeedRetentionJob.java):

- Cron `0 0 3 * * *` (daily 03:00).
- Default retention floor `activity.feed.retention.days=365`; min 30, max 3650.
- Flag-overridable via feature flag `activity.retention.days`.
- **Default `dry-run=true`** — partitions are not actually dropped in production until ops flips `activity.feed.retention.dry-run=false`. This is intentional but worth tracking.

### Project soft-delete
Projects soft-deleted via `DELETE /api/projects/{id}` get a `deleted_at` timestamp. A separate purge path uses `ProjectRepository.findByDeletedAtBefore(Instant)` (PR-H 2026-05-13 retention purge) — when wired into a sweeper that path becomes the hard-delete trigger after the 30-day undo window.

### Statutory retention (cannot be erased on demand)
- **Tax invoices**: kept indefinitely under Indian GST law; see [`Invoice`](../src/main/java/com/navneet/executespec/billing/entity/Invoice.java) and [`InvoiceSequence`](../src/main/java/com/navneet/executespec/billing/entity/InvoiceSequence.java).
- **Payment audit log** ([`PaymentAudit`](../src/main/java/com/navneet/executespec/auth/entity/PaymentAudit.java)): financial audit trail — kept for the duration of any open or potentially-disputable charge. Carries `gateway_event_id`, `gateway_payment_id`, `amount_minor`, `currency`, and a jsonb `metadata` blob.
- **Hash-chained activity audit** ([`ActivityAudit`](../src/main/java/com/navneet/executespec/auth/entity/ActivityAudit.java)): SHA-256 hash-chain rows (`prev_hash || row_hash || chain_seq`) underwrite tamper-evidence (M-019). Removing rows breaks the chain; verification endpoint at `GET /api/admin/audit-chain/verify` ([`ActivityAuditService.verifyChain()`](../src/main/java/com/navneet/executespec/auth/service/ActivityAuditService.java)) would surface the break. These rows are not deletable as a matter of platform integrity.

### Refresh-token blocklist
Revoked JTIs live in `refresh_token_blocklist` with explicit `expires_at` ([`RefreshTokenBlocklist`](../src/main/java/com/navneet/executespec/auth/entity/RefreshTokenBlocklist.java)). The repository exposes `@Modifying deleteExpired(Instant)` for a sweeper; rows are kept until their original token would have expired (≤ 7 days by default).

## 6. Encryption at rest

### Database — column-level

Per-row encryption uses Hibernate `@Convert(EncryptedValueConverter)` on sensitive columns. Verified columns:

- [`AppUser`](../src/main/java/com/navneet/executespec/auth/entity/AppUser.java): `openai_api_key`, `anthropic_api_key`, `gemini_api_key`, `bitbucket_app_password`, `jira_api_token`, `confluence_api_token`.
- [`Org`](../src/main/java/com/navneet/executespec/auth/entity/Org.java): `anthropic_api_key`, `openai_api_key`, `gemini_api_key`, `jira_api_token`, `confluence_api_token`.

`password_hash` is BCrypt strength=12 (set in [`SecurityConfig.passwordEncoder()`](../src/main/java/com/navneet/executespec/auth/security/SecurityConfig.java)). It is technically a one-way hash, not encryption, but it is also explicitly redacted from every export endpoint above.

### Redis — agent context cache

In-flight worker context (LLM messages, tool calls, tool responses) is cached in Redis at key `v2:agent-ctx:{runId}:{taskId}` with a 24-hour TTL — see [`ContextStore`](../src/main/java/com/navneet/executespec/context/ContextStore.java). This cache is treated as best-effort and ephemeral. There is an AES-256-GCM helper at [`KeyEncryptor`](../src/main/java/com/navneet/executespec/security/KeyEncryptor.java) that wraps short-lived secrets stored in Redis; its symmetric key is derived as SHA-256 of `app.jwt.secret`. A consequence of this design: rotating the JWT secret renders any pre-rotation Redis entries unreadable and the system gracefully falls back to `SYSTEM_ONLY` defaults.

### Cloud SQL & GCS — at-rest

Customer data persists in Cloud SQL Postgres and (for archived workspaces) GCS bucket `executespec-workspaces`. Both services apply Google Cloud's default at-rest encryption (AES-256). The application configures the connection through Hikari (`spring.datasource.hikari`); no app-layer DB encryption envelope is applied beyond the per-column encryption already noted.

### What is **not** encrypted at rest by the application
- `payment_audit.gateway_payment_id`, `gateway_event_id`, `metadata` jsonb — kept in clear for ops + reconciliation.
- `activity_feed.payload` jsonb — kept in clear; semantic content varies by event type.
- Invoice line items ([`InvoiceLineItem`](../src/main/java/com/navneet/executespec/billing/entity/InvoiceLineItem.java)) — kept in clear, including buyer GSTIN and address.

The GCP infrastructure baseline still encrypts these at rest; the application just doesn't add a second envelope.

## 7. Operational notes

- The `app.jwt.secret` is validated at boot in the `prod` profile by [`JwtSecretValidator`](../src/main/java/com/navneet/executespec/auth/security/JwtSecretValidator.java): must be present, not contain the dev placeholder marker `must-be`, and at least 32 chars. Boot fails fast otherwise.
- Webhook bodies (Razorpay, Stripe) are signature-verified BEFORE any processing — see [`WebhookSignatureVerifier`](../src/main/java/com/navneet/executespec/billing/security/WebhookSignatureVerifier.java) (Razorpay HMAC-SHA256, constant-time `MessageDigest.isEqual`) and the Stripe `Webhook.constructEvent` path in [`StripePaymentGateway`](../src/main/java/com/navneet/executespec/billing/gateway/StripePaymentGateway.java). A bad signature is a 401; a missing signature is a 401. Webhooks always reply 200 once verified (idempotency is upstream via `WebhookEventLog.gateway+event_id`) so the gateway never retries.
- `RefreshTokenBlocklistRepository` exposes `deleteExpired(Instant)` and `insertIfAbsent(jti, userId, expiresAt)` native upsert; ops can manually call the deletion query if backfill is needed.
- The reminder endpoint at `POST /api/internal/reminder/send` is gated by a shared-secret header (`X-Admin-Reminder-Token`) compared with `MessageDigest.isEqual`; if the secret is unset the endpoint 503s, if the header doesn't match it 403s. See [`AdminReminderController`](../src/main/java/com/navneet/executespec/auth/controller/AdminReminderController.java).

## 8. Known gaps & TODOs

1. **No automated hard-purge after soft-delete.** Account closure today is a soft-delete + policy promise. A sweeper for `app_user.deleted_at` past N days (mirroring `ProjectRepository.findByDeletedAtBefore` for projects) is not wired.
2. **ZIP export is capped at 200 rows per section.** Users beyond the cap must contact support. Pagination is not in the API surface.
3. **`GET /api/auth/generate?password=...`** still exists on `AuthController` — a developer-only BCrypt oracle that is reachable by any authenticated user (no `@PreAuthorize`). This is internal-tool noise, not a DSAR surface, but it is on the followup list.
4. **`activity.feed.retention.dry-run=true` default.** Production must explicitly flip this off; otherwise partitions are never dropped.
5. **AES-256-GCM Redis envelope ([`KeyEncryptor`](../src/main/java/com/navneet/executespec/security/KeyEncryptor.java)) keys off JWT secret.** Rotating the JWT secret invalidates any in-flight encrypted Redis entries — by design but worth noting before any rotation runbook.
6. **PostHog defaults off** (`analytics.posthog.enabled=false`) — wiring exists but no events are sent unless the flag is flipped. When flipped on, the catalog above will be live.
