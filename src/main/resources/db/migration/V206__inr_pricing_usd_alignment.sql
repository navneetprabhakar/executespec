-- V206 — INR pricing kept ~10% below USD as USD strengthened (2026-05-23).
--
-- USD strengthened vs INR (rate ~₹96/USD). A proposed flat +15% INR bump was
-- checked against the live USD prices and would have pushed Pro Monthly + Yearly
-- ABOVE their USD counterparts (₹2,899 = $30.20 > $29; ₹28,599 = $297.91 > $289),
-- which we don't want — INR should stay BELOW USD (the regional discount).
--
-- At ₹96/USD the OLD INR prices already sit ~10% below USD, so they are kept:
--   * PRO/Solo Monthly INR : ₹2,499  (unchanged — $26.03 ≈ 10% below $29)   — no row touched
--   * PRO/Solo Yearly  INR : ₹24,890 (unchanged — $259.27 ≈ 10% below $289) — no row touched
--   * TEAM Monthly INR/seat: ₹3,199 → ₹3,699 was 14.6% below $39 (deeper than the
--                            other tiers); trimmed to ₹3,399 (~9% below) to bring
--                            it into the same ~10% band. (per_seat_inr 319900 → 339900)
--
-- Only the TEAM monthly INR row moves. The WHERE pins the old amount so only the
-- canonical row is touched; the stale duplicate Pro-monthly rows are handled in V207.
--
-- Razorpay note: Razorpay plans are immutable, so recreating the TEAM plan at the
-- new per-seat amount is a separate ops step (TEAM per-seat plans are seeded via
-- the subscribe path, not PlanSeederService). We do NOT null razorpay_plan_id here
-- (that would force a Razorpay API call on every boot and break keyless startup).
-- Existing subscribers stay grandfathered on their current plan.
--
-- Rollback: V_{next} restoring per_seat_inr = 319900 on the TEAM monthly INR row.

UPDATE billing_plan
   SET per_seat_inr = 339900
 WHERE tier = 'TEAM' AND period = 'MONTHLY' AND per_seat_inr = 319900 AND active = TRUE;
