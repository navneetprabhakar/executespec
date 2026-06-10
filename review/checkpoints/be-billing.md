# be-billing — raw findings

Reviewed as-is on branch `benchmark` (incl. uncommitted working tree), 2026-06-10. Code + tracked config only (application.yml, application-{dev,prod,benchmark}.yml, V*.sql); no docs consulted. All paths relative to repo root. Every `.java` file in scope was read in full; dead-code claims grep-verified across `src/main`, `src/test`, and `frontend/`.

## 1. Inventory

| Package | Files | Notes |
|---|---|---|
| `billing/` | 84 .java (~9.0K LOC) | controller(6), service(15), gateway(10), entity(16), repository(15), dto(9), scheduler(3), lifecycle(3), security(1), client(1), config(1), cost(2), exception(2) |
| `credit/` | 1 | `LifetimeCreditService` (v3 lifetime credits — dormant) |
| `entitlements/` | 2 | `EntitlementResolver` + DTO |
| `referral/` | 4 | service, hook, entity, repo |
| `tenant/` | 4 | filter, 2 context helpers, JPA listener |
| tests | 6 files, 1,182 LOC | lifecycle, founder, credit, entitlements, referral ×2 |

Adjacent files read for verification (not in scope, not fully reviewed): `api/controller/AdminAnalyticsController`, `api/controller/RunController` (submit/fork/approve slices), `auth/service/TierEnforcementService`, `auth/service/TierPermissionService` (slice), `auth/service/OrgService.adjustSeats`, `auth/service/AuthService.createFreeSubscription`, `auth/security/SecurityConfig` (route rules), `integration/RunOrchestrator.persistEstimate`, `integration/RunPersistenceWriter.writeRunCostLog`, `cost/RunCostTracker` + `cost/CostHistoryService` (credit formulas), repositories `PipelineRunRepository`/`ExecutionRunRepository` (cost sums), frontend `ide/IdeBilling.tsx`, `lib/api/billing.ts`, `lib/api/misc.ts`, `lib/billing-api.ts`.

---

## 2. Findings

### 2.1 [BUG][CRITICAL] Billing page shows 0 runs / $0.00 — root cause found (3 stacked defects)

The page (`frontend/src/ide/IdeBilling.tsx:79-80`) calls `getAnalyticsBilling('org', user.orgId)` + `getAnalyticsUsage('org', user.orgId, 30)` — entityType is **lowercase `'org'`**. Same in `frontend/src/ide/IdeSettings.tsx:813-814` and `frontend/src/signal/pages/MiscPages.tsx:1095-1096`.

1. **Case-sensitive entityType dispatch.** Backend compares `"ORG".equals(entityType)` / `"USER".equals(entityType)` everywhere: `api/controller/AdminAnalyticsController.java:113,122` and `billing/service/AnalyticsService.java:56,105,142,179,269,288,313,321`. `'org'` matches neither, so it falls into the **USER** branch with an **org id** as the user id.
2. **SUPER_ADMIN path returns silently-wrong zeros.** `AdminAnalyticsController.assertAnalyticsAccess` early-returns for SUPER_ADMIN (`AdminAnalyticsController.java:109`) before the entityType validation, so the request reaches `AnalyticsService.getUsageMetrics("org", <orgId>, 30)` → USER branch SQL `WHERE created_by_user_id = ? AND org_id IS NULL` (`AnalyticsService.java:83`) with `? = the orgId` → 0 rows → `totalRuns=0, totalCost=0.0`. The reporting account is SUPER_ADMIN ⇒ **this is the exact observed "0 runs / $0.00 while 2 runs cost $0.08."** Note `IdeBilling.tsx:121` uses `usage?.totalRuns ?? permissions?.currentRunCount` — `0` is not nullish, so the fallback never fires; and even if it did, `TierPermissionService.superAdminPermissions` hardcodes `currentRunCount = 0` (`auth/service/TierPermissionService.java:137`).
3. **Non-SUPER_ADMIN users never reach the controller at all.** `SecurityConfig` locks `/api/admin/**` to `hasRole('SUPER_ADMIN')` (`auth/security/SecurityConfig.java:234-235`), which covers `/api/admin/analytics/*`. The entire in-controller RBAC for ORG_ADMIN / BILLING_ADMIN / standalone users (`AdminAnalyticsController.java:103-134`) is **unreachable dead logic**. Every normal user gets 403 → frontend `.catch(() => null)` → `totalSpend = usage?.totalCost ?? 0` (`IdeBilling.tsx:124`) → **$0.00 for everyone, always**.

Latent 4th defect: even with correct casing, the USER branch can never return data — `AND org_id IS NULL` (`AnalyticsService.java:83,121,158,195`) excludes everything because every user gets a personal org auto-provisioned (V162/V163) and runs are stamped with `org_id`. The `entityType=USER` queries are dead-by-construction.

Corroboration: the workaround comment at `IdeBilling.tsx:116-120` ("the analytics/billing endpoint reports a stale per-org plan/limit — e.g. it returns FREE/5 even for an ENTERPRISE org") is this same bug seen from the other end: `getBillingOverview("org", orgId)` falls into the USER branch, `userRepo.findById(<orgId>)` finds nothing, and the hardcoded defaults `currentPlan="FREE", runLimit=5` (`AnalyticsService.java:262-266`) are returned. The endpoint wasn't stale — it was querying the wrong branch.

Fix surface: normalize `entityType.toUpperCase()` at the controller boundary (or accept both), carve `/api/admin/analytics/**` out of the blanket SUPER_ADMIN rule (or move it off `/api/admin`), and drop `org_id IS NULL` from the USER branch (filter by `created_by_user_id` only, or by personal-org id).

### 2.2 [BUG][CRITICAL] Stripe activation chain is broken — paid customers never get their tier

- `BillingService.subscribe` persists the Stripe **checkout-session id** (`cs_…`) as `gatewaySubscriptionId`: `billing/service/BillingService.java:304-315` (`checkout.sessionId()` → `persistSubscription`).
- Every Stripe webhook handler looks up by the **subscription id** (`sub_…`) from the event payload: `BillingService.java:998-999` (checkout.session.completed), `:1034-1035` (invoice.paid), `:1080-1081` (payment_failed), `:1103-1104` (subscription.deleted).
- `setGatewaySubscriptionId` is **never called anywhere** (repo-wide grep; only the builder write at creation). Nothing ever rewrites `cs_…` → `sub_…`.

Consequences: `handleStripeCheckoutCompleted` finds no row, logs "checkout was initiated outside our flow… will be fully set up by invoice.paid" (`BillingService.java:1000-1008`) and returns; `handleStripeInvoicePaid` finds no row → warns "unknown subscription" (`:1036-1038`) → **`upgradeTier` never runs, period never set, no local invoice, no renewal handling, no dunning, no downgrade-on-delete**. The local subscription is stuck `CREATED` forever; `getStatus` shows no subscription; `cancel()` throws "No active subscription to cancel" (`:445-449`); `OrgService.adjustSeats` pushes the seat quantity to the `cs_…` id → `Subscription.retrieve("cs_…")` fails (`auth/service/OrgService.java:852-853`). Money is taken by Stripe; entitlement never arrives. Prod default gateway is stripe (`application-prod.yml:188`, `billing.stripe.enabled` default true at `:190`), so this is the live USD purchase path. (Side effects that DO work: stripe customer id stored on user/org and `org.seat_count` sync, `BillingService.java:950-990`.)

Fix surface: in `handleStripeCheckoutCompleted`, look the row up by session id (it's in `session.id`) or by `metadata.plan_id + billing_entity_id`, then overwrite `gatewaySubscriptionId` with `session.subscription`.

### 2.3 [BUG][HIGH] Credit charge/refund asymmetry — quota refund can exceed what was charged

- Charge: submit increments the quota counter by exactly **1** — `api/controller/RunController.java:153` → `TierEnforcementService.assertAndIncrementRunCount(userId)`.
- The multi-credit overload `assertAndIncrementRunCount(String, int credits)` (`auth/service/TierEnforcementService.java:137`) has **zero callers** (grep-verified). There is **no charging at plan approval** — `RunController` approve (`:369-396`) only validates `creditsConfirmed == run.creditsCharged` as a stale-approval guard.
- `creditsCharged = est.credits()` (often > 1) is stamped at planning time: `integration/RunOrchestrator.java:2419`.
- Refund: on FAILED / clean-PARTIAL, `billing/service/RunQuotaRefundListener.java:70-77` refunds **`creditsCharged`** credits via `decrementRunCount(userId, credits)` + `usageTrackingService.decrement(...)`. `decrementRunCount` clamps only at the counter's global zero (`TierEnforcementService.java:503-527`), not at what this run added.

Net effect: a failed run estimated at N credits refunds N while only 1 was charged — erasing other runs' usage from the monthly counter (e.g. used 10/15, one 5-credit run fails → counter drops to 6). The "cost-proportional credits, charged at approval" model exists only in the data model, not in enforcement. Either wire the multi-credit charge at approval or refund exactly 1.

### 2.4 [BUG][HIGH] Cross-org payment-history disclosure

`GET /api/billing/payment-history?orgId=…` (`billing/controller/PaymentAuditController.java:77-85`) requires only `hasAnyRole('ORG_ADMIN','BILLING_ADMIN','SUPER_ADMIN')` and passes the caller-supplied `orgId` straight to `auditService.findByOrg(orgId, pr)` — **no check that the caller belongs to that org**. Any org admin (every solo user is admin of their personal org) can enumerate any other org's payment audit rows (amounts, tier changes, payment ids). This endpoint is NOT under the `/api/admin/**` blanket rule.

### 2.5 [BUG][HIGH] Stripe cancel is immediate, message says period-end

`BillingService.cancel()` routes Stripe to `gatewayRouter.stripe().cancelSubscription(...)` (`billing/service/BillingService.java:457-458`), which is the **immediate** cancel (`gateway/StripePaymentGateway.java:152-162`, `subscription.cancel(prorate=true)`), while the local row gets `cancelAtPeriodEnd=true` and the user is told "Cancels at period end: …" (`:464-481`). The correct method exists (`StripePaymentGateway.cancelAtPeriodEnd`, `:227-242`) and IS used by the Razorpay branch and the lifecycle sunsetting path — just not here. A Stripe subscriber who cancels loses the rest of their paid period at the gateway.

### 2.6 [BUG][MED] Upgrade flow destroys the old subscription before the new one is paid

`BillingService.subscribe` upgrade branch (`billing/service/BillingService.java:228-267`): on selecting a higher tier it immediately (a) cancels the existing gateway subscription (`cancelImmediately` / Stripe immediate cancel), (b) issues a prorated refund (`issueProratedRefund`), (c) marks the row `UPGRADED` — **all before checkout for the new plan completes**. If the user abandons checkout: no active subscription row remains (`UPGRADED` ∉ `('FREE','ACTIVE','PAST_DUE')`, `repository/BillingSubscriptionRepository.java:18-21`), the gateway sub is gone, a partial refund went out, but `user.tier`/`org.tier` keep the old paid value indefinitely (nothing downgrades `UPGRADED` rows — `PeriodEndDowngradeScheduler` only scans `CANCELLED`). Free paid-tier access + lost renewal revenue. The cancel+refund should fire on activation of the NEW subscription, not at checkout initiation.

### 2.7 [BUG][MED] `POST /api/billing/seats` mutates seats without billing

`BillingService.updateSeats` (`billing/service/BillingService.java:503-528`) writes `totalSeats`/`extraSeats` locally with **no gateway quantity update and no upper bound** (only `>= plan.includedSeats`). Billed amount never changes; subsequent invoices DO bill `extraSeats × perSeatInr` (`InvoiceService.java:53-54`) for seats that were never gateway-authorized — or conversely seats are granted free until renewal depending on gateway. The correct, gateway-synced path exists separately (`OrgService.adjustSeats` → `updateSubscriptionQuantity`, `auth/service/OrgService.java:797-889`). No frontend caller of `/billing/seats` exists (only the orphaned `frontend/src/lib/billing-api.ts:183`, which itself has zero importers) — a dark, dangerous endpoint. Remove or delegate to the OrgService path.

### 2.8 [BUG][MED] Founder pricing: two disconnected systems, discount never applied, racy gate

- Enforcement at subscribe: `countActiveFounderSubscriptions() >= founderMaxSlots` (`billing/service/BillingService.java:270-277` vs `application.yml:692` `max-slots: 100`) — unlocked check-then-act (TOCTOU; two concurrent checkouts both pass at 99).
- The purpose-built atomic allocator `FounderPricingService.tryAllocate` / `revokeAllocation` (pessimistic lock, allocation rows, lifetime semantics — `billing/service/FounderPricingService.java:79-181`) has **zero production callers** (grep: only its unit test). Its Javadoc says "Use tryAllocate from BillingService.subscribe pre-checkout … forwards the coupon ID to Stripe / Razorpay" — none of that is wired.
- The pricing-page badge `GET /api/billing/founder-pricing/{tier}` (`billing/controller/BillingController.java:55-61`) reads the *allocator's* tables (`FounderPricingDiscount`), while subscribe enforces the *other* mechanism — capacity numbers can disagree.
- No discount is applied anywhere: `FounderPricingDiscount.stripeCouponId`/`razorpayOfferId` are never read; `billing.founder-pricing.discount-pct: 30` (`application.yml:693`) has no readers. "Founders" pay the plan's full price unless ops hand-made a cheaper plan row.

### 2.9 [BUG][MED] Currency-based gateway routing does not exist

`application.yml:705-709` defines `billing.currency-gateway-map: {INR: RAZORPAY, USD: STRIPE, EUR: STRIPE, GBP: STRIPE}` — **zero readers in Java** (repo-wide grep). `application-prod.yml:183-186` claims "Razorpay continues to serve INR customers via currency-based routing (see PaymentGatewayRouter + BillingController)" — neither class contains any currency logic. Actual routing (`gateway/PaymentGatewayRouter.java:40-63`): explicit `preferredGateway` request param → `entity.getPaymentGateway()` → system default. Currency only influences Stripe price-id selection (`StripePaymentGateway.resolvePriceId`/`resolvePriceIdByTier`, `:335-381`, keyed off `baseAmountInr>0 && baseAmountUsd==0`). An INR customer with no stored preference on prod (default stripe) gets routed to Stripe.

### 2.10 [BUG][MED] FREE-tier users can never spend bonus/top-up runs

`TierEnforcementService.assertUserQuota` FREE branch throws 402 at the cap **without consulting top-ups** (`auth/service/TierEnforcementService.java:570-580`), while the non-FREE branch (`:582-592`), the org branch (`:595-612`), and the second gate `UsageTrackingService.assertWithinLimits` (`billing/service/UsageTrackingService.java:100-114`) all allow over-cap when bonus > 0. Top-up purchase (`RunTopupService.initiatePurchase`) and admin grants (`grantBonusRuns`) have no tier restriction — a FREE user can buy/receive runs they can never use (solo-FREE users without an org hit the FREE branch).

### 2.11 [BUG][MED] SUNSETTING status orphans paid subscribers

`TierLifecycleService.executeSunsettingSideEffects` sets `status = "CANCELLING_AT_PERIOD_END"` (`billing/lifecycle/TierLifecycleService.java:334`). That status is (a) not in `findActiveByUserId`'s `('FREE','ACTIVE','PAST_DUE')` set (`repository/BillingSubscriptionRepository.java:18-21`) → the subscriber instantly loses subscription resolution everywhere (getStatus shows none, `UsageTrackingService` falls back to FREE plan limits) despite being paid through period end; and (b) not in `findCancelledPastPeriodEnd`'s `status = 'CANCELLED'` (`:56-59`) → `PeriodEndDowngradeScheduler` never picks them up, contradicting `LifecycleState`'s own Javadoc ("then auto-downgrade to FREE via the existing PeriodEndDowngradeScheduler", `lifecycle/LifecycleState.java:29-34`). Gated behind `billing.lifecycle.sunsetting.enabled` (default off) but it is the designed path.

### 2.12 [BUG][LOW] `spentUsd` window wrong for annual plans (and dark)

`BillingService.getStatus` computes `periodStart = currentPeriodEnd − 30d` (`billing/service/BillingService.java:614-617`). For ANNUAL subs (`computePeriodEnd` = +365d, `:1413-1418`) that start is ~335 days in the **future** → `spentUsd` always 0. Also for a monthly sub activated < 30d ago the window reaches back before the period start. Currently no frontend consumes `spentUsd` (`lib/billing-api.ts` has zero importers), but the endpoint is public API.

### 2.13 [BUG][LOW] "Resend invoice" emails the admin, not the customer

`InvoiceController.resendInvoice` sends to `principal.getUser().getEmail()` — the **calling admin** — instead of the invoice's owner (`billing/controller/InvoiceController.java:193-211`).

### 2.14 [BUG][LOW] Stripe-side local invoices book INR + Indian GST for USD sales

`generateStripeSubscriptionInvoice` uses `plan.getBaseAmountInr()` as the subtotal (`billing/service/InvoiceService.java:176-178`; 0 for USD-only plans) and `buildInvoice` hardcodes `currency("INR")` (`:357`) + applies GST via `GstCalculationService` based on the buyer profile (default country "IN", `controller/InvoiceController.java:296-303`). Wrong amount, currency and tax treatment for Stripe/USD sales; also flows into `AccountingService.recordRevenueEntry` and GSTR-1 export. Currently unreachable due to 2.2, but will mis-book the moment 2.2 is fixed.

### 2.15 [BUG][LOW] Webhook events lost on processing error (idempotency vs durability)

`processRazorpayWebhook` saves the event row, then catches any processing exception, logs, and returns 200 **without marking processed** (`billing/service/BillingService.java:697-703`). On Razorpay's retry, the dedup check `existsByGatewayAndEventId` (`:674`) sees the stored row and skips — the event is permanently dropped ("can be reprocessed manually": no such tooling exists). Same shape for Stripe (`:869-909`). The dedup should key on *successfully processed* events, or a re-processor for `processed=false` rows is needed. Related: the controller derives the Stripe event id by string-stripping `"Event validated: "` from a log-style message (`billing/controller/BillingWebhookController.java:93-94`) — fragile contract between `WebhookResult.message` and the controller.

### 2.16 [BUG][LOW] Reminder/dunning spam — no per-subscription send tracking

- `RenewalReminderScheduler` matches the same sub on each daily run inside the 3-day window (`billing/scheduler/RenewalReminderScheduler.java:41-47` + `findRenewingBefore`) → 3 duplicate emails + 3 in-app notifications per renewal.
- `DunningScheduler` fires daily forever once `daysPastDue >= 3` (`billing/scheduler/DunningScheduler.java:37-51`; GENTLE on days 3-6, URGENT 7-13, FINAL every day from 14 on), and uses `sub.getUpdatedAt()` as the past-due anchor — any unrelated save resets the dunning clock.

### 2.17 [BUG][LOW] Cross-referrer invite collision breaks referral binding

`recordInvite` dedupes only per `(referrer, email)` (`referral/ReferralService.java:88-93`); the unique index is `(referrer_user_id, lower(referee_email))` (`db/migration/V153__referral_invite.sql:44`), so **two different referrers can invite the same email** → two rows. `bindReferral`/`onRefereeFirstReadyRun` then call `findByRefereeEmailIgnoreCase` which returns `Optional` (`referral/ReferralInviteRepository.java:18-19`) → `IncorrectResultSizeDataAccessException` → binding/grant silently fails (signup survives via try/catch in `AuthService.tryBindReferral`). Also doc-vs-code: Javadoc says "both get +5" (`ReferralService.java:21-23`) but only the **referrer** is credited (`:197`).

### 2.18 [RISK] Top-up paths

- `RunTopupService.verifyAndCredit` never asserts `topup.getUserId().equals(userId)` (`billing/service/RunTopupService.java:188-210`) — the authenticated caller param is used only for notification/audit. Exploitability low (needs the Razorpay signature) but the ownership check is missing.
- Verify (client) and `handleOrderPaymentWebhook` (server) race: both read `status == "PENDING"` without a row lock (`RunTopupService.java:200-203` / `BillingService.java:820-826`) → duplicate invoice + duplicate accounting entries possible (`generateTopupInvoice` has **no paymentId dedup**, unlike the subscription invoice path `InvoiceService.java:40-47`). Credit amount itself is idempotent (both set `remaining = quantity`).
- Dead branch: `verifyAndCredit` parses `grantReason.startsWith("pkg:")` to resolve the package (`RunTopupService.java:237-244`), but `initiatePurchase` never writes `grantReason` → always null → invoice always uses the fallback description.
- Top-ups are Razorpay/INR-only (`initiatePurchase` hardcodes `pkg.getAmountInr(), "INR"` at `:163`); `TopupPackage.amountUsd` exists but no Stripe purchase path is wired (see dead `createTopupCheckoutSession`, §3).

### 2.19 [RISK] Prorated refund details

`issueProratedRefund` (`billing/service/BillingService.java:1170-1303`): refund base is `invoice.getTotalAmount()` — **GST-inclusive** — proportioned by min(unused-runs, remaining-days). Reasonable anti-gaming, but: Stripe upgrades get **no refund at all** ("handled via Stripe dashboard", `:1256`) — silent INR/USD customer-fairness gap; refund failure is swallowed (`:1298-1302`) with no retry/ledger flag.

### 2.20 [INCONSISTENT] Tier/limit enforcement — the full map (they disagree)

Monthly/concurrent run limits are read in **five+ places** with different stores, different tier resolution, and different reset windows:

| # | Site | Counter store | Limit source | Tier resolution | Reset |
|---|---|---|---|---|---|
| 1 | `TierEnforcementService.assertAndIncrementRunCount`/`assertCanSubmitRun`/`incrementRunCount` (`auth/service/TierEnforcementService.java:88,214,449`) | `org.monthly_run_count` / `app_user.personal_run_count` | `tier_config.monthly_run_limit` (+ org override, ENTERPRISE+BYOK=∞) | org tier **only** if user has org (`effectiveOrgLimit:534`); SUPER_ADMIN bypass | billing-cycle (sub period) else calendar month, **Asia/Kolkata** |
| 2 | `UsageTrackingService.assertWithinLimits` (`billing/service/UsageTrackingService.java:49-126`) | `billing_usage.current_value` | RUNS → `tier_config` via **EntitlementResolver (higher-of org,user)**; other metrics → `billing_plan_limit` | higher-of(org,user) | calendar month (Asia/Kolkata), `getOrCreateUsage:182` |
| 3 | `EntitlementResolver.resolve` (`entitlements/EntitlementResolver.java:75-131`) | `personal_run_count` (even for org users → shows 0) | `tier_config` | higher-of(org,user); **no** SUPER_ADMIN special-case | n/a (display) |
| 4 | `TierPermissionService` (→ `/api/auth/me/permissions`, what IdeBilling trusts; `auth/service/TierPermissionService.java:137,192-193`) | `org.monthly_run_count` or `personal_run_count`; SUPER_ADMIN hardcoded 0 | `effectiveOrgLimit` | role/org-based | n/a |
| 5 | `AnalyticsService.getUsageMetrics`/`getBillingOverview` (`billing/service/AnalyticsService.java:50,260`) | `pipeline_run` rows / `org.monthly_run_count` | `billing_plan.run_limit` (**default 5**, `:266`) | none (entityType param) | rolling N days |
| 6 | dormant: `LifetimeCreditService` (`credit/LifetimeCreditService.java`) | `app_user.lifetime_run_credit_balance` | cap 25 | user.tier only | lifetime |

Concrete divergences: (a) **fork path** `RunController.java:540-578` uses the non-incrementing `assertCanSubmitRun` probe + increments only `billing_usage` — forks never consume the #1 quota counter (the BUG-006 charge-at-submit fix was applied to submit only), so counters #1 and #2 drift per fork; (b) a PRO user inside a FREE org: gate #2 allows PRO limits, gate #1 enforces FREE org limits — stricter wins, surfaces contradictory errors; (c) gate #1 resets on billing cycle, gate #2 on calendar month → windows disagree mid-cycle; (d) stale fallbacks: `FALLBACK_FREE = 5` (`TierEnforcementService.java:63`) and `EntitlementResolver.java:85` (`FREE → 5`) vs the V200 FREE=15; error copy "Upgrade to Pro for 20 credits/month" (`TierEnforcementService.java:576`); `BillingOverview` default `runLimit = 5`.

### 2.21 [INCONSISTENT] Seat management

- subscribe allows TEAM **1**-20 (`billing/service/BillingService.java:174-180`, 2026-05-22 change); `OrgService.adjustSeats` still enforces **2**-20 (`auth/service/OrgService.java:48` `TEAM_MIN_SEATS = 2`, check at `:808`) → a single-seat TEAM org cannot adjust seats without jumping to ≥2.
- Three seat writers with different rules: `BillingService.updateSeats` (no max, no gateway — §2.7), `OrgService.adjustSeats` (2-20, Stripe-synced, Razorpay→501), Stripe webhook sync (`fetchStripeSubscriptionSeatCount` → `totalSeats` + `org.seat_count`, `BillingService.java:917-990`). `extraSeats` is only ever set by the dead `updateSeats` path yet is billed on invoices (`InvoiceService.java:53-54,86-102`).

### 2.22 [INCONSISTENT] Misc

- Two different `TIER_RANK` maps: `BillingService.java:94-95` (`STARTER=1`, no MAX) vs `EntitlementResolver.java:61-67` (`MAX=2`, no STARTER).
- `BillingService` audits `verifyAndActivate` with hardcoded currency "INR" (`:415-416`) — correct only because that endpoint is Razorpay-only; nothing enforces that.
- `findActiveByUserId` returns `Optional` but the protecting partial-unique index `idx_billing_sub_active_user` (`db/migration/V23__billing_system.sql:68-70`) covers exactly `('FREE','ACTIVE','PAST_DUE')` — any new "active-ish" status (like `CANCELLING_AT_PERIOD_END`) silently escapes both the index and the queries.
- Two email stacks in one package: `BillingNotificationService` (Gmail API direct) vs `InvoiceEmailService` → `MailService`.
- `RunCostTracker` buffers 1.10 in one estimator (`cost/RunCostTracker.java:256`) and `BUFFERED_MULTIPLIER = 1.20` in the tier-aware one (`:323`); `CostHistoryService.java:149` re-hardcodes `1.20` and `5.0` instead of the constants.

### 2.23 [LAYERING]/[SMELL]

- `StripePaymentGateway` uses field injection `@Autowired(required=false) BillingPlanRepository` (`gateway/StripePaymentGateway.java:49-50`) — violates the project's constructor-injection rule, and puts a repo dependency inside a gateway adapter (layering).
- `BillingService` is `@Transactional` at class level (`BillingService.java:38`): webhook processing holds a DB transaction across invoice PDF generation and Gmail sends; `subscribe` makes gateway HTTP calls (cancel/refund/checkout-create) inside the TX; `TierLifecycleService.transitionTier` runs per-subscription gateway cancels inside its TX (`lifecycle/TierLifecycleService.java:86-129` → `executeSunsettingSideEffects`).
- `RazorpayConfig.razorpayHttpClient` builds a `RestClient` with **no connect/read timeouts** (`billing/config/RazorpayConfig.java:36-56`) — a slow Razorpay call can pin request threads (and TXs, per above).
- `InvoiceService.nextInvoiceNumber` is `@Transactional` on a self-invoked method (`InvoiceService.java:389-390`) — annotation has no effect (callers' TX saves it).
- `BillingWebhookController.logWebhookEvent` + `BillingService`'s own `BillingWebhookEvent` = two overlapping webhook audit tables ([DUPLICATION]); `WebhookEventLog.processingStatus` values RECEIVED/FAILED are never written.
- Two `TenantContext` classes: `tenant/TenantContext.java` (header-based, "default" fallback) and `config/datasource/TenantContext.java` (orgId-based) — same name, different thread-locals ([DUPLICATION]). The header-based one is vestigial: **nothing in `frontend/` ever sends `X-Tenant-Id`** (grep), so every `@EntityListeners(TenantInterceptor)` entity (PipelineRun, AgentTask, …) persists `tenant_id='default'`; `TenantInterceptor` does per-insert reflection (`tenant/TenantInterceptor.java:33-49`).
- `AnalyticsService.getUsageMetrics` "costByModel" actually returns **call counts** as the map values (`AnalyticsService.java:201-205` puts `calls`), with the admission at `:172-174` ("model_task_metric tracks duration… not cost. TODO: Add actual cost tracking").

### 2.24 [PERF]

- `AccountingService.getGstSummary` + `exportGstr1` load `invoiceRepo.findAll()` and filter in memory (`billing/service/AccountingService.java:119`, `:138`) — quoted comment: `// For now, filter in memory`.
- `exportGstr1` printf `%.2f` with `inv.getCgstRate() != null ? inv.getCgstRate() : 0` (`AccountingService.java:155-173`) — the ternary yields `Integer` when the rate is null → `IllegalFormatConversionException` on any legacy row with null rates (latent crasher for the whole export).
- GST money math on `double` + `Math.round` (`GstCalculationService.java:59-75`) while the `BigDecimal` rate constants go unused in arithmetic — fine at paise scale, but inconsistent for a tax engine.
- `AccountingService.getRevenueMetrics` ignores its `from`/`to` for MRR (`:96-100`), counts top-ups as recurring revenue, never subtracts refund DEBITs, and `arr = mrr × 12`; `TierLifecycleService.listAll` MRR counts annual plans at full price per month and only USD amounts (`lifecycle/TierLifecycleService.java:251-254`).

---

## 3. Dead code & junk (grep-verified, src/main + src/test + frontend)

- **`FounderPricingService.tryAllocate` / `revokeAllocation`** — zero production callers; only `FounderPricingServiceTest`. The V177 "atomic allocation contract" was built and never wired into `BillingService.subscribe` (which still uses the racy count, §2.8).
- **`TierEnforcementService.assertAndIncrementRunCount(String, int credits)`** — zero callers. The multi-credit charge path is dead while the multi-credit refund is live (§2.3).
- **`StripePaymentGateway.createTopupCheckoutSession`** and **`getCustomerPortalUrl`** — zero callers anywhere.
- **`LifetimeCreditService.decrementForRun` / `refund` / `canSubmit`** — zero production callers (the v3 lifetime-credit lane never decrements; only `currentBalance` is read for display and `grantInviteCredit` adds). The referral grant accrues credits nothing can spend.
- **`BillingGateway.fetchStatus`** (+ `RazorpayGateway.fetchStatus` impl) — zero callers.
- **`BillingPlanRepository.countByFounderPricingTrueAndActiveTrue`** and **`findFirstByTierAndKeyModeAndActiveTrue`** — zero callers.
- **`RunTopupService.listTopups`** — zero callers. **`RunTopupService.expireOverdueTopups`** — Javadoc says "Called by scheduler"; **no scheduler calls it** — `RunTopup.status='EXPIRED'` is never written (harmless only because every query also filters `expiresAt > :now`).
- **`application.yml` dead config**: `billing.currency-gateway-map` (`:705-709`) and `billing.founder-pricing.discount-pct` (`:693`) — no readers (§2.8/2.9). The prod-yml comment pointing at currency routing is wrong (`application-prod.yml:183-186`).
- **`frontend/src/lib/billing-api.ts`** — zero importers; it is the only reference to `POST /api/billing/seats` (`:183`) and `BillingStatusResponse.spentUsd` (`:63`), making both backend surfaces dark.
- **`AdminAnalyticsController.assertAnalyticsAccess` non-SUPER_ADMIN branches** (`:111-133`) — unreachable behind `SecurityConfig`'s `/api/admin/**` SUPER_ADMIN rule (§2.1).
- **`RazorpayPaymentGateway.createCheckoutSession`** returns `null` by design with a warn log (`gateway/RazorpayPaymentGateway.java:24-35`) — an interface impl that can NPE its caller if ever routed; subscribe special-cases around it.
- **STARTER tier remnants**: `BillingService.java:95` rank map, `:1124` (`STARTER → FREE`), `BillingPlan.java:31` comment — legacy tier with no live plans (V62 restructure).
- Stale TODO/FIXMEs worth quoting:
  - `AnalyticsService.java:174` — `// TODO: Add actual cost tracking to model_task_metric for accurate per-model cost breakdown.`
  - `TierLifecycleService.java:346` — `// TODO Day-6: fire tier-sunsetting.html email to sub.userId.`
  - `AccountingService.java:119` — `var invoices = invoiceRepo.findAll(); // For now, filter in memory`
  - `EntitlementResolver.java:97` — `Double currentSpend = null; // Wave 2 follow-up — needs join with run_cost_log.`
  - `EntitlementResolver.java:88-90` — storage cap hardwired `-1` "until we wire billing_plan.limits in a follow-up".
  - `BillingService.java:1256` — `// Note: Stripe refunds handled via Stripe dashboard or future implementation`
- `WebhookEventLog.processingStatus` RECEIVED/FAILED never written; `BillingSubscription.billingEntityType`/`billingEntityId`/`metadata` columns never populated by any code path read in scope; `Invoice.gatewayOrderId`/`paymentMethod`/`notes`/`pdfStoragePath`/`pdfGeneratedAt` never written.

---

## 4. Model/LLM touchpoints

No direct LLM/provider calls in any in-scope package (correct layering — billing never touches `LlmRouter`). Touchpoints are read-only cost aggregation:

- **Credit formula** (the "1–N credits per run"): `cost/RunCostTracker.java:80-83` — `BUFFERED_MULTIPLIER = 1.20`, `USD_PER_CREDIT = 5.0`; legacy estimator `:256-258`:
  `double buffered = (low + high) / 2.0 * 1.10; int credits = Math.max(1, (int) Math.ceil(buffered / USD_PER_CREDIT)); double hardCap = credits * USD_PER_CREDIT;`
  tier-aware estimator `:323-325` uses `* BUFFERED_MULTIPLIER` (1.20). Historical k-NN variant duplicates constants: `cost/CostHistoryService.java:149` — `int credits = Math.max(1, (int) Math.ceil(((low + high) / 2.0 * 1.20) / 5.0));`
- Stamped on the run at planning: `integration/RunOrchestrator.java:2417-2419` (`costEstimateLow/High`, `creditsCharged`); hard cap = `credits × $5` clamped by `userCostCap` (`:2430-2436`, `clampHardCap:2461`); BYOK skips the platform cap.
- Spend reads: `pipelineRunRepo.sumActualCostByUserAndSince` (`state/repository/PipelineRunRepository.java:104`, sums `pipeline_run.actual_cost_usd`) + `execRunRepo.sumTotalCostByUserAndSince` (`execution/repository/ExecutionRunRepository.java:108`) in `BillingService.getStatus:618-630`; `AnalyticsService` sums the same columns via JDBC (`:66,81,387-427`).
- Provider strings appear only as labels: `RunCostLog.provider` (`billing/cost/RunCostLog.java:40-41`), `CostOutlierDetector` counter tag (`billing/service/CostOutlierDetector.java:92`), `AnalyticsService.costByModel` (`model_task_metric.model_name`, counts not cost — §2.23).
- Tier↔model coupling: `plan.keyMode` → `org.keySourceMode` mapping in `upgradeTier` (`BillingService.java:1145-1153`, `BYOK→OWN_KEYS`, `MANAGED|PLATFORM→SYSTEM`, else HYBRID) — this is what flips an org into BYOK and out of platform cost caps.
- Per-run cost budget by tier: `TierEnforcementService.resolveRunCostBudget` (`:791-809`, FREE $3 / PRO $5 fallbacks, TEAM/ENTERPRISE/PRO+BYOK = −1) and `requiresCostCap` (`:777-785`).

## 5. Libraries

- **Stripe Java SDK** (`com.stripe.*`) — checkout sessions, subscriptions, webhook signature (`Webhook.constructEvent`), billing portal. Global static `Stripe.apiKey` set in `@PostConstruct`.
- **Razorpay: no SDK** — hand-rolled `RestClient` client (`billing/client/RazorpayRestClient.java`) + Basic auth (`RazorpayConfig`), HMAC verification via `javax.crypto.Mac` + **commons-codec** `Hex`; constant-time compares via `MessageDigest.isEqual` (good).
- **Spring Data JPA** (+ `JpaSpecificationExecutor` on Invoice/AccountingEntry — specifications never used), **JdbcTemplate** raw SQL in `AnalyticsService`.
- **Thymeleaf** + **Flying Saucer / OpenPDF** (`org.xhtmlrenderer.pdf.ITextRenderer`) for invoice PDFs; new `ITextRenderer` per call.
- **Gmail API** (`com.google.api.services.gmail`) + `jakarta.mail` MimeMessage for billing notifications; separate `MailService` path for invoices/dunning ([INCONSISTENT]).
- **Micrometer** counters/timers (CostOutlierDetector); **Micrometer context-propagation** (`ThreadLocalAccessor`) in tenant.
- Lombok (`@Data`/`@Builder` on entities, `@RequiredArgsConstructor` DI) — consistent with conventions except the one field-`@Autowired` (§2.23).

## 6. Test coverage

In-scope test files (6, 1,182 LOC): `TierLifecycleServiceTest` (state machine only — repo/gateway paths untested; the promised `TierLifecycleServiceIntegrationTest` was never added, per its own Javadoc), `FounderPricingServiceTest` (thorough — but pins an **unwired** service), `LifetimeCreditServiceTest` (thorough — dormant service), `EntitlementResolverTest` (good: higher-of-tier + sentinel-leak guards), `ReferralServiceTest` + `ReferralCompletionHookTest` (good unit coverage).

**Zero tests** for: `BillingService` (1,419 LOC — subscribe/upgrade/cancel/webhooks/refund/tier-bridge), both webhook controllers, `UsageTrackingService`, `TierEnforcementService` interplay, `AnalyticsService` (the entityType/`org_id IS NULL` bugs would have surfaced), `InvoiceService`/`GstCalculationService`/`AccountingService` (money + tax math), `RunTopupService`, `PaymentGatewayRouter`/`StripePaymentGateway`/`RazorpayGateway`, all three schedulers, `tenant/*`. The inversion is notable: the two best-tested classes are the two with no production wiring. No test would catch 2.1, 2.2, 2.3, 2.5 today.

## 7. Security notes (parked)

- **Cross-org payment-history read** — §2.4 (the one actionable security bug found).
- Webhooks `permitAll` + HMAC/Stripe-Signature verification before processing — correct; Razorpay uses constant-time `MessageDigest.isEqual`; missing-signature → 401.
- `verifyPaymentSignature` logs an 8-char prefix of the **expected** signature on mismatch (`billing/security/WebhookSignatureVerifier.java:54-61`) — negligible oracle, but unnecessary.
- `InvoiceController.issueCreditNote` accepts any `amountPaise` (negative or > original invoice; `:156-174`) — FINANCE/SUPER_ADMIN-gated, but no bounds; negative values would generate inverted accounting entries.
- `BillingController.listPlans?includeHidden` correctly downgraded for non-SUPER_ADMIN (`:43-47`); founder badge endpoint hides raw counts by design (`:63-78`).
- `AdminBillingController.seedPlans` has no method-level guard but sits under the `/api/admin/**` SUPER_ADMIN config rule — config-only protection; an annotation would be defense-in-depth.
- `TenantFilter` header validation + principal cross-check is sound (`tenant/TenantFilter.java:35-80`); moot while no client sends the header.
- `RunTopupService.verifyAndCredit` missing ownership assert — §2.18.
- Invoice ownership checks (`InvoiceController.requireOwnership:257-268`) are correct (user OR same-org).
