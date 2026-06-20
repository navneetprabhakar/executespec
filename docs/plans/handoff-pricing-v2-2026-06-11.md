# Handoff: Pricing v2 — Full Session Record, 2026-06-11

**Branch:** `feature/pricing-v2-launch`  
**Worktree:** `.claude/worktrees/pricing-v2-launch`  
**Final commit:** `962aaa9d`  
**Status:** Historical handoff from another worktree. The branch/session claimed complete + verified and not pushed; verify current branch state before using it as launch truth.

---

## 00 · What this session was

A single continuous session that moved through four distinct phases:

1. **Feasibility assessment** — Is the business still viable with DeepSeek V4? (Code-true, no PRD.)
2. **Pricing strategy + analysis** — How should the ladder be set? Projections, investor materials.
3. **Decision-making + delegation** — Founder converges on the locked sheet via mid-session calls.
4. **Full implementation** — V239–V241 migrations, coupon wiring, all public surfaces updated.

The session referenced docs in `docs/` and `docs/reports/`, but several generated artifacts are not present in this worktree. Treat this as a historical handoff, not a live artifact index.

| Document | What it is |
|---|---|
| `docs/business-feasibility-2026-06-11.md` | Referenced by the handoff, not present in this worktree. |
| `docs/pricing-strategy-2026-06-11.md` | Referenced by the handoff, not present in this worktree. |
| `docs/reports/investor-slide-2026-06-11.{pdf,png}` | Referenced by the handoff, not present in this worktree. |
| `docs/reports/pricing-strategy-2026-06-11.pdf` | Referenced by the handoff, not present in this worktree. |
| `docs/reports/business-feasibility-2026-06-11.pdf` | Referenced by the handoff, not present in this worktree. |

---

## 01 · Phase 1 — Feasibility assessment

### The prompt
> "Do a fresh product and business feasibility assessment, don't rely on PRD for cost assessment, we have introduced deepseek v4 flash and pro which are generating similar results as sonnet 4.6 in benchmarks at almost 80% discount."

### Method
Built from code-true sources only: `application.yml` model-matrix rates, Flyway-seeded tier rows, measured eval costs from `scripts/v2-eval-results/`, reason-log RL entries, and provider rate cards fetched 2026-06-11. No PRD numbers.

### Key findings

**The cost premise is verified — and understated:**
- DS V4 Pro: $0.435/$0.87 per Mtok input/output vs Sonnet 4.6 $3.00/$15.00 → 85.5%/94.2% cheaper
- DS V4 Flash: $0.14/$0.28 → 95.3%/98.1% cheaper
- "~80% discount" understates it. On actual run token shapes: **Pro ≈ 92% cheaper/run, Flash ≈ 97%**
- At DS routing, COGS for a fully-utilized PRO subscriber: **$1.70–$5.00/month** vs $10.92 all-Anthropic

**The parity half is unproven internally:**
- External vendor claims: 80.6% SWE-bench-Verified (V4-Pro-Max)
- NIST CAISI independent eval (May 2026): 74% SWE-V vs GPT-5.5 81% / Opus 4.6 79%; 44% vs 78% on held-out PortBench — "lags frontier ~8 months, but most cost-efficient on 5 of 7 suites"
- Internal: TB first-20 = 12/20 (60%) on Flash for $0.40. But: SWE mixed10 (pre-V224/V231/V235) had Flash at 1/10 vs Sonnet 6/10. No same-slice Sonnet baseline yet.
- Gate: `infra/benchmark/scripts/run-tb-gated-sweep.sh` (Flash → Pro → both >50% → Sonnet) must run before any managed-key default flip

**Two stale YAML pricing rows found** (chips: task_f4aace89 — these cause RunCostTracker to misreport costs on DS runs):
- `deepseek-v4-pro-native`: YAML $0.27/$0.027/$1.10 vs actual **$0.435/$0.003625/$0.87**
- `deepseek-v4-flash-native`: YAML $0.10/$0.02/$0.20 vs actual **$0.14/$0.0028/$0.28**

**Verdict:** Feasibility profile inverted. Binding constraints are no longer unit economics; they are (1) proving parity on our harness before flipping defaults, and (2) distribution.

---

## 02 · Phase 2 — Pricing strategy, projections, investor slide

### The prompt
> "Help me strategise pricing, I think I am a bit lost in pricing, I also have to build projections for 1000 paid signups distributed across all tiers and then prepare an investor slide — keep it pdf"

### What was built

**Strategy document** referenced by the historical session, not present in this worktree, covered:
- **Diagnosis**: the ladder was ~80% right; three specific gaps (power rung, value story, DS dividend allocation)
- **5 pricing principles**: run = value metric; price ≠ cost; discount with levers never list; BYOK is trust wedge not lost revenue; spend DS dividend on quality + funnel
- **The locked ladder** (see §03)
- **Full 1,000-paid projections** (base / conservative / upside / stress scenarios)
- **Mistake register M1–M9** documenting past pricing errors in the codebase
- **Decision sheet D1–D12** with owner + gate for every choice

**Projections at 1,000 paid — base case:**

| Line | MRR |
|---|---|
| PRO (720 accounts, blended $19.49) | $13,084 |
| TEAM (250 orgs × 3.0 seats × $39) | $29,250 |
| ENTERPRISE (30 × $750) | $22,500 |
| Top-ups | $700 |
| **Revenue** | **$65,534** |
| LLM COGS + FREE funnel + infra + fees | −$7,750 |
| **Gross profit** | **$57,784** |
| **Gross margin** | **≈ 88%** |

Scenarios: conservative $511k ARR / 85% GM · upside w/ PRO+ $1.22M ARR / 90% GM · stress $501k / 76%.

**$20 reprice cost**: ~$71k ARR vs the $29 ladder at identical mix. Break-even: conversion runs ~1.2× better at the $20 anchor.

**Investor slide** referenced by the historical session, not present in this worktree, was described as a single page containing product thesis, moat, unit economics, the 1,000-paid base model, the DS dividend story, and a round-ask placeholder.

---

## 03 · Phase 3 — Decision-making conversation

### PRO+ $99 question
The founder asked: "Explain the $99 PRO logic — will they not expect higher models?"

**Answer given:** PRO+ is *not* a model upgrade — it's a quota + cap upgrade. The model at PRO $20 is already the flagship (Sonnet 4.6). What a heavy user can't buy at PRO is *more runs with managed keys*. PRO+ $99 = 150 runs + $15 cap lifted + parallel execution + priority queue. At DS routing, serving 150 runs costs $8–25/user even at full use — 92% GM. This is the standard SaaS volume tier ($20 → $99 → $400+); the naming trap is calling it "Max" (retired V165 name) or implying a model upgrade exists.

**Gate conditions before launching PRO+:**
1. DS parity verified (gated sweep pass)
2. ≥15% of PRO users hit 30-run quota in a month OR buy ≥2 top-up packs

### PRO/PRO+ deep dive
The founder asked for a full breakdown: "how it will work, what are your suggestions regarding pricing, models, caps? I want to understand the good / bad and ugly."

**Summary of analysis delivered:**

| Dimension | PRO $20 | PRO+ $99 |
|---|---|---|
| **Runs** | 30/mo | 150/mo |
| **Model** | Sonnet 4.6 (same as PRO+) | Sonnet 4.6 (same) |
| **Cap** | $5/run | $15/run |
| **Execution** | Sequential | Parallel slots |
| **Queue** | Standard | Priority |
| **COGS floor** (DS hybrid) | $1.70–5.00/mo | $8–25/mo |
| **GM at full use** | 75–92% | 75–91% |

**Good:** Huge quota gap between PRO (30) and TEAM (unlimited BYOK) — PRO+ fills the hole for power users who want managed keys. 150 runs × $0.167 COGS = $25 worst-case; $99 − $25 = $74 contribution even at full use. The tier pays for itself at 5 users.

**Bad:** PRO+ is a hidden tier until demand gate fires — it can't generate demand while hidden. The right trigger is a "you've hit your limit" upgrade CTA at the plan gate (D8 in the queue).

**Ugly:** If DS parity fails, HIGH-band 150-run PRO+ users would cost ~$225 all-Anthropic at full use — negative margin. This is why the DS parity gate is non-negotiable before PRO+ launch.

### Founder delegation
> "I am not a business or sales guy, I am looking up to you to fix my mistakes."
> "You can choose, I want to close Stripe work in next 2 days as well."

Response: I chose. Firm decisions delivered (not options), covering every item on the D-sheet. The founder's only remaining jobs are the Stripe/Razorpay ops tasks (see §06).

### Mid-session pricing revisions (final locked sheet emerges)

The session went through three rounds of founder input:

| Round | Change |
|---|---|
| Initial | FREE 15 runs → founder asked "5/10 runs"; I chose **10** (5 was tried pre-V200 and reverted as too restrictive) |
| Second | PRO $29 → **$20** (removes the "pricier than Cursor" objection pre-launch) |
| Third | "Remove the INR discount, it's hurting us" + confirmed ₹95/$ basis → **INR at parity**, discount retired |

---

## 04 · The locked pricing sheet (D1–D12 canonical record)

The historical session said the full record lived in a pricing-strategy doc that is not present in this worktree. Summary:

| Tier | USD | INR | Quota | Notes |
|---|---|---|---|---|
| FREE | $0 | — | 10 runs/mo | $3/run cap. Acquisition. |
| PRO | $20/mo · $199/yr | ₹1,899/₹18,990 | 30 runs/mo | $5/run cap. Managed keys. |
| PRO+ | $99/mo · ₹9,399 | (hidden) | 150 runs/mo | $15 cap. Gated — demand trigger. |
| TEAM | $39/seat · $389/yr | ₹3,699/₹36,990 | Unlimited | BYOK, 1–20 seats. |
| ENTERPRISE | custom | custom | Unlimited | Model matrix override, governance. |

**Top-ups (tapered):** 5/$10 · 10/$18 · 20/$32 (₹949/₹1,699/₹2,999)  
**Founder coupon:** FOUNDER50_LIFETIME — 50% off lifetime, first 100 = **$10/mo** (₹949)  
**Visible pricing page:** 3 cards + Enterprise strip. Annual is a toggle. PRO+ hidden.  
**Post-launch rule:** the list only moves up. This is the last free price move.

---

## 05 · Phase 4 — Implementation

### What shipped in commit `962aaa9d`

**Migrations (V239–V241)**

| File | What |
|---|---|
| `V239__pricing_v2_launch_reseed.sql` | Deactivates $29-era rows (pinned by tier+period+amount — **critical**: V185 renamed Solo→Pro, so name pins match nothing); inserts 8 new active rows (PRO/TEAM × monthly/yearly × USD/INR); carries limits via join. |
| `V240__free_tier_10_runs.sql` | FREE 15→10 across `tier_config` + `billing_plan` + `billing_plan_limit` (V200 pattern). |
| `V241__topup_taper_inr_parity.sql` | Deactivates all active top-up packs; inserts 3 tapered USD + 3 INR-parity rows. |

**Backend**

| File | Change |
|---|---|
| `BillingService.java` | Wires founder coupon via `FounderPricingService.tryAllocate` — allocates only when the resolved gateway has its coupon/offer id; passes through to `createCheckoutSession` (Stripe) and `createSubscription` (Razorpay). |
| `PaymentGateway.java` | Default 6-arg `createCheckoutSession(..., couponId)` delegating to 5-arg. |
| `BillingGateway.java` | Default 7-arg `createSubscription(..., seats, offerId)` delegating to 6-arg. |
| `StripePaymentGateway.java` | 6-arg override applies coupon via `SessionCreateParams.Discount`. |
| `RazorpayGateway.java` | 7-arg override fixes `isYearlyPeriod` (`"YEARLY" OR "ANNUAL"`) — **was a real billing bug**; passes `offer_id`. |
| `RazorpayRestClient.java` | 6-param `createSubscription` appends `offer_id` when non-blank. |
| `PlanSeederService.java` | Same YEARLY/ANNUAL period fix for Razorpay plan minting. |
| `TierEnforcementService.java` | `FALLBACK_FREE = 10` (was stale 5), `FALLBACK_PRO = 30` (was stale 20). |
| `BillingServiceStripeTest.java` | Added `FounderPricingService` mock (23rd ctor arg); 6-arg checkout stub. |

**Frontend**

| File | Change |
|---|---|
| `frontend/src/ide/IdeBilling.tsx` | FEATURES map rewritten — old had pre-V176 "HIDDEN/grandfathered" PRO, invented "500 credits/seat"/"$25 hard cost cap" TEAM, "SSO+SCIM"/"99.9% SLA" ENTERPRISE. Now truthful v2. |
| `frontend/src/signal/pages/ExtraPages.tsx` | Tier cards, runs-language intro, TEAM annual line. |
| `frontend/src/ide/IdePlan.tsx` | Plan gate copy at v2 runs-language. |
| `frontend/src/signal/pages/AuthPages.tsx` | "10 runs" copy. |
| `frontend/src/ide/UsageMeter.tsx` + `UpgradeModal.tsx` | Historical note from this handoff; these files are not present in the current worktree. |

**Website + docs**

| File | Change |
|---|---|
| `website/index.html` | Price cards ($20/₹1,899, $199/yr, TEAM $389/yr), Credit glossary rewritten to $5 truth, FAQ schema updated, Product schema price corrected, "10 free runs" in hero, library cards "N credits"→"N runs". |
| `website/llms.txt` | 3 blocks at v2 sheet (incl. TEAM "$389/seat/year — 17% off"). |
| `CLAUDE.md` | Migration head now V247 in the current worktree; tier-ladder at v2. |
| `docs/reason-log/2026-05.md` | RL-2026-06-022 entry. |
| `docs/document_map.md` | New rows; playbook row annotated "pre-date pricing v2". |

---

## 06 · Bugs found and fixed

### Bug 1: YEARLY-vs-ANNUAL Razorpay billing bug (production impact)
**Files:** `RazorpayGateway.java`, `PlanSeederService.java`  
**What:** `isYearlyPeriod()` checked only `"ANNUAL".equals(period)`. Every yearly row in the DB stores `"YEARLY"`. Result: every INR yearly plan would have been minted as a *monthly* Razorpay plan at the yearly price — subscribers billed ₹18,990 or ₹36,990 *monthly* instead of annually.  
**Fix:** `"YEARLY".equalsIgnoreCase(period) || "ANNUAL".equalsIgnoreCase(period)`

### Bug 2: V239 name-pinned deactivations matched nothing (seeding trap)
**What:** V185 renamed `billing_plan` rows from "Solo…" to "Pro…". The first V239 draft pinned deactivations by name (`name LIKE 'Solo%'`) — matched nothing, leaving $29 rows active alongside new $20 rows. Checkout would have resolved to an ambiguous active row, likely wrong (or deterministic but wrong).  
**Fix:** Deactivations now pin by `(tier, period, base_amount_usd)` — immune to name changes. Caught only by fresh-DB psql audit, not unit tests.

### Bug 3: IdeBilling FEATURES overclaims (escaped RL-2026-06-019 truth-pass)
**What:** The in-app billing modal showed PRO as "HIDDEN — grandfathered" (internal note leaked to users), invented TEAM as "500 credits/seat" and "$25 hard cost cap", ENTERPRISE as "SSO + SCIM" + "99.9% SLA" (neither built). None were true.  
**Fix:** FEATURES map fully rewritten to v2 truth.

### Bug 4: TierEnforcementService stale fallbacks
**What:** Fallback quotas if `tier_config` lookup fails: `FALLBACK_FREE = 5` (should be 10 since V200), `FALLBACK_PRO = 20` (should be 30 since v2 reseed). Under-enforced quota on cache misses.  
**Fix:** Corrected to 10 and 30.

---

## 07 · Verification

1. **`mvn verify` — 1,704 tests green** (two test fixes required: `BillingServiceStripeTest` 23-arg constructor + 6-arg mock stub)
2. **`tsc`, `vite build`, `eslint`, `prettier:check`** — all clean
3. **Fresh-DB Flyway** — throwaway Postgres, applied all 241 migrations V1→V241, booted on `:8090` with `-Dspring-boot.run.profiles=local`
4. **psql row audit confirmed:**
   - Exactly one active plan per tier/period/currency (8 rows total)
   - Pro Monthly USD $20 · Pro Yearly USD $199 · Pro Monthly INR ₹1,899 · Pro Yearly INR ₹18,990
   - Team Monthly USD $39 · Team Yearly USD $389 · Team Monthly INR ₹3,699 · Team Yearly INR ₹36,990
   - `tier_config` FREE=10, PRO=30
   - 6 active top-up packs (3 USD + 3 INR)
   - `founder_pricing_discount` row intact, gateway ids empty (awaiting ops)
5. **Website screenshots at 375px** — cards render correctly; pre-existing overflow on React /pricing page chipped separately (task_816aeb8c — predates this change)

---

## 08 · Merge warnings

### V239 collision with `codex/review-fixes-docs-2026-06-10`
That branch carries its **own V239** (different content — benchmark-related). Whichever branch merges second must **renumber its V239 to V242** (or the next available number at merge time). Documented in commit message and RL-2026-06-022.

### June reason-log rotation
The codex branch also contains the June reason-log rotation (creating `2026-06.md`, repointing "current-log" references). RL-2026-06-022 from this session was added to `2026-05.md` to avoid the conflict. After both branches merge, verify the rotation is complete.

---

## 09 · Ops checklist — Stripe + Razorpay (2-day window)

### Stripe dashboard (dashboard.stripe.com)
1. Create price: **PRO Monthly USD** → $20/month, recurring (Product: "ExecuteSpec Pro")
2. Create price: **PRO Yearly USD** → $199/year, recurring
3. Create price: **TEAM Monthly USD** → $39/seat/month, recurring (may exist via env `STRIPE_TEAM_MONTHLY_PRICE_ID` — check)
4. Create price: **TEAM Yearly USD** → $389/seat/year, recurring, per-seat
5. Create coupon: ID = `FOUNDER50_LIFETIME`, **50% off**, duration = **forever**, max redemptions = **100**
6. **Send the 5 IDs** → I wire into `billing_plan.stripe_price_id` columns + `founder_pricing_discount.stripe_coupon_id` (tiny V242 migration or env vars)

### Razorpay dashboard (dashboard.razorpay.com)
1. Confirm subscriptions are enabled for the account
2. Create a **50% discount subscription offer** applicable to all plans
3. **Send the `offer_id`** → goes into `founder_pricing_discount.razorpay_offer_id`
4. **Do NOT manually create INR plans.** After deploy, hit `POST /api/admin/billing/seed-plans` (SUPER_ADMIN) → `PlanSeederService` auto-mints all 4 INR plans at the correct period and amount. The YEARLY period bug is fixed, so this is safe.

### Webhooks (verify both are configured)
- **Razorpay:** `subscription.charged`, `subscription.cancelled`, `subscription.halted`, `payment.failed`
- **Stripe:** `customer.subscription.updated`, `customer.subscription.deleted`, `invoice.payment_succeeded`, `invoice.payment_failed`

### Test cards (smoke after wiring)
- Razorpay: `5500 6700 0000 1002`
- Stripe: standard `4242 4242 4242 4242`

---

## 10 · Deferred engineering queue (from D-sheet)

| ID | Item | Gate |
|---|---|---|
| D7 | Auto-routing flip — default provider Anthropic→DeepSeek | DS parity eval pass (gated-sweep + BUILD-mode v2-eval DS column) |
| D8 | Cap-at-gate upgrade CTA — show upgrade prompt when `userCostCap` near-capped | Any sprint |
| D9 | Estimator recalibration — `GET /api/projects/{id}/estimate` vs `run_cost_log` actuals | Any sprint |
| D10 | 5-number pricing dashboard — MRR, runs/user, COGS/run, GM%, churn | Any sprint |
| PRO+ | Scaffold + launch | DS parity pass + ≥15% of PRO quota-hitting |
| TEAM managed keys add-on | +$15/seat, 40 runs/seat | Demand signal from BYOK-friction funnel analysis |

---

## 11 · Open chips

| Chip | Description |
|---|---|
| task_f4aace89 | Stale DS pricing rows in `application.yml` — `deepseek-v4-pro-native` and `deepseek-v4-flash-native` YAML rates are $0.27-era vintage; RunCostTracker misbills DS runs until fixed |
| task_816aeb8c | Pre-existing `/pricing` React page horizontal overflow at 375px — not caused by this change; `cols-4-xl` appears to be a dead Tailwind class |

---

## 12 · Next session startup

```bash
# Worktree is live at:
cd /Users/navneetprabhakar/Documents/antigravity/executespec/.claude/worktrees/pricing-v2-launch

# If cleaned up, re-enter from the main tree:
cd /Users/navneetprabhakar/Documents/antigravity/executespec
git worktree add .claude/worktrees/pricing-v2-launch feature/pricing-v2-launch

# After founder says "push":
git push -u origin feature/pricing-v2-launch
gh pr create --base dev --title "feat(pricing): pricing v2 launch — \$20 PRO, FREE 10 runs, INR parity (V239–V241)"

# After Stripe/Razorpay IDs arrive:
# → V242 migration: UPDATE billing_plan SET stripe_price_id = '...' WHERE ...
# → UPDATE founder_pricing_discount SET stripe_coupon_id = '...', razorpay_offer_id = '...'
# → Boot locally, smoke with test cards
# → POST /api/admin/billing/seed-plans (after deploy, to mint INR Razorpay plans)
```

---

## 13 · If you were building this fresh

The biggest recurring cost in this session was **pricing truth-drift across 6 surfaces** (DB ↔ website ↔ llms.txt ↔ IdeBilling ↔ PublicPricingPage ↔ plan gate copy). Every truth-pass visit costs a session. Fix:

1. **Single `pricing.yml`** — tiers × period × currency × quotas × caps × coupon defs. Seeds `billing_plan` idempotently at boot. Drives `GET /api/billing/plans`. Consumed by website + frontend at build time. A price change is one diff.
2. **Semantic key on `billing_plan`** — unique `(tier, period, currency)` partial index on `active=TRUE` + stable `plan_key` column. The V185 rename trap and V207/V239 duplicate-active bugs become impossible.
3. **Normalize `period` as a DB enum** (`MONTHLY|YEARLY`). The `"ANNUAL"` vs `"YEARLY"` string drift caused a real would-have-billed-wrong bug.
4. **Money tests on a real Flyway DB** (Testcontainers) — a single test asserting the active plan matrix would have caught the V239 seeding bug before a human psql audit was needed.
5. **Frontend pricing pages render from the API** with a build-time static snapshot. Hardcoded TSX card copies are where stale claims accumulated ("500 credits/seat", "$25 cap").
6. **CI exit code honesty** — never gate on `cmd | tail` (tail's exit code masks cmd failure). Use `set -o pipefail` or `EXIT=$?` capture. This bit me twice in this session.
7. **Sell runs, not credits, from day one.** `1 credit ≈ $5` is internal capping machinery; "30 runs/month" is the value prop. Surface the conversion only in receipts.
