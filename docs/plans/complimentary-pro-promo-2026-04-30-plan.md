# Complimentary PRO promo — execution plan

> **Execution date:** 2026-04-30 (Wed)
> **Owner:** Navneet
> **Decision made:** 2026-04-22 (this doc)
> **Status:** PLANNED — do not execute before 2026-04-30 unless explicitly approved
> **Related:** `docs/reports/session-handoff-2026-04-21.md` (pricing v3 context), `CLAUDE.md` §v2.6.0 (US-first pivot + PH launch kit)

## 1 · Goal

Upgrade a hand-picked list of early dev-env signups to **PRO for 30 days, zero charge**, as a founder thank-you. Use the 30-day window to
(a) get honest product feedback, (b) convert a fraction to paid on expiry, (c) seed referrals ahead of Product Hunt.

The financial cost (≤ $20 × N users in LLM spend) is explicitly absorbed by the founder. No board approval needed.

## 2 · Option chosen — A (30-day free PRO, not bonus credits)

From the 3 options presented on 2026-04-22:

| Option | Selected |
|---|---|
| A. Free PRO for 30 days | ✅ |
| B. Free PRO for 90 days | — |
| C. Bonus credits only (keep FREE tier) | — |

**Why A not C:** PRO unlocks the actual differentiators (provider choice, 2 projects, 90-day retention, project memory). Bonus credits on FREE
leaves them in the "5 runs/month, single-credit caps, demo project only" experience that's already the weakest tier in the lineup.

**Why A not B:** 90 days trains a "free forever" expectation; 30 days is a clear trial envelope. If conversion on 30 days is ≥ 20% we repeat.
If < 10% we go back to paid-only promos.

## 3 · Target user selection

### 3.1 SQL (run against dev DB on 2026-04-30 morning)

```sql
SELECT id, email, first_name, last_name, tier, oauth_provider, created_at, last_login_at
FROM app_user
WHERE status = 'ACTIVE'
  AND tier = 'FREE'
  AND created_at > now() - INTERVAL '21 days'
  AND email NOT LIKE '%@executespec.%'      -- exclude internal test accounts
  AND email NOT LIKE '%+test%'              -- exclude +test email aliases
  AND email NOT LIKE '%@example.com'
ORDER BY created_at DESC;
```

**Window justification:** 21 days catches signups from 2026-04-09 onwards — covers the v2.5.13 RN codegen ship + v2.6.0 UX overhaul. Older
signups (pre-2026-04-09) either converted already or are stale enough that a "thanks for signing up" email will land as spam.

### 3.2 Manual filter (before sending)

Do NOT auto-grant. Paste the query output into this plan's Appendix A on 2026-04-30 and **strike through anyone who:**
- has a work email of a known competitor (Cursor / Replit / Devin / Augment / Cognition etc.)
- has an obvious throwaway domain (mailinator, guerrillamail, tempmail, etc.)
- has already hit any paid endpoint (check `billing_subscription` for rows with `gateway IN ('razorpay','stripe')` — they don't need this)
- registered but never verified email (`email_verified = false`) — they haven't experienced the product yet; don't complicate.

Target list size: **5-30 users**. If the query returns > 30 we tighten the date window to 14 days.

### 3.3 Separate account for my own test row
Before running against real users, dry-run the whole pipeline against my own `navneet.prabhakar007@gmail.com` account (which should not be
in the target list because it's internal). Verify the email arrives, the dashboard shows PRO, and the auto-downgrade cron fires at day 30.

## 4 · Tier-change mechanics

### 4.1 Data writes per user (single transaction)

```
app_user:
  SET tier = 'PRO', updated_at = now()
  WHERE id = :userId

billing_subscription:
  INSERT (user_id, plan_id, status, current_period_start, current_period_end,
          gateway, payment_gateway, created_at, updated_at)
  VALUES (:userId, <PRO_PLAN_ID>, 'ACTIVE', now(), now() + interval '30 days',
          'COMPLIMENTARY', 'COMPLIMENTARY', now(), now())

activity_audit:
  INSERT (event_type, entity_type, entity_id, actor_user_id, new_value, reason, created_at)
  VALUES ('COMPLIMENTARY_PRO_GRANTED', 'USER', :userId, '<founder-id>',
          '{"tier":"PRO","days":30,"promo":"2026-04-30-founder-thank-you"}',
          'Founder thank-you promo — see docs/plans/complimentary-pro-promo-2026-04-30-plan.md',
          now())
```

### 4.2 Gateway value: `COMPLIMENTARY`

`BillingSubscription.gateway` is a free-text VARCHAR(20) (not an enum) — `"COMPLIMENTARY"` is a new string value that:
- Does **not** match `"razorpay"` or `"stripe"`, so `PaymentGatewayRouter` will not attempt to charge / renew
- Does **not** trigger webhook flows (webhooks dispatch on explicit gateway match)
- Is visible in the admin console with the string "COMPLIMENTARY" — easy to filter later

**Migration not required** — no schema change, just a new string value in the column.

### 4.3 What happens at day 30

`PeriodEndDowngradeScheduler` already runs nightly and downgrades subscriptions where `current_period_end < now()` AND `status = 'ACTIVE'`.
When it hits a `COMPLIMENTARY` row it'll set the user's tier back to FREE cleanly — no new code needed.

**Verification required on 2026-04-29:** read `PeriodEndDowngradeScheduler.java` and confirm it does not skip `gateway=COMPLIMENTARY` rows.
If it does skip them, add an explicit branch: "COMPLIMENTARY subscriptions auto-downgrade at period end like any other." (Small patch, ~10 LOC.)

## 5 · Backend changes required before 2026-04-30

Keep the scope tight — one endpoint, one service method, one template.

### 5.1 New endpoint (SUPER_ADMIN only)

```
POST /api/admin/users/{id}/complimentary-pro
Body: { "days": 30, "promoCode": "2026-04-30-founder-thank-you", "skipEmail": false }
Auth: SUPER_ADMIN
Response: 200 { "userId", "tier": "PRO", "expiresAt", "emailSent": true/false }
```

Single-user endpoint, not bulk — forces the operator (me) to look at each user one at a time in the admin UI.

### 5.2 New service method

`SuperAdminController` → new `ComplimentaryPromoService.grantPro(userId, days, promoCode, skipEmail)` that:
1. Loads user, verifies `status = ACTIVE` and `tier = FREE`
2. Runs the 3 writes from §4.1 in a single `@Transactional` block
3. Fires `mailService.sendComplimentaryProEmail(user, days)` (new method)
4. Logs `log.info("Complimentary PRO granted: user={} days={} promo={}", email, days, promoCode)`
5. Returns the result DTO

### 5.3 New MailService method

```java
@Async
public void sendComplimentaryProEmail(AppUser user, int days) {
    Context ctx = new Context();
    ctx.setVariable("firstName", user.getFirstName());
    ctx.setVariable("days", days);
    ctx.setVariable("expiresAt", Instant.now().plus(Duration.ofDays(days)));
    ctx.setVariable("baseUrl", baseUrl);
    sendHtml(user.getEmail(),
             "You've been upgraded — PRO, on us, for " + days + " days",
             "email/complimentary-pro",
             ctx);
}
```

### 5.4 New email template

`src/main/resources/templates/email/complimentary-pro.html` — based on the Graphite+Signal palette used in `new-user-alert.html` and
`weekly-admin-summary.html`. Body copy (approved 2026-04-22):

> Hi {{firstName}},
>
> I'm Navneet, founder of ExecuteSpec. You signed up recently while we were still tuning things, and I want to say thanks by upgrading your
> account to **PRO, on us, for 30 days** — no credit card, no catch.
>
> Starting now you have:
> - **20 credits this month** (20 LOW specs, 6 HIGH specs, or 10 Mobile-app specs — any mix)
> - Your choice of **Anthropic · OpenAI · Gemini** on every run
> - **2 projects · 2 linked repos · project memory · architecture docs**
> - **90-day run history** (was 30)
>
> The easiest way to feel the difference is to link one of your own GitHub repos and submit a real spec — [open your dashboard →]({{baseUrl}})
>
> After 30 days we'll automatically drop you back to Free. If you want to stay on PRO, it's $20/mo — but there's zero pressure. I genuinely
> want your honest feedback on what's good and what's broken.
>
> Reply to this email with anything — I read every one.
>
> — Navneet

CTA button: "Open your dashboard" → `{{baseUrl}}/projects`.

Footer: a one-line plain-text reply-to hint: _"Reply directly to this email — it goes to my personal inbox."_

### 5.5 Audit view

No new UI needed — existing admin activity-audit page will show the `COMPLIMENTARY_PRO_GRANTED` rows with the promo code in `new_value`.

## 6 · Execution runbook (2026-04-30)

**Pre-flight (same morning, before running anything):**
- [ ] Dev server is healthy: `curl http://localhost:8080/actuator/health` returns `UP`
- [ ] Mail transport is live: trigger `POST /api/admin/test-email` with my own address, confirm it lands in inbox within 60 s
- [ ] `PeriodEndDowngradeScheduler` handles `COMPLIMENTARY` gateway (verified on 2026-04-29)
- [ ] Run §3.1 SQL, paste results into Appendix A of this doc
- [ ] Strike through filtered-out rows per §3.2 rules, commit this doc with the final list
- [ ] Dry-run pipeline against my own account (see §3.3) — confirm email + tier change + audit row
- [ ] Back up `app_user` + `billing_subscription` + `activity_audit`: `pg_dump --table=app_user --table=billing_subscription --table=activity_audit > backup-2026-04-30.sql`

**Execution:**
1. For each user on the confirmed list, hit `POST /api/admin/users/{id}/complimentary-pro` with `{"days": 30, "promoCode": "2026-04-30-founder-thank-you"}`
2. After each call, eyeball the admin page for that user — confirm tier=PRO, subscription row exists with `gateway=COMPLIMENTARY`, audit row present
3. Wait ~2 minutes between calls so mail throughput stays sane and I can bail if something looks off
4. After all users processed, spot-check 2-3 in the admin UI + confirm at least 1 email landed in an external inbox (ask a test-user friend)
5. Log the run in Appendix B of this doc (final count, any errors, any users skipped at execution time)

**Post-execution (same day):**
- [ ] Send myself a summary email with the final user list + timestamps
- [ ] Set calendar reminder for 2026-05-30 ("promo users auto-downgrade today — verify")
- [ ] Set calendar reminder for 2026-05-15 ("check promo conversion — who ran a paid run, who churned silently")

## 7 · Rollback plan

If anything looks off mid-execution:
1. Stop firing new endpoint calls immediately
2. For already-granted users, revert per-user:

```sql
BEGIN;
UPDATE app_user SET tier = 'FREE', updated_at = now() WHERE id = :userId AND tier = 'PRO';
DELETE FROM billing_subscription WHERE user_id = :userId AND gateway = 'COMPLIMENTARY' AND current_period_end > now();
INSERT INTO activity_audit (event_type, entity_type, entity_id, actor_user_id, new_value, reason, created_at)
  VALUES ('COMPLIMENTARY_PRO_REVERTED', 'USER', :userId, '<founder-id>',
          '{"reason":"manual_rollback"}', 'Manual rollback of complimentary PRO promo', now());
COMMIT;
```

3. No email is sent on revert — the original promo email stays, but when the user visits the dashboard they'll see FREE. Live with this —
sending a "sorry, we revoked your PRO" email is worse than silence.

4. Full-run nuke (if I want to undo everything): use the `backup-2026-04-30.sql` from pre-flight + `psql -f backup-2026-04-30.sql` on a paused
app. Only if the damage is systemic.

## 8 · Success metrics

Tracked in `run_cost_log` + `billing_subscription` + `activity_audit`:

| Metric | Target | Data source |
|---|---|---|
| Promo-eligible users reached | 100% of filtered list | `activity_audit` rows with `event_type='COMPLIMENTARY_PRO_GRANTED'` |
| Delivered emails (soft bounces excluded) | ≥ 95% | `mail_log` if present, else Gmail sent folder |
| Day 7: user ran ≥ 1 run | ≥ 40% | `pipeline_run.created_by_user_id` JOIN `activity_audit` on promo grant |
| Day 30: user converted to paid PRO | ≥ 20% | `billing_subscription` rows with `gateway IN ('razorpay','stripe')` appearing after grant |
| Day 60: still active (any login) | ≥ 50% | `app_user.last_login_at` |
| Direct replies to the promo email | ≥ 15% | manual inbox count |

**Kill criteria for repeat runs:** if day-30 conversion < 10%, retire the "free PRO for 30 days" tactic; shift to Option C (bonus credits on
FREE) + a lighter-touch survey email instead.

## 9 · What this plan deliberately does NOT do

- **No cross-posting on Twitter / Slack** about the promo. Private thank-you, not a marketing stunt.
- **No auto-rollover** to paid at day 30. The expiry is genuine — we don't want the "accidentally charged me" complaint.
- **No referral attached.** The promo email does not ask for a referral, share link, PH upvote, or survey fill. One ask = one signal. We can
do a separate referral pitch at day 14.
- **No list expansion mid-flight.** Whatever list lands in Appendix A is the list. If we want to promo more users later, that's a separate
plan doc.
- **No retroactive credit.** Users who already ran FREE this month don't get their quota reset on top of the upgrade — they just get the 20
PRO credits going forward.

## 10 · Open items before 2026-04-30

- [ ] Write the `ComplimentaryPromoService` + endpoint + template (§5) — estimate: 2 hours
- [ ] Verify `PeriodEndDowngradeScheduler` handles `gateway=COMPLIMENTARY` (§4.3) — estimate: 15 minutes
- [ ] Commit the 3 backend changes (admin alert + weekly summary + promo endpoint) as one PR ahead of execution — estimate: 30 minutes
- [ ] Test the dry-run path against my own account (§3.3) — estimate: 15 minutes

Total ~3 hours of focused work. Allocate Mon 2026-04-28 afternoon or Tue 2026-04-29 morning.

---

## Appendix A — Target user list (fill in on 2026-04-30)

_Paste the output of the §3.1 SQL query here on execution day. Strike through (`~~...~~`) the rows that fail the §3.2 filter. The remaining
non-struck rows are the execution target._

```
id                                    | email                        | first_name | last_name | created_at          | oauth_provider
--------------------------------------+------------------------------+------------+-----------+---------------------+----------------
(fill in)
```

## Appendix B — Execution log (fill in on 2026-04-30)

_Timestamped log of the execution — one line per user processed or skipped, with any errors._

```
(fill in)
```
