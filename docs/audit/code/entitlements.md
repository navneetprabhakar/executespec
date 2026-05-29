# Audit: `entitlements/` + `credit/` + `pricing/` — tier resolution, free-tier credits, model pricing

Status: **DONE** · Updated 2026-05-29 · 6 files / 422 lines · all read in full

## Files
- `entitlements/EntitlementResolver.java` (181) + `EntitlementsResponse.java` (28) ✓
- `credit/LifetimeCreditService.java` (141) ✓
- `pricing/ModelPricing.java` (40) entity + `ModelPricingId.java` (19) + `ModelPricingRepository.java` (13) ✓

## What it does
- **`EntitlementResolver`** — the v3 single-source resolver that collapses tier + caps + usage into one `EntitlementsResponse` for every v3 surface (billing page, composer gating, run-cap nudges).
- **`LifetimeCreditService`** — the v3 Free-tier "5 lifetime runs + 5/invite (cap 25)" balance arbiter (paid tiers = unlimited via an `Integer.MAX_VALUE` sentinel).
- **`pricing/`** — the `model_pricing` DB table (per `(provider, model)`: input/output/cached cost per 1M tokens).

## Findings — clean code; the headline is the multiplicity of overlapping credit/tier systems.

### Architecture (🟡 — three parallel "can this user run?" systems)
- 🟡 **There are (at least) three independent quota/credit notions, by design ("cohabitation"):** (1) **v2** monthly run limit via `auth/TierEnforcementService` + `app_user.personal_run_count`; (2) **v3** lifetime free credits via `LifetimeCreditService` + `lifetime_run_credit_balance` (explicitly "a v2 run does NOT decrement the lifetime balance and a v3 run does NOT touch personal_run_count"); (3) **cost-proportional billing credits** charged at plan approval (`billing/`, per CLAUDE.md). The CLAUDE.md notes the v3 consumer/B2B surface split was **rolled back (V179/V185)** — yet these v3 credit classes (`EntitlementResolver`/`LifetimeCreditService`) survive and are still wired to v3 read surfaces. The result is real split-brain risk: a contributor must know *which* system gates a given path (the rate limiter + run-submit use v2 `TierEnforcementService`; the billing/entitlements UI uses v3 `EntitlementResolver`). Worth an explicit "which credit system is authoritative post-rollback?" decision in the [billing] audit — this is the kind of three-sources-disagree bug the resolver itself was created to fix (its own class doc cites "Free / 30 of 5 runs / 286% over budget" from exactly this divergence).
- 🟢 **Within its own scope `EntitlementResolver` is carefully correct**: picks the **higher rank** of (org tier, user tier) — closing the "paid user under a FREE org row renders as FREE" bug class at the resolver instead of every consumer — and has a **two-layer guard** against leaking the `Integer.MAX_VALUE` unlimited sentinel (decide effective-paid by OR-ing org+user tier, then clamp anything ≥1M to -1). Defensive + well-reasoned.

### Pricing (corrects an earlier mental model)
- 🟢 **`model_pricing` is the runtime pricing cache, seeded from YAML — not a competing source.** Grep shows it's read by `RunCostTracker` + `agent/CostEstimator` + `api/PricingController`, and written by `config/ModelPricingInitializer`. So the flow is **`application.yml model-matrix.model-pricing` → (ModelPricingInitializer seeds) → `model_pricing` table → RunCostTracker reads**. This refines the [cost.md](cost.md) note: RunCostTracker doesn't read YAML directly at call time, it reads the DB table the initializer populated from YAML at boot. YAML remains the source of truth (CLAUDE.md), the table is the hot-read mirror — no dual-source defect. (Verify the initializer re-syncs on YAML change rather than only seeding empty — a stale-row risk if pricing changes without a reseed; flag for [cost.md]/billing follow-up.)
- 🟢 Clean composite-key JPA (`@EmbeddedId ModelPricingId{provider,model}`), nullable `cachedInputCostPer1m` for cache-aware pricing.

### Credit service (🟢)
- 🟢 `LifetimeCreditService` is tidy: `@Transactional` decrement/refund/grant all cap at `lifetimeRunCreditCap` (no over-refund/over-grant past 25), paid tiers no-op, `InsufficientCreditException` drives the upgrade-or-invite nudge. Read paths `@Transactional(readOnly=true)`.

## Cross-refs
v2 counterpart (authoritative for run-submit + rate limit) → `auth/TierEnforcementService` (next this tier), [ratelimit.md](ratelimit.md). Cost-credit (billing) system → `billing/` (next). Pricing consumed by → [cost.md](cost.md) (`RunCostTracker`), `agent/CostEstimator` (Tier 1), `api/PricingController` (Tier 7). YAML pricing source + seeding → `config/ModelPricingInitializer` (Tier 7, `config/`). Tier display normalisation (MAX→Team) ties to → root CLAUDE.md tier ladder.
