# System-wide user journeys

These are journeys that aren't scoped to a single project — they cover signup, billing changes, key management, org membership, compliance, and account-level admin. Each journey is a numbered click-path with endpoints cited in `METHOD /path` form linked back to source. UI components are cited under `frontend/src/signal/pages/`.

Use `docs/project-user-journeys.md` for project-scoped flows (repo onboarding, spec composition, autofix surfaces).

---

## Journey 1: Signup to first verified run

Goal: a new user lands on the marketing site, signs up, verifies their email, and submits their first run.

Tier: arrives as FREE by default. Subscription auto-created (`AuthService.createFreeSubscription`); personal org auto-bootstrapped on register (V162 `Org.is_personal=true`).

```
Marketing CTA / signup form  POST /api/auth/register
                  │
                  ▼
Email link        GET  /api/auth/verify-email?token=…
                  │
                  ▼ (F-29 single-step session)
Welcome page     /welcome      (sandbox picker + GitHub-app install CTA)
                  │
                  ▼
Compose          POST /api/runs
                  │
                  ▼
Plan gate → Approve → Live → Result
```

### Steps

1. The user lands on a marketing CTA and clicks **Sign up**. The frontend route is `/register` or `/signup` (`SignupPage` in `frontend/src/signal/pages/AuthPages.tsx`). They fill `firstName`, `lastName`, `email`, `password` and submit.

2. The frontend POSTs `/api/auth/register` (`AuthController.register` in `src/main/java/com/navneet/executespec/auth/controller/AuthController.java`). The body uses `RegisterRequest`:
   - `email`: RFC-5321-lite regex + `@Size(max=254)`,
   - `firstName` / `lastName`: 1-100, `<>` rejected,
   - `password`: 10-128, full complexity validated server-side in `AuthService.validatePasswordStrength` (mixed case, digit, special, no whitespace),
   - Wave 2.1 optional `signupSource`, `utmSource`, `utmCampaign`.

3. `AuthService.register` (`@Transactional`) runs:
   - persists `AppUser` with `status="PENDING_VERIFICATION"`, `tier="FREE"`, `globalRole="USER"`, `lifetime_run_credit_balance=5` (R-161),
   - calls `createFreeSubscription` so `billing_subscription.status="FREE"` exists from day one,
   - calls `OrgService.bootstrapPersonalOrg(userId)` to create the personal org (V162: `org.is_personal=true`),
   - tries `tryBindReferral` (R-162) using header / cookie context,
   - sends a verification email via `MailService.sendVerificationEmail` (Thymeleaf template under `email/`, async),
   - fires `sendNewUserAlertToAdmin`,
   - emits an activation analytics event.

4. The user opens the verification email and clicks the link → `GET /api/auth/verify-email?token=...` (`AuthController.verifyEmail`). The token is consumed via `email_token` table (`EmailToken` entity, `type="EMAIL_VERIFICATION"`). F-29: instead of redirecting to login, `verifyEmailAndAuthenticate` is called — it flips `email_verified=true`, issues a fresh JWT pair, and returns a `LoginResponse` so the user is signed in without re-entering creds.

5. The OAuth callback handler (`AuthCallbackPage` in `frontend/src/signal/pages/AuthCallbackPage.tsx`) stores `access_token` in `localStorage` and explicitly strips any legacy `refresh_token` (the refresh is HttpOnly cookie-authoritative). The user is bounced to `/welcome` (`WelcomePage`).

6. On `/welcome` the user sees:
   - three sandbox spec presets (REST API / React component / Python CLI), which seed `sessionStorage('signal:pending-spec', ...)`,
   - a GitHub-app install CTA via `GET /api/integrations/github/app/install-url`.

7. The user picks the REST API preset (no GitHub repo needed for FREE tier). The page navigates to `/compose`. `ComposePage` reads `sessionStorage`, pre-fills the textarea, and shows the tier-clamped cost cap input (FREE → $3).

8. The user clicks **Submit**. The frontend POSTs `/api/runs` (`RunController.submit`). For FREE users the `RunRequest` is intentionally minimal — no `projectId`, no `selectedRepoIds`, no `figmaUrl`. The controller runs `InputSanitizer`, calls `TierEnforcementService.assertAndIncrementRunCount(userId)` (pessimistic lock against `lifetime_run_credit_balance`), and dispatches `RunOrchestrator.submitRun`.

9. The pipeline runs as Journey 2 of the project doc — classifier → planner → plan gate. FREE-tier specs must be SINGLE_SHOT or SMALL complexity (`TierEnforcementService.assertComplexityAllowed`); HIGH-complexity plans 403 at approve-time.

10. The user approves the plan via `POST /api/runs/{id}/approve`. The credits-confirmed check uses the FREE-tier `lifetime_run_credit_balance` — the user's balance drops by 1 (or N, if a multi-credit spec).

11. The user watches the live page and lands on the result page. PR creation is optional for FREE — the workspace ZIP is downloadable via `GET /api/runs/{id}/download` and the in-browser preview runs for 5 minutes.

### Anomalies

- `AuthController` exposes `GET /api/auth/generate?password=...` returning a bcrypt encoding of the supplied plaintext. This is a dev helper but is not gated by SUPER_ADMIN — any authenticated user can use the server's password encoder as an oracle. Worth flagging.
- `setRefreshCookie` sets `cookie.setSecure(false)` with a comment "set true in production (HTTPS)" — relies on infra HTTPS termination rather than enforcing the secure flag.
- `JwtUtil` defaults `app.jwt.access-token-expiry-minutes` to 15 even though the project doc references 1 h — the deployed value in `application.yml` should be confirmed.

---

## Journey 2: FREE to TEAM upgrade

Goal: a FREE user has hit the 15/month cap (V200; was 5) and wants to upgrade to TEAM (BYOK, $39/seat/month, min 2 seats).

```
Plans page    GET /api/billing/plans
        │
        ▼
Subscribe     POST /api/billing/subscribe   body { planId, gateway, seats, successUrl, cancelUrl }
        │
        ├── Razorpay → Razorpay Checkout (client-side)
        │       └── verify via POST /api/billing/verify
        │
        └── Stripe → Stripe Checkout (hosted) → return to successUrl
                └── webhook POST /api/webhooks/stripe sets ACTIVE
        │
        ▼
Tier upgraded on user.tier + org.tier (BUG-009 dual-write)
GET /api/billing/status confirms
```

### Steps

1. The user clicks **Upgrade** anywhere there's a CTA, landing on `/plans` (`PlansPage` in `frontend/src/signal/pages/account/PlansPage.tsx`). The page calls `GET /api/billing/plans` (`BillingController.listPlans` in `src/main/java/com/navneet/executespec/billing/controller/BillingController.java`). The service filters by `billing_plan.lifecycle_state=LISTED` AND `tier_config.lifecycle_state=LISTED` (v2.6.0 — both must be LISTED for public surfaces; SUPER_ADMIN can pass `includeHidden=true`).

2. The page renders the 3-tier comparison (FREE / TEAM / ENTERPRISE) with a monthly / yearly toggle (yearly saves 20%, computed via `BillingPlan.annual_discount_pct`). PRO is hidden but shows a grandfathered banner for users currently on PRO. The user picks TEAM yearly.

3. The user clicks **Subscribe**. The frontend POSTs `/api/billing/subscribe` with `CreateSubscriptionRequest{planId, gateway, successUrl, cancelUrl, seats}`. TEAM seats are clamped 2-20 (`OrgService.TEAM_MIN_SEATS=2`, `TEAM_MAX_SEATS=20`).

4. `BillingService.subscribe` (`src/main/java/com/navneet/executespec/billing/service/BillingService.java`, 1440 lines, `@Transactional` at class level):
   - validates seat count for TEAM,
   - blocks lifecycle-hidden plans,
   - detects an existing subscription → cancellation path with **prorated refund** (`issueProratedRefund` computes the lower of runs-based and time-based refund, calls `RazorpayGateway.refundPartial`, generates a credit note via `InvoiceService.generateCreditNote`),
   - checks founder-pricing capacity via `FounderPricingService.tryAllocate` (pessimistic-lock row, increment counter or grant lifetime re-allocate),
   - dispatches via `PaymentGatewayRouter.resolve(entity)` to either Stripe or Razorpay.

5. **Razorpay path** (`gateway="RAZORPAY"`):
   - `RazorpayGateway.createSubscription` calls `RazorpayRestClient` to create the customer + subscription, returns `SubscriptionInitResult{gatewaySubscriptionId, gatewayCustomerId, shortUrl, razorpayKeyId, gateway="RAZORPAY"}`,
   - The frontend opens Razorpay Checkout (client-side SDK) with `razorpayKeyId`,
   - On payment success the user's browser receives a triple `{razorpaySubscriptionId, razorpayPaymentId, razorpaySignature}`,
   - The frontend POSTs `/api/billing/verify` (`BillingController.verifyAndActivate`),
   - `WebhookSignatureVerifier.verifyPaymentSignature` HMAC-checks the triple (using `key_secret`, `payment_id|subscription_id`),
   - On verify-pass `BillingService.verifyAndActivate` activates the subscription, calls `upgradeTier` which writes BOTH `user.tier=TEAM` AND `org.tier=TEAM` (BUG-009 dual-write fix, backfilled by V199), generates an invoice via `InvoiceService.generateSubscriptionInvoice` (per-FY locked-sequence invoice number `ES-{fy}-{seq}`), and triggers `InvoiceEmailService.sendInvoice` with the PDF attachment.

6. **Stripe path** (`gateway="STRIPE"`):
   - `StripePaymentGateway.createCheckoutSession` builds a seat-aware Checkout Session with `setPrice(priceId).setQuantity((long)seats)`, returns `{checkoutUrl, sessionId}`,
   - The user is redirected to Stripe Checkout (hosted), pays, returns to `successUrl`,
   - Webhook `POST /api/webhooks/stripe` (`BillingWebhookController` in `src/main/java/com/navneet/executespec/billing/controller/BillingWebhookController.java`) is signature-verified, deduped via `billing_webhook_event(gateway, eventId)`, then routed through `BillingService.processStripeWebhook`,
   - On `checkout.session.completed`, the service activates the subscription, syncs `org.seat_count` from the Stripe Subscription line item via `fetchStripeSubscriptionSeatCount`, and upgrades the tier on both user and org. On `invoice.paid` it extends the period and generates a Stripe-hosted invoice (`gateway="stripe"`, `gateway_pdf_url` populated from `getInvoicePdfUrl`).

7. The user lands back on the dashboard. The frontend calls `GET /api/billing/status` (`BillingService.getStatus`) which returns a `BillingStatusResponse` with `subscription`, `plan`, `usage[]` (RUNS metric with bonus credits added), `bonusRuns`, and `spentUsd` (current-period LLM cost from `pipeline_run` + `execution_run`).

8. Tier is now TEAM. The user sees TEAM-only features in the nav: Org settings → External sharing toggle (V198), Audit log (`/audit`), Org members (`/org-members`), API keys / BYOK card.

### Anomalies

- `BillingPlan.prePersist` runs `if (!active) active = true;` — caller-built `active=false` plans silently flip to `true` on first save. Worth flagging if you ever try to create an inactive plan.
- `BillingSubscriptionRepository.findActiveByUserId` returns at most one but doesn't enforce uniqueness (no LIMIT 1). If a user accidentally has two non-cancelled subs, JPA throws `NonUniqueResultException` — see the bulk variant `findAllActiveOrPastDue` for the safer pattern.
- `RazorpayPaymentGateway.createCheckoutSession` returns null + logs WARN — Razorpay uses client-side SDK, not server-side checkout. The router branches on `gateway.gatewayType()` so this NPE is dodged in practice.

---

## Journey 3: BYOK setup (bring-your-own-keys)

Goal: a TEAM-tier user wants to provide their own LLM API keys so runs don't hit the platform's monthly cost cap.

Tier: BYOK applies only to PRO+ (the `canManageAiKeys` permission flag is PRO+; FREE users get a disabled UI with an Upgrade CTA).

```
API Keys page          /api-keys
        │
        ▼ (validate)
POST /api/auth/settings/ai-keys/validate    body { provider, key }
        │
        ▼ (save)
PUT  /api/auth/settings/ai-keys             body { openaiKey, anthropicKey, geminiKey }
        │
        ▼ (org-level — optional)
PUT  /api/org/me/settings/key-source        body { keySourceMode: "OWN_KEYS", enabledProviders }
        │
        ▼
Subsequent runs use the keys; BYOK orgs (keySourceMode=OWN_KEYS) skip the platform cap
```

### Steps

1. The user clicks **Account** → **API keys** in the nav. `/api-keys` renders `ApiKeysPage` (`frontend/src/signal/pages/account/MiscAccountPages.tsx`). The page reads `useAuthStore(selectNormalizedTier)` and `useAuthStore((s) => s.permissions)`. Gating: `canManage = tier !== 'FREE'`. On FREE the UI is disabled with an Upgrade CTA card. (Note from research: the `canManageAiKeys` permission flag is computed PRO+ but the page bypasses it in favour of the `tier !== 'FREE'` literal — RJ-01 quirk.)

2. The user pastes their Anthropic key into the field and clicks **Validate**. The frontend POSTs `/api/auth/settings/ai-keys/validate` (`AuthController.validateAiKey`) body `{provider: "anthropic", key: "sk-ant-..."}`. The service makes a real provider call to verify the key works, returns `ValidationResult{valid: true/false, message: ...}`.

3. After validation passes, the user clicks **Save**. The frontend PUTs `/api/auth/settings/ai-keys` (`AuthController.updateAiKeySettings`) body `{openaiKey, anthropicKey, geminiKey}`. The service writes encrypted values to `app_user.{openai_api_key, anthropic_api_key, gemini_api_key}` via `@Convert(EncryptedValueConverter)`. The encryption is AES-256-GCM with the key derived as SHA-256 of `app.jwt.secret` (`KeyEncryptor`).

4. The user's `UserDto` now exposes booleans (`hasOpenAiKey`, `hasAnthropicKey`, `hasGeminiKey`) instead of the raw secrets. The dashboard shows BYOK is active on the user level.

5. **Optional org-level BYOK**: an ORG_ADMIN can flip the whole org to BYOK so every member uses the org's keys instead of the platform's. They go to `/org/settings` (`OrgSettingsPage`) and PUT `/api/org/me/settings/key-source` (`OrgController`) body `{keySourceMode: "OWN_KEYS", enabledProviders: ["anthropic"]}`. Valid modes: `SYSTEM` (platform pays), `HYBRID` (default — fall back to platform when org key missing), `OWN_KEYS` (BYOK strict, no platform fallback).

6. The ORG_ADMIN can also save org-level keys via `PUT /api/org/me/settings/ai-keys` body `{anthropicKey, openAiKey, geminiKey}`. These persist to `org.{anthropic_api_key, openai_api_key, gemini_api_key}` (also AES-256-GCM encrypted), and `org.api_keys_provided=true` if at least one is set.

7. The next time the user submits a run, `ApiKeyResolver` walks the precedence chain: user-level key → org-level key → platform key (only if `keySourceMode != OWN_KEYS`). BYOK orgs (`keySourceMode=OWN_KEYS`) skip the platform cost cap entirely; HYBRID and SYSTEM stay capped (`TierEnforcementService.requiresCostCap` returns false only when `keySourceMode=OWN_KEYS`).

### Anomalies

- The `canManageAiKeys` permission flag in `TierPermissionService` is computed correctly but ignored by the page — research flags this as an RJ-01 quirk.
- `Org` has parallel gateway fields (`payment_gateway` uppercase enum + `gateway` lowercase string default "razorpay") which can drift. Research flags this as an artefact of Stripe being added on top of Razorpay-only entities.
- The JWT-secret-derived encryption key means a JWT secret rotation makes all encrypted Redis entries unreadable → BYOK falls back to SYSTEM gracefully but keys must be re-entered.

---

## Journey 4: Invite a teammate

Goal: an ORG_ADMIN invites another developer to their TEAM-tier org.

```
Org members page              /org-members
        │
        ▼
POST /api/org/invite          body { email, orgRole: "DEVELOPER" }
        │
        ▼ (domain match + seat cap + common-domain blocklist)
        │
        ├── domain matches enforced policy → PENDING
        ├── domain doesn't match → ESCALATED   (admin@executespec.ai)
        └── over seat cap        → BillingException (402 PAYMENT_REQUIRED)
        │
        ▼
Invitation email   sendOrgInviteEmail   (Thymeleaf, token-bearing URL)
        │
        ▼
Invitee accepts    POST /api/org/accept-invite?token=…
```

### Steps

1. The ORG_ADMIN clicks **Account** → **Org members**. `/org-members` renders `OrgMembersPage` (`frontend/src/signal/pages/account/MiscAccountPages.tsx`). The page reads `useAuthStore((s) => s.user)`; the invite form is gated `canInvite = orgRole==='ORG_ADMIN' || globalRole==='SUPER_ADMIN'`.

2. The page loads `GET /api/org/me` (`OrgController`, returns `OrgDto`), `GET /api/org/me/members` (`OrgMemberDto` list), `GET /api/org/me/invitations` (pending `OrgInvitation` list).

3. The admin types an email and picks a role (DEVELOPER / BILLING_ADMIN / ORG_ADMIN), clicks **Invite**. The frontend POSTs `/api/org/invite` (`OrgController.invite`) body `InviteRequest{email, orgRole, projectIds?}`.

4. `InvitationService.createInvitation` (`src/main/java/com/navneet/executespec/auth/service/InvitationService.java`, 595 lines):
   - Checks common-domain blocklist (`BLOCKED_DOMAINS` — 50+ consumer email providers like gmail.com, yahoo.com, etc. — invite rejected if `org.block_common_domains=true`),
   - Counts current org members against the org's tier limit (TEAM uses `seat_count` from active billing subscription's `total_seats`, falls back to `org.seat_count`, falls back to 2),
   - Checks `org.domain_enforcement_mode`:
     - `OPEN` → any email allowed → status `PENDING`,
     - `ENFORCED` + email's domain in `domain_patterns` → `PENDING`,
     - `ENFORCED` + domain mismatch → `ESCALATED` (admin@executespec.ai gets a notification email; SUPER_ADMIN must approve/reject via `/api/admin/invitations/escalated`).
   - Generates a UUID token, persists `org_invitation` row with `expires_at = now + 7d`,
   - Sends `sendOrgInviteEmail` via `MailService` (Thymeleaf template with token-bearing accept URL).

5. The admin sees the invite in the pending list. They can resend via `POST /api/org/me/invitations/{id}/resend` (re-activates EXPIRED rows) or cancel — though research notes `DELETE /api/org/me/invitations/{id}` (`OrgController.cancelInvitation`) is a no-op (computes `findFirst()` then returns 204 without persisting the cancel). Worth fixing.

6. The invitee receives the email and clicks the accept link.

### Continuation: invitee accepts

7. The invitee clicks the email link → frontend route `/accept-invite?token=...` (`AcceptInvitePage` in `frontend/src/signal/pages/ExtraPages.tsx`).

8. If they're not logged in, the page redirects them through register/login first.

9. Once authenticated, the page POSTs `/api/org/accept-invite?token=...` (`OrgController.acceptInvite`). `InvitationService.acceptInvitation` runs:
   - validates token exists, status PENDING, not expired (auto-flips PENDING+expired → EXPIRED),
   - idempotent on already-accepted (returns success without double-add),
   - checks `current_user.email` matches `invitation.invited_email` (case-insensitive),
   - creates `org_member` row with the offered role,
   - inherits org tier (sets `user.tier = org.tier`),
   - if `project_ids` is set, auto-assigns the user to each project via `project_member` (PROJECT_DEVELOPER role).

10. The invitee is bounced to `/home` and sees the org's projects in `Projects`.

### Anomalies

- `OrgController.cancelInvitation` is a no-op (research finding) — invitations cancel only by expiry.
- TEAM seat-cap drift: research notes `resolveTeamSeatLimit` walks `billing_subscription.totalSeats` → `org.seat_count` → fallback 2. Mismatches between Stripe's seat count and `org.seat_count` would surface here.
- `OrgController.PUT /api/org/me/members/{userId}/role` is admin-only; cannot self-suspend; the last ORG_ADMIN cannot be removed (`OrgService.removeMember` guards).

---

## Journey 5: Accept an invite as a new user

Goal: a developer receives an org invite email but doesn't have an ExecuteSpec account yet. They sign up, then accept.

```
Email link → /accept-invite?token=…  (not logged in)
        │
        ▼ (Register / Login)
POST /api/auth/register                 (creates account, personal org auto-bootstrapped)
        │
        ▼ (verify email)
GET  /api/auth/verify-email?token=…     (single-step session, F-29)
        │
        ▼ (now authenticated)
POST /api/org/accept-invite?token=…
```

### Steps

1. The user clicks the invite link → `/accept-invite?token=...` (`AcceptInvitePage`). The page sees no JWT in `localStorage` and renders a "Sign up to accept" CTA (preserving the `token` query param).

2. The user clicks through to `/register` with the invite token stashed in `sessionStorage`. They register as in Journey 1 (`POST /api/auth/register`). Note: registration creates their personal org (V162). The invitation token is held until verify-email + accept-invite.

3. They verify their email via `GET /api/auth/verify-email?token=...` → F-29 single-step session.

4. After verify, the frontend reads the stashed invitation token from `sessionStorage` and POSTs `/api/org/accept-invite?token=...`. `InvitationService.acceptInvitation` performs the email-match check (the invited email MUST match the registered email, case-insensitive) and then:
   - creates the `org_member` row for the **new org** (so the user now has membership in both their personal org AND the inviting org),
   - inherits the inviting org's tier,
   - auto-assigns to any pre-specified projects.

5. The user lands on `/home`. The default org context is the inviting org (the more recent membership). The personal org persists in the background and can be revisited via the org switcher.

### Engineering-honest note

The research notes describe `findOrCreateFromOAuth` (`AuthService.findOrCreateFromOAuth`) as the OAuth path with personal-org bootstrap + referral binding. The email-invite path goes through `register` first, then `acceptInvitation` — they are two separate transactional flows, not one. If the user's personal org auto-create fails for any reason during register, they'd end up with no org context and the invite-accept would attach them only to the inviting org.

---

## Journey 6: Configure SSO (GOOGLE_OIDC, with SAML constraints)

Goal: an ORG_ADMIN wants to enforce SSO for their org's domain.

Tier: ENTERPRISE-feature in practice (SSO config endpoint is `SUPER_ADMIN | ORG_ADMIN`-only). No tier flag on the controller itself; gating is by role.

Engineering-honest note: only **GOOGLE_OIDC** is fully wired in this build. **OKTA_SAML** and **AZURE_AD_SAML** can be saved to the schema but the SAML handshake is roadmap; `SsoConfigController` refuses to set `enforced=true` on SAML providers (returns 409). It also blocks the admin from enforcing SSO before they've done at least one SSO login themselves (M-016 lockout-prevention).

```
Org settings page    /org/settings
        │
        ▼
GET  /api/auth/sso/config              (current config)
        │
        ▼
PUT  /api/auth/sso/config              body { provider, metadataUrl, domain, enforced }
        │
        ▼ guards:
        │   M-016: cannot enforce before admin's own SSO login
        │   J-14:  cannot enforce on SAML providers (409)
        │
        ▼
SSO config saved (provider, metadataUrl, domain, enforced)
```

### Steps

1. The ORG_ADMIN goes to `/org/settings` → SSO section (`OrgSettingsPage` in `frontend/src/signal/pages/LegalPages.tsx`). The page loads `GET /api/org/me` and `GET /api/auth/sso/config` (`SsoConfigController` in `src/main/java/com/navneet/executespec/auth/sso/SsoConfigController.java`).

2. The admin picks **Google OIDC**, types their org's email domain (e.g. `example.com`), and clicks **Save**.

3. The frontend PUTs `/api/auth/sso/config` (`SsoConfigController.update`) body `SsoConfigUpdate{provider: "GOOGLE_OIDC", enforced: false, metadataUrl: "...", domain: "example.com"}`. Validation:
   - `provider` matches `GOOGLE_OIDC|OKTA_SAML|AZURE_AD_SAML`,
   - `metadataUrl` matches `^$|^https://.+` (empty for partial updates),
   - `domain` is `@Size`-bounded.

4. The service persists to `org.{sso_provider, sso_enforced, sso_metadata_url, sso_domain}`.

5. The admin tests SSO by logging out and visiting `/login` → clicking **Sign in with Google**. The frontend hits `GET /api/auth/oauth2/authorize/google` (`AuthCallbackPage` handles the callback). `OAuthSuccessHandler` (`src/main/java/com/navneet/executespec/auth/oauth/OAuthSuccessHandler.java`) maps the profile through `OAuthUserMapper`, finds-or-creates the user via `AuthService.findOrCreateFromOAuth`, mints JWTs, redirects to `${app.mail.base-url}/auth/callback`.

6. After verifying SSO works, the admin returns to `/org/settings` and PUTs again with `enforced: true`. The endpoint runs the M-016 check (caller's last login provider must be Google for the org's domain) before allowing enforce.

7. From then on, any user from `domain=example.com` who tries to log in with email+password is rejected with a "Use SSO to sign in" message. The OAuth flow auto-creates their account on first SSO login (`findOrCreateFromOAuth` — `@Transactional`: email-merge if account exists with same email, else create fresh; auto-bootstraps personal org via `bootstrapPersonalOrg` for new accounts).

### SAML constraints (roadmap)

- `OKTA_SAML` / `AZURE_AD_SAML` providers can be **saved** to the schema, but the controller refuses to set `enforced=true` on them (returns 409 with the message "SAML enforcement is roadmap; only GOOGLE_OIDC supports enforcement today").
- There's no SAML handshake implementation in this build — `OAuthSuccessHandler` only handles Google + GitHub OAuth2 (`@ConditionalOnExpression` gates on the relevant client IDs).
- For SAML you'd configure metadata URL + domain for documentation purposes, but real auth still flows through Google OIDC.

### Anomalies

- `OAuthSuccessHandler` propagates JWTs through URL query params (browser history retention concern, common pattern but worth noting). The refresh token is also sent as a `refresh=` query param then immediately stripped client-side and replaced with the HttpOnly cookie.

---

## Journey 7: Audit log review

Goal: a TEAM-tier ORG_ADMIN reviews who did what in their org over the past month.

Tier: TEAM+ feature. FREE / PRO users see a 403 message on `/audit` with the tailored upgrade message (`AuditLogPage` in `frontend/src/signal/pages/ExtraPages.tsx`):
- FREE / PRO → "TEAM+ feature, Upgrade your plan",
- TEAM+ non-org-admin → "Ask your org admin",
- TEAM+ ORG_ADMIN → "Enable in Org Settings → Compliance".

```
Audit page    /audit
        │
        ▼
GET /api/admin/audit-logs           (SUPER_ADMIN)
GET /api/org/me/audit-logs          (ORG_ADMIN)
        │
        ▼ filter by entityType / eventType / actor / time
        │
        ▼ Export CSV in-place (Blob URL)
```

### Steps

1. The ORG_ADMIN clicks **Audit** in More. `/audit` → `AuditLogPage`. The page reads `useAuthStore(selectNormalizedTier)` and `useAuthStore((s) => s.user)`. Based on tier + role it tailors the empty-state copy.

2. For TEAM+ ORG_ADMIN, the page calls `GET /api/org/me/audit-logs` (`OrgController.listAuditLogs`) with filters `entityType`, `eventType`, `actorId`, `entityId`, `from`, `to`, `page`, `size`. The service walks the hash-chained `activity_audit` table (`ActivityAuditService.query`, page size capped at 100).

3. The user filters by `eventType=PLAN_APPROVED` and a time window. The page renders rows with timestamp, actor, entity type, event type, old → new value, metadata. Each row's `prev_hash` and `row_hash` are SHA-256 chain links (M-019/V147).

4. The user clicks **Export CSV**. The page builds a Blob URL client-side from the filtered rows and triggers a download (no `/api/admin/audit-logs/export` endpoint for org-level — SUPER_ADMIN has the full export at `GET /api/admin/payment-audit/export`).

5. (SUPER_ADMIN only) a separate endpoint `GET /api/admin/audit-chain/verify` (`SuperAdminController`) walks every chained row and returns `ChainVerifyReport{totalChained, verified, clean, firstBreak?}` for tamper-evidence checks.

### Anomalies

- `ActivityAuditService.record(ActivityAudit entry)` is `synchronized` (JVM-wide) to keep the hash chain consistent. Single-JVM only — research notes the chain seq comes from a Postgres sequence which gives cross-instance ordering, but the chain hash computation is per-JVM serialised. Worth flagging for multi-MIG-instance deploys.

---

## Journey 8: V198 external-sharing toggle

Goal: an ORG_ADMIN turns on cross-org project sharing so they can invite external collaborators (e.g. an agency, a contractor) to a specific project.

Tier: PRO+ (the underlying cross-org `ProjectMember` invite path is tier-gated). Plus V198 adds an org-level kill-switch.

```
Org settings page         /org/settings
        │
        ▼
PUT /api/org/me/settings/external-sharing   body { enabled: true }
        │   (ORG_ADMIN-of-own-org or SUPER_ADMIN only)
        │   emits ORG_EXTERNAL_SHARING_CHANGED audit row
        ▼
org.external_sharing_enabled = true
        │
        ▼ now the invite UI on project pages unlocks
        │
        ▼
Project members page      /projects/:projectId/members
        │
        ▼
POST /api/projects/{id}/invites        body { email, role: "PROJECT_VIEWER" }
        │   (PRO+, ORG_ADMIN-of-own-org or SUPER_ADMIN, AND org.external_sharing_enabled=true)
        │
        ▼
External user receives email, accepts → cross-org project_member row created
```

### Steps

1. The ORG_ADMIN goes to `/org/settings` (`OrgSettingsPage`) and finds the **External sharing** section. They click the toggle.

2. The frontend PUTs `/api/org/me/settings/external-sharing` (`OrgController.updateExternalSharing`) body `{enabled: true}`. The endpoint requires `ORG_ADMIN` of caller's own org OR `SUPER_ADMIN`. `OrgService.updateExternalSharing`:
   - sets `org.external_sharing_enabled=true`,
   - emits `ORG_EXTERNAL_SHARING_CHANGED` audit row via `ActivityAuditService.record` (entity=ORG, oldValue=false, newValue=true, actor=caller).

3. The admin goes to a project → **Members** tab (`ProjectMembersPage` in `frontend/src/signal/pages/project/ProjectMembersPage.tsx`). The page calls `GET /api/org/me` to read the V198 flag. Until V198 is on, the invite UI shows an "External sharing is off — Enable in Org settings" card.

4. With V198 enabled, the invite form unlocks. The admin types the external person's email and picks a role (PROJECT_VIEWER read-only / PROJECT_DEVELOPER / PROJECT_ADMIN).

5. The frontend POSTs `/api/projects/{id}/invites` (`ProjectMembersPage` uses raw `fetch` — research flags this as a missing typed wrapper in `frontend/src/lib/api.ts`). The endpoint applies three V198 gates:
   - caller's tier ≥ PRO,
   - caller is ORG_ADMIN of their own org OR SUPER_ADMIN (not just PROJECT_ADMIN — V198 narrowed this),
   - `org.external_sharing_enabled=true`.

6. On success a `project_invite` row is persisted with `is_external=true`. The invitee receives `MailService.sendProjectInviteEmail` with the token-bearing accept URL.

7. The external user clicks the link → `/accept-invite?token=...` (`AcceptInvitePage`). The flow is the same as Journey 4 step 7-10, except this creates a `project_member` row (cross-org) instead of an `org_member` row. The external user keeps their existing org / personal-org membership; they just gain read/write/admin access to **this** project.

8. The external user now sees the project in their **Shared with me** tab on `/projects` (`ProjectsPage`). Their org context is unchanged; the project shows up via `project_member` membership rather than `org_member`.

### V198 narrowing detail

The research notes (per `bucket-small-A-1-research.md` is not the V198 source; the V198 details are in the architecture summary and `ProjectMembersPage.tsx` notes):

- Before V198: PROJECT_ADMIN of a project could invite external collaborators directly.
- After V198: PROJECT_ADMIN is no longer enough — the path is now gated by ORG_ADMIN-of-own-org (or SUPER_ADMIN) AND `org.external_sharing_enabled=true`. This narrows the surface significantly.
- Public read-only share links (`POST /api/projects/{id}/share-links`) are **independent** of V198 and remain available subject to their own tier gates.

---

## Journey 9: GDPR data export

Goal: a user requests an export of all their personal data under GDPR Article 15 + 20.

Tier: any tier. Available to any authenticated user for their own data; SUPER_ADMIN can export any user's data via DSAR endpoints.

```
Account page          /account
        │
        ▼ click "Export my data"
GET /api/auth/me/data-export          (returns application/zip)
        │
        ▼
ZIP downloaded: { manifest.json, payload.json, README.md }
```

### Steps

1. The user goes to `/account` (`AccountPage` in `frontend/src/signal/pages/account/AccountPage.tsx`). They scroll to the **Security & data** section and click **Export my data**.

2. The page does a raw `fetch('/api/auth/me/data-export', {method: 'GET'})` (research notes this is one of the few legacy uses of `window.alert` for the error path).

3. `AuthController.exportMyData` calls `UserDataExportService.exportForUser(userId)` (`src/main/java/com/navneet/executespec/auth/service/UserDataExportService.java`). The service is `@Transactional(readOnly=true)` and builds an `ExportBundle{zipBytes, suggestedFilename}`.

4. The bundle includes (capped at 200 rows each — `EXPORT_ROW_LIMIT`):
   - `redactUser(AppUser)` — drops `password_hash` and every API key, replaces them with boolean `hasXxxKey` flags,
   - orgs the user is a member of (`org` rows joined via `org_member`),
   - projects in those orgs (`project` rows),
   - the user's runs (`pipeline_run` rows),
   - the user's notifications (`notification` rows),
   - active subscription (`billing_subscription`) + last 200 invoices (`invoice`).

5. The service writes three entries to the ZIP:
   - `manifest.json` — schema version 2, generated_at, user_id, row counts per table,
   - `payload.json` — the redacted data dump,
   - `README.md` — plain-English explanation of what's in the ZIP and what each field means.

6. The endpoint returns `application/zip` with `Content-Disposition: attachment; filename="executespec-data-export-{userId}-{timestamp}.zip"`.

### Right-to-erasure (separate flow)

The user can also request deletion via `DELETE /api/auth/me/data` (`AuthController.eraseMyData`). This:
- soft-deletes the user (`status="DELETED"`, `deleted_at=now()`),
- emits an audit row,
- triggers an async retention sweep.

The active session is invalidated via the per-user JWT epoch (`token_invalidated_at`).

### DSAR (SUPER_ADMIN path)

`DsarController` (`src/main/java/com/navneet/executespec/auth/dsar/DsarController.java`) exposes:
- `GET /api/dsar/me` — JSON dump for self (no ZIP, lighter than UserDataExportService),
- `GET /api/dsar/users/{userId}` — SUPER_ADMIN-only, dump for any user.

The JSON dump shape mirrors `payload.json` from the ZIP export but skips the manifest + README wrappers.

---

## Journey 10: Billing-admin invoice review

Goal: a BILLING_ADMIN reviews invoices, payment history, and webhook events for their org.

Tier: tier-agnostic; gating is by `orgRole=BILLING_ADMIN` OR `ORG_ADMIN` (or `SUPER_ADMIN`).

```
Billing page         /billing
        │
        ▼
GET /api/admin/analytics/billing?entityType=org&entityId={orgId}
GET /api/admin/analytics/usage?entityType=org&entityId={orgId}&period=30
GET /api/billing/payment-history                        (paginated)
        │
        ▼ per-invoice
GET /api/billing/invoices                               (paginated)
GET /api/billing/invoices/{id}                          (detail)
GET /api/billing/invoices/{id}/pdf                      (redirect or server-rendered)
```

### Steps

1. The BILLING_ADMIN clicks **Billing** in the nav. `/billing` → `BillingPage` (`frontend/src/signal/pages/account/BillingPage.tsx`). The page reads `useAuthStore((s) => s.user)`.

2. The page loads:
   - `GET /api/admin/analytics/billing?entityType=org&entityId={user.orgId}` → `BillingOverview{billingEntityType, currentPlan, subscriptionStatus, nextBillingDate, periodUsage, remainingRuns, runLimit, gateway, topupCredits, recentInvoices[]}` (`AdminAnalyticsController.billing` in `src/main/java/com/navneet/executespec/api/controller/AdminAnalyticsController.java`),
   - `GET /api/admin/analytics/usage?entityType=org&entityId={user.orgId}&period=30` → `UsageMetrics{totalRuns, successfulRuns, failedRuns, partialRuns, runsByDay[], runsByProject, avgTimeToCompleteSeconds, totalCost, costByModel}`,
   - raw `fetch('/api/billing/payment-history')` for the paginated `PaymentAudit` list.

3. `AdminAnalyticsController.assertAnalyticsAccess` enforces the RBAC matrix:
   - SUPER_ADMIN → any entity,
   - ORG_ADMIN → only own org,
   - BILLING_ADMIN → only own org **billing endpoints** (passes `billingOnly=true`),
   - standalone user → own data only.

4. The BILLING_ADMIN clicks an invoice in the recent-invoices section. The frontend GETs `/api/billing/invoices/{id}` (`InvoiceController.getInvoice` in `src/main/java/com/navneet/executespec/billing/controller/InvoiceController.java`) which returns `InvoiceDetail{invoice, lineItems}`. The page renders amount + GST breakdown (CGST + SGST for intra-state India invoices; IGST for inter-state; zero-rated for exports under LUT).

5. They click **Download PDF**. `GET /api/billing/invoices/{id}/pdf` returns either a redirect to the Stripe-hosted PDF (when `invoice.gateway_pdf_url` is set) OR a server-rendered PDF via `InvoicePdfService.generatePdf` (Thymeleaf template `invoice/invoice-pdf` + Flying-Saucer ITextRenderer, Indian numbering + amount-in-words).

6. For webhook event auditing, the BILLING_ADMIN can hit `GET /api/admin/webhook-events?gateway=stripe&page=0&size=20` (`PaymentAuditController.listWebhookEvents`, `SUPER_ADMIN`-only — research notes BILLING_ADMIN can hit payment-history but NOT webhook-events). They'd ask a SUPER_ADMIN to share that view if needed.

7. For payment history, `GET /api/billing/payment-history` (`PaymentAuditController.paymentHistory`, `ORG_ADMIN | BILLING_ADMIN | SUPER_ADMIN`) returns paginated `PaymentAudit` rows scoped to the org. Each row carries `gatewayProvider`, `gatewayEventId`, `currency`, `amount_minor`, idempotency_key, and full metadata jsonb.

### Anomalies

- BILLING_ADMIN can read invoices + payment audit but **cannot** read webhook events (SUPER_ADMIN-only). Worth flagging if compliance audit expects full event visibility.
- `BillingService.processStripeWebhook` writes the raw event before processing but doesn't verify the signature itself — it relies on `BillingWebhookController` having called `gatewayRouter.stripe().handleWebhook(...)` first. If `processStripeWebhook` is ever invoked directly, the auth check is skipped. Mitigated by call-site convention.
- `BillingService.processRazorpayWebhook` swallows non-SecurityException errors (by design — Razorpay doesn't retry). Combined with `processed=false` initially written to `billing_webhook_event`, failed events live silently in the table until ops notices.
- Two parallel webhook tables (`webhook_event_log` + `billing_webhook_event`) both use `(gateway, eventId)` unique constraints — research flags potential double-bookkeeping drift between them.

---

## Cross-cutting endpoints

These show up in multiple system-wide journeys:

| Purpose                                | Endpoint                                                                    | Source                                                                 |
| -------------------------------------- | --------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Permissions snapshot                   | `GET /api/auth/me/permissions`                                              | `AuthController.permissions` → `TierPermissionService.getPermissions`  |
| Me / refresh / logout                  | `GET /api/auth/me` / `POST /api/auth/refresh` / `DELETE /api/auth/logout`   | `src/main/java/com/navneet/executespec/auth/controller/AuthController.java` |
| OAuth (Google, GitHub)                 | `GET /api/auth/oauth2/authorize/{provider}` → callback                      | `OAuthSuccessHandler` + `OAuthUserMapper`                              |
| Org self                               | `GET /api/org/me` / `PUT /api/org/me`                                       | `src/main/java/com/navneet/executespec/auth/controller/OrgController.java` |
| Billing status                         | `GET /api/billing/status`                                                   | `BillingService.getStatus`                                             |
| Billing plans                          | `GET /api/billing/plans`                                                    | `BillingController.listPlans`                                          |
| Run top-ups                            | `POST /api/billing/topup/purchase` + `POST /api/billing/topup/verify`       | `RunTopupService`                                                      |
| Force-logout (per-user JWT epoch)     | `POST /api/auth/invalidate-sessions`                                        | `AuthController.invalidateSessions`                                    |
| Notifications                          | `GET /api/notifications` + `POST /{id}/read` + `POST /read-all`             | `NotificationsPage`                                                    |
| Activity / audit                       | `GET /api/admin/audit-logs` (SA) / `GET /api/org/me/audit-logs` (ORG_ADMIN) | `ActivityAuditService.query`                                           |

The tier-permission matrix is computed by `TierPermissionService.getPermissions(userId)` (`src/main/java/com/navneet/executespec/auth/service/TierPermissionService.java`) — single source of truth for "can user X do feature Y?". It branches on SUPER_ADMIN / FINANCE / org-member / personal-org (→ solo path) / solo-FREE, and reads `tier_config` for caps. The frontend pulls this once on login via `GET /api/auth/me/permissions` and stores it in `useAuthStore((s) => s.permissions)` for all UI gating.
