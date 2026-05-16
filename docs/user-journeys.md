# System-wide user journeys

> Cross-cutting journeys that aren't project-specific: signup, billing upgrade, BYOK setup, teammate invitations, SSO config, audit log review. Derived from the code on this branch (migration head V199).

## Journey 1 — Signup → first verified run (no GitHub repo)

Goal: a brand-new user goes from landing page to a verified PR in a sandbox spec without needing to connect any external accounts.

1. **Land on the marketing site.** `landing/LandingController` serves the public landing page from `static/index.html`. CTA → `/register`.
2. **Register.** UI: `/register` (`AuthPages.tsx`). Collects email + first/last name + password (≥12 chars). Posts `POST /api/auth/register {email, firstName, lastName, password, signupSource?, utmSource?, utmCampaign?}` (`auth/controller/AuthController`).
3. **Auto-create user + personal org.** `AuthService.register` writes `AppUser` with `globalRole=USER` + `tier=FREE` + `email_verified=FALSE`. `OrgService.bootstrapPersonalOrg(userId)` writes an `org` row with `is_personal=TRUE` (V162). This unblocks `POST /api/projects` for the FREE-tier user.
4. **Verify email.** Verification email sent via `app.mail.transport=smtp`. User clicks the link → `GET /api/auth/verify-email?token=...` returns `{accessToken, user}` (auto-session, no password re-entry per [F-29], 2026-04-30). UI stores the token and routes to `/welcome`.
5. **Pick a sandbox starter.** UI: `/welcome` (`EmptyProjectPage`). "Path 2 · Try a sandbox" → `POST /api/onboarding/pick-sample {sampleId}` (`onboarding/OnboardingController`). Backend provisions a sandbox project under the personal org and seeds a sample spec body. Returns `{projectId, spec, projectType, preferredProvider}`.
6. **Submit the spec.** UI auto-navigates to `/compose` with the sandbox project pre-selected and spec pre-filled. User reviews and clicks Submit. `POST /api/runs` → 202 RunSummary.
7. **Approve the plan + watch live.** Same shape as the in-product flow — `/plan/:runId` → approve → `/live/:runId`. FREE tier is capped at 1-credit specs (`agent.cost.max-credits-free=1`) so the plan gate only ever offers 1 credit on FREE.
8. **Review the result.** `/result/:runId` → ScoreCard. FREE users have `canDeploy=false` so the "Preview deploy" row reads "Upgrade" instead of "Start".
9. **Activation funnel.** `EventName.PLAN_APPROVED` and `EventName.RUN_COMPLETE` are emitted to PostHog (when `analytics.posthog.enabled=true`) and to the in-DB `activation_event_ledger` (V125).

## Journey 2 — Upgrade from FREE to TEAM

Goal: a FREE user hits the monthly cap and upgrades to TEAM.

1. **Hit the FREE quota.** User submits a 6th run in the calendar month. `POST /api/runs` calls `tierEnforcementService.assertAndIncrementRunCount(userId)` which throws HTTP 429 ("Monthly run cap exceeded — limit 5"). UI surfaces this on `/home` via the "All runs" quick-card ("5 / 5 this cycle") and on `/compose` via the "You've used all 5 runs in this billing cycle" banner.
2. **Open the upgrade flow.** Either:
   - Click "Top up credits" → `/topup` (instant, no plan change), or
   - Click "Upgrade your plan" → `/plans`.
3. **Pick a plan.** `/plans` (`PlansPage.tsx`) calls `GET /api/billing/plans` (`billing/controller/BillingController`). PRO is filtered server-side (V122 `lifecycle_state=HIDDEN`) so the user sees FREE / TEAM / ENTERPRISE.
4. **Subscribe.** Click "Subscribe" on TEAM. UI calls `POST /api/billing/subscribe {planId, currency, seatCount?}` (`BillingController.subscribe`). `PaymentGatewayRouter` picks Razorpay (INR) or Stripe (USD/EUR/GBP) per `currency-gateway-map`. Returns a checkout URL.
5. **Pay.** User redirects to Stripe / Razorpay checkout, pays, returns to `/billing?session_id=…`.
6. **Webhook fires.** Gateway posts `POST /api/webhooks/stripe` or `POST /api/webhooks/razorpay` (`BillingWebhookController`). HMAC verified (`STRIPE_WEBHOOK_SECRET` / Razorpay HMAC). `BillingService.handleSubscriptionCreated` writes `billing_subscription` row, updates `org.tier='TEAM'`, fires `TierUpgradedEvent`.
7. **V199 sync.** `V199__sync_org_tier_with_subscription_tier.sql` ensures `org.tier` stays in sync with the active subscription's tier on any subsequent webhook.
8. **Use the new tier.** Refresh `/home` → `permissions` re-fetched from `GET /api/auth/me/permissions` → monthly cap shows "0 / ∞" → composer unlocks multi-credit specs, multi-repo selection up to 5, BYOK panel visible, audit log visible.

Adjust seats later: `/org-members` → "Adjust seats" → `POST /api/org/me/seats/adjust {newSeats}` (`OrgController`). Range 2–20 self-serve. Stripe gets a prorated quantity update. Refuses to drop below current active member count.

## Journey 3 — Set up BYOK (bring-your-own-keys)

Goal: a PRO+ user provides their own Anthropic / OpenAI / Gemini keys so platform cost caps don't apply.

1. **Open API keys.** UI: `/api-keys` (`MiscAccountPages.tsx ApiKeysPage`). Hidden on FREE via `Shell.tsx:58`.
2. **Validate keys first.** For each provider field, click "Test" → `POST /api/auth/settings/ai-keys/validate {provider, apiKey}` (`AuthController`). Pings the provider's models endpoint. Returns `{valid, message}`.
3. **Save per-user keys.** "Save" → `PUT /api/auth/settings/ai-keys {openAiApiKey, anthropicApiKey, geminiApiKey}`. Keys are KMS-wrapped (`executespec.crypto.enabled=true` in prod).
4. **Switch the org to OWN_KEYS mode (ORG_ADMIN only).** `PUT /api/org/me/settings/key-source {keySourceMode: 'OWN_KEYS', enabledProviders: ['ANTHROPIC', 'OPENAI', 'GEMINI']}` (`OrgController`).
5. **Verify cost-cap exemption.** Next run submission: `RunOrchestrator.persistEstimate` checks `org.keySourceMode == 'OWN_KEYS'` and skips the `hardCapUsd` ceiling (`RunOrchestrator.java:1550`). `userCostCap` from the DTO still applies if set.
6. **Provider failover.** When the user's key for one provider fails, the YAML `failover-order` chain (anthropic → openai → gemini, etc.) picks the next enabled provider's key via `ApiKeyResolver`. Failover events emitted as `RUN_PROVIDER_SWITCHED` over SSE so the live log shows the swap.

Per-org keys (TEAM tier): `PUT /api/org/me/settings/ai-keys` (ORG_ADMIN only) shares one set of keys across all org members.

## Journey 4 — Invite a teammate to the org

Goal: ORG_ADMIN invites a developer who'll be added to the org and (optionally) specific projects.

1. **Open the team page.** UI: `/org-members` (`MiscAccountPages.tsx OrgMembersPage`).
2. **Invite.** "Invite member" button opens a modal. Fields: `email`, `orgRole` (DEVELOPER / BILLING_ADMIN / ORG_ADMIN), `projectIds[]` (optional project pre-assignment). POST `/api/org/invite {email, orgRole, projectIds}` (`OrgController`).
3. **Invitation row created.** `OrgInvitation` row written with `status=PENDING`, `expires_at = NOW() + 14 days`, unique `token`. Email sent via `app.mail` with the accept link.
4. **Recipient accepts.** UI: `/accept-invite?token=<token>` (`AcceptInvitePage` in `ExtraPages.tsx`). Calls `POST /api/org/accept-invite?token=<token>` (`OrgController`). Email-match check; conflict if the email doesn't match the logged-in user. On success, creates `OrgMember` row with the assigned `orgRole`, optionally adds `ProjectMember` rows for any `projectIds` from the invite.
5. **Audit row written.** `ActivityAudit` row with `eventType=MEMBER_INVITED` / `MEMBER_ACCEPTED`. Visible on `/audit` (TEAM+ only).
6. **TEAM seat-count check.** If the org is TEAM tier and adding this member would exceed `org.seatCount`, the accept call returns HTTP 402 with a message asking the admin to adjust seats first (`POST /api/org/me/seats/adjust`).

Resend / revoke: UI shows pending invitations with "Resend" (`POST /api/org/me/invitations/{id}/resend`) and "Revoke" actions.

Suspend / remove: `PUT /api/org/me/members/{userId}/suspend {suspended: true}` blocks login without removing the row. `DELETE /api/org/me/members/{userId}` removes.

Change role: `PUT /api/org/me/members/{userId}/role {role}` flips DEVELOPER ↔ BILLING_ADMIN ↔ ORG_ADMIN.

## Journey 5 — Accept an invite as a brand-new user

Goal: an external email recipient (no existing ExecuteSpec account) accepts an invitation.

1. **Receive the email.** Invite email contains `https://executespec.ai/accept-invite?token=<token>`.
2. **Click the link.** Lands on `/accept-invite?token=…`. UI calls `GET /api/invites/preview?token=` (`sharing/controller/ProjectInviteController` for project invites, `OrgController` for org invites). Returns `{invitedEmail, projectName | orgName, role, expiresAt}`. No auth required for preview — whitelisted in `SecurityConfig`.
3. **Sign up if needed.** If the recipient has no account, UI prompts "Create account" → `/register?invite=<token>`. Email is pre-filled and locked to the invited email so the email-match check on accept can't fail.
4. **Verify email.** Same as Journey 1 step 4.
5. **Accept.** UI calls `POST /api/invites/accept?token=…` (or `POST /api/org/accept-invite` for org invites). Creates `ProjectMember` with `is_external=TRUE` (V175) for project invites, or `OrgMember` for org invites.
6. **Routed to the project.** UI navigates to `/projects/:projectId` (project invite) or `/home` (org invite).

## Journey 6 — Configure SSO (Enterprise / TEAM)

Goal: ORG_ADMIN configures SAML or OIDC SSO so org members log in via their identity provider.

1. **Open SSO settings.** UI: `/integrations` → "Single Sign-On" card (TEAM+ tier surface).
2. **Choose provider.** SAML, Google Workspace OIDC, Okta, Azure AD.
3. **Configure.** `POST /api/sso/config {provider, metadataUrl | issuerUrl, clientId, clientSecret, attributeMap}` (`auth/sso/SsoConfigController`). Stores in `sso_config` table (V127 schema).
4. **Test.** "Test SSO" button → backend does a server-to-server probe of the IdP metadata. Returns `{valid, message}`.
5. **Enable for the org.** `PUT /api/sso/config/{id}/enable`. Sets `org.sso_enabled=TRUE`. Future logins from the org's email domain pattern are redirected to `/auth/oauth2/init?surface=sso&orgId=<id>` which kicks off the IdP flow.
6. **SCIM provisioning (Enterprise only).** SCIM endpoint `/scim/v2/Users` accepts standard SCIM 2.0 payloads with a per-org token. Provisions / deprovisions `AppUser` + `OrgMember` rows on user assignment / unassignment in the IdP.
7. **Login flow.** Org member visits `/login` → enters email → backend matches the email's domain to the org's `domain_patterns` → redirects to IdP → IdP redirects back to `/auth/callback/sso?code=…&state=…` (`AuthCallbackPage.tsx`) → backend exchanges the code, mints a session, redirects to `/home`.

## Journey 7 — Review org-level audit log

Goal: an ORG_ADMIN reviews compliance-relevant events across the org for a quarterly review.

1. **Open the audit log.** UI: `/audit` (`AuditLogPage` in `ExtraPages.tsx`). Sidebar entry gated on tier TEAM+ or SUPER_ADMIN (`Shell.tsx:63`).
2. **Tier / role gate.** UI calls `GET /api/audit-logs?page=0&size=100` (`activity/ActivityFeedController`). 403 branches the error message:
   - FREE / PRO tier → "Audit log is a TEAM+ feature. Upgrade your plan to enable it."
   - TEAM+ but not ORG_ADMIN → "Audit log is restricted to org admins. Ask your org admin to grant you access."
   - TEAM+ ORG_ADMIN but disabled → "Audit log isn't enabled for your org yet. Open Org Settings → Compliance to turn it on, or contact support if the toggle isn't visible."
3. **Browse and filter.** Tabs: All / Auth / Billing / Members / Admin. Each row shows action name, actor email, target id, timestamp. Backed by `activity_audit` table (partitioned by month).
4. **Verify hash chain (SUPER_ADMIN).** `GET /api/admin/audit-chain/verify` returns `{totalChained, verified, clean, firstBreak: {rowId, chainSeq, reason} | null}`. Confirms the hash chain hasn't been tampered with since the last verified row (M-019 / M-042).
5. **Export.** "Export filtered as CSV" button generates a CSV client-side from the filtered list. For a real compliance handover, ORG_ADMIN can call `GET /api/admin/payment-audit/export` (SUPER_ADMIN-only via tier-gated proxy) for a full DB-backed export.
6. **Specific compliance events to look for.** `ORG_EXTERNAL_SHARING_CHANGED` (V198 flag toggles), `MEMBER_ROLE_CHANGED`, `BILLING_SUBSCRIPTION_UPDATED`, `RUN_ARCHIVED`, `KEY_SOURCE_CHANGED`, `TIER_LIFECYCLE_CHANGED` (SUPER_ADMIN-driven).

## Journey 8 — Toggle external sharing for the org (V198)

Goal: ORG_ADMIN enables cross-org collaborators (e.g. a contractor with a gmail address) on a single project.

1. **Open org settings.** UI: `/integrations` → Org Settings card → "External Sharing" toggle.
2. **Enable.** `PUT /api/org/me/settings/external-sharing {enabled: true}` (`OrgController`). Requires `ORG_ADMIN` of own org or `SUPER_ADMIN`. Writes `org.external_sharing_enabled=TRUE` (V198).
3. **Audit row.** `ActivityAudit` row written with `eventType=ORG_EXTERNAL_SHARING_CHANGED` so the toggle is reviewable in the audit log.
4. **Invite shows up.** On `/projects/:projectId/members`, the "Invite" popover now shows the external-email input field (previously hidden). Tier check still applies: PRO+ AND ORG_ADMIN-of-own-org (or SUPER_ADMIN) AND `org.externalSharingEnabled=true`.
5. **Send invite.** `POST /api/projects/{id}/invites {email, projectRole}` writes `project_invite` (V175) with unique token. Recipient flow matches Journey 5.
6. **Revoke if needed.** UI shows pending external invites with "Revoke" action → `DELETE /api/projects/{id}/invites/{inviteId}`.

## Journey 9 — Request GDPR data export

Goal: any user requests an export of all data tied to their account (GDPR right of access).

1. **Open account page.** UI: `/account` (`AccountPage.tsx`).
2. **Export.** "Export my data" button calls `GET /api/auth/me/data-export` (`auth/dsar/DsarController` + `UserDataExportService`). Returns a ZIP with manifest + payload + README.
3. **Contents.** `manifest.json` lists every dataset included; `payload/` contains JSON files for `app_user`, `org` (if user owns the org), `org_member`, `project` (for project memberships), `pipeline_run` (the user's runs), `agent_task`, `validation_report`, `notification`, `activity_audit` (filtered to actor=userId), `billing_subscription`, `payment_history`. `README.md` documents the format.
4. **Download.** Browser saves `executespec-export-<date>.zip`.

Available on all tiers (GDPR-mandatory).

## Journey 10 — Billing admin reviews invoices

Goal: a BILLING_ADMIN downloads invoices for the org's accounting books.

1. **Open billing.** UI: `/billing` (`BillingPage.tsx`). Visible to ORG_ADMIN + BILLING_ADMIN.
2. **Payment history.** Calls `GET /api/billing/payment-history?orgId=` (`billing/controller/PaymentAuditController`). Lists payments / refunds with gateway, amount, currency, status, paid_at.
3. **Invoices.** `GET /api/billing/invoices?orgId=` (`InvoiceController`) lists `invoice` rows. Click an invoice → `GET /api/billing/invoices/{id}/download` returns the PDF.
4. **Top-up history.** `GET /api/billing/topup/history` lists every `run_topup_purchase` row (consumed and unconsumed) so the BILLING_ADMIN can reconcile credit purchases.
5. **Payment audit (SUPER_ADMIN only).** For deeper drill-down: `/admin` → "Payment audit" row → `GET /api/admin/payment-audit?orgId=`. Full ledger including webhook events. CSV export via `GET /api/admin/payment-audit/export`.
</content>
</invoke>