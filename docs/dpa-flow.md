# DPA / GDPR — data export, erasure, sub-processors

> Last refreshed: 2026-05-16. Code-truth source:
> `src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java`,
> `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`,
> `src/main/java/com/navneet/executespec/auth/dsar/DsarController.java`,
> `src/main/java/com/navneet/executespec/compliance/DataRetentionService.java` (referenced from `AuthController`).
>
> This doc covers the **code-backed** parts of the GDPR / DPDP / CCPA story.
> Anything not citable is marked **policy / contractual**.

---

## 1. Two parallel export surfaces

There are two GDPR-export endpoints, both shipped:

| Endpoint                                       | Output  | Caller                                  | Where                                                            |
| ---------------------------------------------- | ------- | --------------------------------------- | ---------------------------------------------------------------- |
| `GET /api/auth/me/data-export`                 | ZIP     | The authenticated user themselves       | `AuthController.exportMyData` (lines 182-191)                    |
| `GET /api/dsar/me`                             | JSON    | The authenticated user themselves       | `DsarController.exportSelf` (lines 75-80)                        |
| `GET /api/dsar/users/{userId}`                 | JSON    | SUPER_ADMIN acting on behalf of legal   | `DsarController.exportFor` (lines 82-89)                         |

The ZIP path (`/api/auth/me/data-export`) is the **canonical** GDPR Article 15
(right of access) + Article 20 (data portability) surface. The JSON DSAR path
(`/api/dsar/me`) was added by M-017 (Wave 3) for legal-team workflows. Both
return the same conceptual data; the ZIP wraps it for end-user consumption
while DSAR is a raw JSON document.

---

## 2. `GET /api/auth/me/data-export` — the ZIP

### Controller

`AuthController.exportMyData` (lines 182-191) delegates to
`UserDataExportService.exportForUser(userId)` and sets:

```
Content-Disposition: attachment; filename="executespec-export-<userId>-<YYYY-MM-DD>.zip"
Content-Type: application/zip
```

The endpoint requires authentication (matches the `/api/auth/me/**` rule in
`SecurityConfig`); no extra role is needed.

### Service

`UserDataExportService.exportForUser(String userId)` is `@Transactional(readOnly =
true)`. It builds the bundle synchronously, in memory, and returns an
`ExportBundle(byte[] zipBytes, String suggestedFilename)` record.

Per-list cap: `EXPORT_ROW_LIMIT = 200`
(`UserDataExportService.java:80`). The service comment notes that this is the
in-memory safety bound; power-users with thousands of runs would need async +
GCS hand-off, which is a future schema bump.

### ZIP contents

Three entries:

| Entry           | Format         | Contents                                                                 |
| --------------- | -------------- | ------------------------------------------------------------------------ |
| `manifest.json` | JSON           | Metadata: `exportedAt`, `exportedFor`, `schemaVersion=2`, `rowLimit`, `scope`, `rights` |
| `payload.json`  | JSON           | The actual data — keys listed below                                      |
| `README.md`     | Markdown text  | Plain-English explanation of files, scope, redactions, erasure pathway   |

### Manifest schema (verbatim from `UserDataExportService.java:99-112`)

```json
{
  "exportedAt": "2026-05-16T12:34:56Z",
  "exportedFor": "<user-uuid>",
  "schemaVersion": 2,
  "rowLimit": 200,
  "scope": "Personal data only. Org / project data shared with other users is NOT included unless you are the sole member.",
  "rights": "GDPR Article 15 (access) + Article 20 (portability). Contact admin@executespec.ai for erasure (Article 17) requests."
}
```

### Payload top-level keys

| Key                            | Cardinality                       | Source row                                                       |
| ------------------------------ | --------------------------------- | ---------------------------------------------------------------- |
| `user`                         | one redacted object               | `AppUser` (via `redactUser`, see §3)                             |
| `orgs`                         | up to 1                           | The user's `Org`, looked up by `user.org_id`                     |
| `projects`                     | n (no cap)                        | `projectRepo.findByOrgId(orgId)`                                 |
| `runs`                         | up to 200                         | `pipelineRunRepo.findByCreatedByUserIdOrderByStartedAtDesc(…)`   |
| `notifications`                | up to 200                         | `notificationRepo.findByUserIdOrderByCreatedAtDesc(…)`           |
| `billing.activeSubscription`   | one or null                       | `subRepo.findActiveByUserId(userId)`                             |
| `billing.invoices`             | up to 200                         | `invoiceRepo.findByUserIdOrderByInvoiceDateDesc(…)`              |
| `_note`                        | string                            | Schema disclaimer                                                |

### What is NOT included (verbatim, lines 53-62)

- Other users' data — cross-org rows are excluded. Even when the requesting
  user is an ORG_ADMIN, this endpoint exports their **personal** data only,
  not their org's broader dataset. (For org-scoped export use
  `POST /api/projects/{id}/export` — `ProjectExportController`.)
- Workspace files on disk. These live in per-run workspace dirs on the GCE
  VM (or in GCS for shipped runs) and are accessible via the existing per-run
  download endpoints. They are deliberately not bundled into the personal
  export to keep the response under MB-scale.
- Internal audit hash chains — `activity_audit` rows have a `hash_chain_value`
  used for integrity proofs; the service does not expose this to the user.

---

## 3. Sensitive-field redaction

`UserDataExportService.redactUser(AppUser)` (lines 189-212) projects the
`AppUser` entity to a minimal, secret-free record:

```java
redacted.put("id", u.getId());
redacted.put("email", u.getEmail());
redacted.put("firstName", u.getFirstName());
redacted.put("lastName", u.getLastName());
redacted.put("globalRole", u.getGlobalRole());
redacted.put("tier", u.getTier());
redacted.put("orgId", u.getOrgId());
redacted.put("createdAt", u.getCreatedAt());
redacted.put("updatedAt", u.getUpdatedAt());
redacted.put("emailVerified", u.isEmailVerified());
redacted.put("hasAnthropicKey", u.getAnthropicApiKey() != null);
redacted.put("hasOpenAiKey", u.getOpenAiApiKey() != null);
redacted.put("hasGeminiKey", u.getGeminiApiKey() != null);
```

Fields explicitly **excluded**:

- `password_hash`
- `anthropic_api_key`, `openai_api_key`, `gemini_api_key` (only `hasXxxKey`
  booleans are surfaced)
- `github_pat`
- `bitbucket_app_password`
- `jira_api_token`, `confluence_api_token`
- `stripe_customer_id` (PII-adjacent — stays in billing.activeSubscription
  payload, not in the user object)
- `token_invalidated_at` (internal session-mgmt field)

The README explicitly says ("What's redacted" section, lines 249-251):

> Your password hash + LLM API keys + GitHub PAT are intentionally excluded.
> You can recover or rotate them from Settings → Account.

---

## 4. `GET /api/dsar/me` — the JSON DSAR document

`DsarController` (M-017, Wave 3) is the **separate** DSAR surface used by
the legal team and for SUPER_ADMIN act-on-behalf-of requests.

### Endpoints

| Method | Path                          | Auth                         |
| ------ | ----------------------------- | ---------------------------- |
| GET    | `/api/dsar/me`                | Any authenticated user       |
| GET    | `/api/dsar/users/{userId}`    | `@PreAuthorize("hasRole('SUPER_ADMIN')")` — logged at WARN |

### Document shape (verbatim from `DsarController.buildExport`, lines 91-154)

```json
{
  "schemaVersion": 1,
  "generatedAt": "<ISO instant>",
  "identity": {
    "id": "…",
    "email": "…",
    "firstName": "…",
    "lastName": "…",
    "tier": "FREE | PRO | TEAM | ENTERPRISE",
    "globalRole": "USER | SUPER_ADMIN | …",
    "status": "ACTIVE | SUSPENDED | PENDING_VERIFICATION",
    "oauthProvider": "google | github | null",
    "orgId": "…",
    "githubOwner": "…",
    "createdAt": "…",
    "updatedAt": "…",
    "hasGithubPat": true | false,
    "hasOpenAiKey": true | false,
    "hasAnthropicKey": true | false,
    "hasGeminiKey": true | false
  },
  "payments": [
    { "id", "createdAt", "eventType", "entityType", "entityId",
      "oldTier", "newTier", "gatewayProvider", "paymentRefId",
      "currency", "amountMinor" }
  ],
  "runs":   [ { "runId", "startedAt", "completedAt", "status", "statusReason",
                 "userRequest", "aiProvider", "actualCostUsd", "projectId", "orgId" } ],
  "notifications": [ { "id", "createdAt", "type", "title", "body", "read" } ],
  "note":   "Per GDPR Art. 15 / CCPA / DPDP … Live credentials are redacted …"
}
```

Caps: 1000 runs, 1000 notifications. The full `PaymentAudit` set is included
(no cap; usually small).

### Difference between `/api/auth/me/data-export` and `/api/dsar/me`

| Aspect                | `/api/auth/me/data-export`                | `/api/dsar/me`                      |
| --------------------- | ----------------------------------------- | ----------------------------------- |
| Output format         | ZIP (manifest + payload + README)         | Single JSON document                |
| Caller workflow       | End-user-facing "Download my data" button | Legal / DSAR ticket fulfilment      |
| Row caps              | 200 per list                              | 1000 runs, 1000 notifications       |
| Includes invoices?    | Yes (`billing.invoices`)                  | No — payments is `PaymentAudit` only |
| Includes orgs / projects? | Yes                                   | No — identity only                  |
| Schema version        | 2                                         | 1                                   |
| `@PreAuthorize`?      | No (any authenticated)                    | No on `/me`; SUPER_ADMIN on `/users/{id}` |

Both intentionally exclude live credentials. The platform position is that
**disclosure of what we hold ≠ handing back live secrets** — the user can
rotate keys via Settings.

---

## 5. Right to erasure (GDPR Article 17)

### Code path

`AuthController.eraseMyData` (lines 634-641):

```java
@DeleteMapping("/me/data")
public ResponseEntity<Map<String, Object>> eraseMyData(
    @AuthenticationPrincipal AppUserDetails userDetails) {
  if (userDetails == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
  int deleted = dataRetentionService.eraseUserData(userDetails.getUser().getId());
  return ResponseEntity.ok(Map.of("runsScheduledForDeletion", deleted));
}
```

Endpoint: `DELETE /api/auth/me/data`. The controller doc-comment summarises
the contract:

> Soft-deletes all completed/failed runs belonging to the authenticated user.
> Active runs (currently executing) are skipped and must be cancelled first.
> The actual workspace GCS deletion happens asynchronously via the retention
> scheduler.

The implementation lives in
`compliance/DataRetentionService.eraseUserData(String userId)` — the count
returned in `runsScheduledForDeletion` is the number of runs the soft-delete
touched.

### What erasure does NOT do today

- It does **not** delete the `app_user` row itself. The user can still log
  in; their identity is preserved. Full account closure is **policy /
  contractual** — the README in the export bundle directs users to email
  `admin@executespec.ai` for the 30-day manual deletion path (lines 252-255):

  > If you want your data deleted (GDPR Article 17), email
  > `admin@executespec.ai` — that path is processed manually within 30 days.

- It does **not** cascade across `org`, `project`, `pipeline_run.project_id`
  ownership when the user is the **sole** owner of those rows. A future
  pass would need to either (a) reassign ownership before delete or (b)
  cascade-soft-delete the linked entities. Tracked as a follow-up in the
  retention service.

- It does **not** delete `payment_audit` or `invoice` rows — financial
  records have a longer retention obligation (typically 7 years for tax
  audit, **policy / contractual**).

### Tier-driven retention windows

`tier_config.retention_days` (`auth/entity/TierConfig.java:85-87`):

| Tier        | Default `retention_days` |
| ----------- | ------------------------ |
| FREE        | 30                       |
| PRO         | 90                       |
| ENTERPRISE  | 365                      |

The column defaults to "system default (30)" per the javadoc; the actual
values are seeded by tier-config migrations. The retention scheduler
(`compliance/DataRetentionService` scheduled job, referenced from
`AuthController`) sweeps rows older than the tier window and soft-deletes
them. Erasure on demand is layered on top of this passive sweep.

---

## 6. Sub-processors (data leaving ExecuteSpec)

This list is derived from `.env.template` + `application.yml` integrations.
Each entry describes **what data we send** to the third party. Any
processor-level legal undertakings (DPA signed, sub-processor list
published, transfer mechanism) are **policy / contractual** — verify with
`docs/legal/dpa.md` or the customer agreement before quoting.

| Sub-processor       | Used for                                                       | Data sent                                                                                                                                    | Mode                                                                                       | Env vars                                                                                                                                                |
| ------------------- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Anthropic**       | Claude LLM calls (planner, worker, reviewer)                    | Spec text, code snippets, tool-call outputs (file contents up to 8k chars, shell output 6k chars, diff 5k chars — `WorkerAgent` truncation).  | HTTPS to api.anthropic.com. With BYOK keys, the customer's own Anthropic org is the endpoint, not ours. | `ANTHROPIC_API_KEY`                                                                                                                                     |
| **OpenAI**          | GPT LLM calls + text-embeddings for RAG                         | Spec text, code, embeddings of uploaded RAG documents.                                                                                       | HTTPS to api.openai.com.                                                                   | `OPENAI_API_KEY`                                                                                                                                        |
| **Google (Gemini)** | Gemini LLM calls                                                | Spec text, code, tool-call outputs.                                                                                                          | HTTPS to generativelanguage.googleapis.com.                                                | `GOOGLE_API_KEY`                                                                                                                                        |
| **Stripe**          | USD card-payment processing + invoicing                         | Email, name, billing address, card token (Stripe Elements — card data never touches our backend), subscription metadata.                       | HTTPS to api.stripe.com. Webhooks signed with `STRIPE_WEBHOOK_SECRET`.                     | `STRIPE_SECRET_KEY`, `STRIPE_PUBLISHABLE_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRO_PRICE_ID`, `STRIPE_ENTERPRISE_PRICE_ID`, `STRIPE_TEAM_PRICE_ID`(_INR)|
| **Razorpay**        | INR card / UPI / netbanking payment processing                  | Email, name, billing address, payment instrument tokens, subscription metadata.                                                              | HTTPS to api.razorpay.com. Webhooks signed with `RAZORPAY_WEBHOOK_SECRET`.                | `RAZORPAY_KEY_ID`, `RAZORPAY_KEY_SECRET`, `RAZORPAY_WEBHOOK_SECRET`, `RAZORPAY_WEBHOOK_URL`                                                              |
| **GitHub**          | Source-code fetch, branch push, PR creation, webhooks            | Customer's GitHub PAT or App installation token; clones repos to GCE VM workspace dir; pushes branches; opens PRs.                            | HTTPS to api.github.com. PR webhook back to `/webhooks/github` (HMAC SHA-256 verified).    | `GITHUB_PAT`, `GITHUB_OWNER`, `GITHUB_OAUTH_CLIENT_ID`, `GITHUB_OAUTH_CLIENT_SECRET`                                                                     |
| **Bitbucket**       | Same as GitHub, for Bitbucket-hosted repos                       | Workspace + repo slug + per-user app password (encrypted at rest).                                                                           | HTTPS to api.bitbucket.org.                                                                | Stored in `app_user` row only.                                                                                                                          |
| **Slack**           | Run-completion notifications + slash-command surfaces            | Run id, status, score, customer-facing message. OAuth bot tokens stored per-org.                                                              | HTTPS to slack.com/api. OAuth callback at `/api/integrations/slack/oauth/callback`.        | `SLACK_CLIENT_ID`, `SLACK_CLIENT_SECRET`, `SLACK_SIGNING_SECRET`                                                                                         |
| **PostHog**         | Wave 2.1 activation funnel events (signup, plan-approval, etc.) | User id, event name (e.g. `SIGNUP`), event properties (`signupMethod`, `tier`, `utmSource`, …).                                              | HTTPS to `${POSTHOG_HOST}` (default `us.i.posthog.com`). Disabled when `POSTHOG_API_KEY` is blank — local dev is a no-op. | `POSTHOG_ENABLED`, `POSTHOG_API_KEY`, `POSTHOG_HOST`                                                                                                    |
| **JIRA / Confluence** | Customer-configured ticket sync (per-user or per-org)         | Run summaries, PR links. Credentials encrypted at rest in `app_user` / `org` rows.                                                           | HTTPS to customer's Atlassian instance.                                                    | None at platform level; per-org via Org Settings.                                                                                                        |
| **Google Cloud (GCP)** | Hosting: Cloud SQL (Postgres), GCE VM, Cloud Run previews, Cloud Trace, Cloud Monitoring | All customer data at rest + in flight. Region us-east4 (see `docs/enterprise-sla.md` §3).                                                       | Cloud SQL private IP, GCE us-east4-a.                                                      | `GCP_PROJECT`, `GCP_REGION`                                                                                                                              |
| **Cloudflare**      | DNS, CDN, DDoS in front of `*.executespec.ai`                    | TLS-terminated traffic forwarded to GCE VM. Headers like `X-Forwarded-For` are propagated.                                                    | Edge.                                                                                       | Not in env; managed at DNS layer.                                                                                                                       |

### Mail provider

`app.mail.transport` (in `application.yml:667`) is `smtp` by default,
delegating to `smtp.gmail.com:587` with the `admin@executespec.ai` mailbox.
Gmail / Google Workspace is therefore the email sub-processor for
transactional mail (verification, password reset, project invites,
notifications). An alternative `gmail-api` OAuth transport is configurable
but disabled by default.

---

## 7. Storage of sensitive fields at rest

| Field                              | Storage                             |
| ---------------------------------- | ----------------------------------- |
| `password_hash`                    | bcrypt cost 12 (`SecurityConfig.passwordEncoder` line 286) |
| `anthropic_api_key`                | AES-256-GCM via `EncryptedValueConverter` (entity-level `@Convert`) |
| `openai_api_key`                   | same                                |
| `gemini_api_key`                   | same                                |
| `bitbucket_app_password`           | same                                |
| `jira_api_token`                   | same                                |
| `confluence_api_token`             | same                                |
| `github_pat`                       | stored as plaintext (entity comment at `auth/entity/AppUser.java:108-110`: "Stored as plaintext.") — **known tech debt**, future migration to encrypt |
| `stripe_customer_id`               | plaintext (it's a tokenised identifier, not a secret) |

The crypto key is resolved per environment (`executespec.crypto.local-key`
env var or GCP Secret Manager). See
`src/main/java/com/navneet/executespec/crypto/EncryptedValueConverter.java`
for the converter implementation.

---

## 8. Audit trail

Every export, settings change, role change, payment event, etc., writes an
`activity_audit` row via `ActivityAuditService.record(…)`. Relevant event
types touched by the auth / DPA path:

| Event type                       | When                                                       | Where                                                              |
| -------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------------------ |
| `USER_REGISTERED`                | Email + password signup                                     | `AuthService.register`                                              |
| `USER_REGISTERED_OAUTH`          | New OAuth user                                              | `AuthService.findOrCreateFromOAuth`                                 |
| `EMAIL_VERIFIED`                 | Verification token consumed                                 | `AuthService.verifyEmail` / `verifyEmailAndAuthenticate`            |
| `PASSWORD_RESET`                 | Reset-token consumed                                        | `AuthService.resetPassword`                                         |
| `PASSWORD_CHANGED`               | Authenticated change-password call                          | `AuthService.changePassword`                                        |
| `USER_GITHUB_SETTINGS_CHANGED`   | PAT / owner update                                          | `AuthController.updateGitHubSettings`                               |
| `USER_AI_KEYS_CHANGED`           | AI key save                                                 | `AuthController.updateAiKeySettings`                                |
| `USER_JIRA_SETTINGS_CHANGED`     | JIRA creds save                                             | `AuthController.updateJiraSettings`                                 |
| `USER_CONFLUENCE_SETTINGS_CHANGED` | Confluence creds save                                     | `AuthController.updateConfluenceSettings`                           |
| `USER_BITBUCKET_SETTINGS_CHANGED` | Bitbucket creds save                                       | `AuthController.updateBitbucketSettings`                            |
| `ORG_EXTERNAL_SHARING_CHANGED`   | V198 flag toggled                                           | `OrgService.updateExternalSharing` (referenced from `OrgController`) |
| (DSAR act-on-behalf)             | SUPER_ADMIN calls `/api/dsar/users/{userId}`                | Logged at WARN in `DsarController.exportFor` line 86               |

The audit log has its own integrity guarantee — a hash chain over
sequential rows (managed by `ActivityAuditService`); the export bundle
**deliberately does not** expose the hash field to the user (see
`UserDataExportService.java:60-62`).

---

## 9. Operator checklist (what to tell a procurement reviewer)

When a customer's procurement team asks "do you support GDPR?", the
honest, code-grounded answer is:

| Right                              | Code-backed?                                          |
| ---------------------------------- | ----------------------------------------------------- |
| **Right of access** (Art. 15)      | Yes — `GET /api/auth/me/data-export`                  |
| **Right to portability** (Art. 20) | Yes — same endpoint, JSON inside the ZIP             |
| **Right to rectification** (Art. 16) | Yes — Settings UI lets the user update name, email, integrations |
| **Right to erasure** (Art. 17)     | Partial — `DELETE /api/auth/me/data` soft-deletes runs; full account closure is **policy** (`admin@executespec.ai` within 30 days) |
| **Right to restriction** (Art. 18) | Policy only — flag a user as `SUSPENDED` via ORG_ADMIN |
| **Right to object** (Art. 21)      | Policy — opt out of analytics by emailing admin       |
| **Automated decision-making** (Art. 22) | Not applicable — there is no automated decision that produces legal or similarly significant effects on a natural person; LLM-generated code is reviewed by the customer before any deploy |

---

## 10. References

- `auth/service/UserDataExportService.java` — ZIP export service.
- `auth/controller/AuthController.java`
  (`exportMyData` lines 182-191, `eraseMyData` lines 634-641).
- `auth/dsar/DsarController.java` — JSON DSAR surface.
- `compliance/DataRetentionService.java` — retention sweep + erasure
  implementation (not read for this doc; behaviour documented from the
  caller's perspective).
- `auth/entity/TierConfig.java:85-87` — `retention_days` column.
- `auth/entity/AppUser.java:137-148, 157-159, 179-181` — `@Convert(converter
  = EncryptedValueConverter.class)` markers on every encrypted secret.
- `.env.template` + `application.yml` — sub-processor env vars.
