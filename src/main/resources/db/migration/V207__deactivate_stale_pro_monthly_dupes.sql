-- V207 — deactivate the stale duplicate "Pro Monthly" plans (2026-05-23).
--
-- Two active PRO-monthly billing_plan rows exist per currency — the pre-V176
-- prices that were left active when V176 launched the Solo tier ($29 / ₹2,499)
-- on NEW rows:
--   * INR ₹1,699 (base_amount_inr 169900)  — stale; canonical is ₹2,499 (249900)
--   * USD $20    (base_amount_usd 2000)     — stale; canonical is $29   (2900)
--
-- Two active plans for the same tier/period/currency is a latent bug (ambiguous
-- plan selection at checkout). Deactivate the stale low-price rows so exactly one
-- canonical active PRO-monthly plan remains per currency.
--
-- Existing subscribers on the old rows are unaffected — a subscription references
-- its own Razorpay/Stripe subscription object, not the plan row's active flag;
-- this only removes the stale rows from NEW-purchase selection.
--
-- Rollback: V_{next} setting active = TRUE on these two rows.

UPDATE billing_plan SET active = FALSE
 WHERE tier = 'PRO' AND period = 'MONTHLY' AND base_amount_inr = 169900 AND active = TRUE;

UPDATE billing_plan SET active = FALSE
 WHERE tier = 'PRO' AND period = 'MONTHLY' AND base_amount_usd = 2000 AND active = TRUE;
