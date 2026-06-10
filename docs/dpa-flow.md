# Data Processing And DSAR Flow

> Recreated 2026-06-10 from the current auth, DSAR, export, billing, and project code. This is an engineering reference, not a signed legal DPA.

## Implemented User Data Exports

ExecuteSpec has two export surfaces:

| Surface | Endpoint | Format | Scope | Code |
| --- | --- | --- | --- | --- |
| Personal ZIP export | `GET /api/auth/me/data-export` | ZIP with `manifest.json`, `payload.json`, `README.md` | Authenticated user's personal data bundle | `UserDataExportService` |
| DSAR JSON export | `GET /api/dsar/me` | JSON | Authenticated user's identity, payments, runs, notifications | `DsarController` |

`GET /api/dsar/users/{userId}` lets `SUPER_ADMIN` export on behalf of a user for legal/support handling.

## Export Contents

The ZIP export includes:

- user identity fields with secrets redacted,
- org rows visible through the user's org,
- project rows for the user's org,
- recent runs capped by `EXPORT_ROW_LIMIT`,
- recent notifications,
- active billing subscription and recent invoices.

The DSAR JSON export includes:

- `identity`
- `payments`
- `runs`
- `notifications`

Secrets are never exported as plaintext. Password hashes, API keys, GitHub credentials, and PAT plaintext are omitted or reduced to presence flags.

## Erasure Status

Self-service account deletion is not fully implemented as a hard-delete workflow. Current deletion/privacy posture:

- personal export exists,
- DSAR admin export exists,
- project export exists through `ProjectExportController`,
- statutory billing/audit retention remains,
- erasure requests still require an operator/legal workflow.

Do not claim automated GDPR/DPDP erasure until a coded deletion workflow exists and is tested.

## Sub-Processors

Live integrations that may process user/project data:

| Processor | Purpose | Configuration |
| --- | --- | --- |
| Anthropic | LLM inference | `ANTHROPIC_API_KEY` / BYOK lanes |
| OpenAI | LLM inference and embeddings | `OPENAI_API_KEY` / BYOK lanes |
| Google Gemini | LLM inference | `GOOGLE_API_KEY` / BYOK lanes |
| DeepSeek | LLM inference | `DEEPSEEK_API_KEY` / BYOK lanes |
| OpenRouter | Aggregated LLM inference | `OPENROUTER_API_KEY` / BYOK lanes |
| Fireworks | Aggregated LLM inference | `FIREWORKS_API_KEY` / BYOK lanes |
| Stripe | Payments, subscriptions, invoices | `STRIPE_*` |
| Razorpay | INR payments | `RAZORPAY_*` |
| GitHub / Bitbucket | Repository access and PR operations | User/org SCM credentials |
| Slack / Jira / Confluence / ServiceNow / PagerDuty | Optional integrations | Integration-specific credentials |
| GCP | Hosting, database, storage, previews | Cloud SQL, Redis, GCS, GCE, Cloud Run |

## Retention

Retention is mixed:

- application data is retained while the account/org/project exists,
- run workspaces are operational artefacts and can be archived/cleaned separately,
- billing/audit data must be retained for statutory and fraud/audit reasons,
- activity retention is controlled separately by activity/audit cleanup code and feature flags.

Before promising a retention period externally, check the actual cleanup job or migration, not this summary.

## Code Pointers

- `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`
- `src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java`
- `src/main/java/com/navneet/executespec/auth/dsar/DsarController.java`
- `src/main/java/com/navneet/executespec/export/ProjectExportController.java`
- `src/main/java/com/navneet/executespec/auth/service/PaymentAuditService.java`
- `src/main/java/com/navneet/executespec/billing/`
