# Session Handoff — 2026-04-21

_Branch: `dev` (all work merged + pushed) · Last commit: `d91761a7`_

Picks up from `session-handoff-2026-04-18.md`. This session closed the UX overhaul, fixed the Gemini provider bug, ran the full 3-provider eval matrix, and pivoted the landing + billing for a US-first Product Hunt launch.

## 1 · Commits landed today (5 on dev)

```
d91761a7 feat(pricing+launch): lock $20=20 credits + US-first pivot + PH launch kit
4214fbbe feat(provider): rename LlmProvider.GOOGLE → GEMINI + full 3-provider eval + v3 pricing
415875f5 chore(eval): refresh pricing analysis with live Apr 21 eval data
be2376eb feat(ux): v2 honest pass — adopt PageShell across 20+ pages + rewrite all 17 email templates
3eaeecc0 feat(ux): UX overhaul v1 — design system + AuthShell + responsive landing page + tier strategy + pricing analysis
```

All on `dev`, no PR to main yet (deliberate — main prod GCE env still unset; see `.github/workflows/deploy.yml`).

## 2 · Decisions locked this session

### 2.1 Credit model (v3)

**`$1 = 1 credit`, flat per complexity band.** Not per-provider.

| Band | Real cost range | Credits | User pays | Margin (best/worst provider) |
|---|---|---|---|---|
| LOW | $0.087–$0.134 | 1 | $1.00 | 91% / 87% |
| MEDIUM | $0.332–$0.482 | 1 | $1.00 | 67% / 52% |
| HIGH | $1.28–$2.65 | **4** | $4.00 | 68% / **-32% (Anthropic)** |
| Mobile | $0.67–$1.23 | 2 | $2.00 | 67% / 39% |

**Anthropic HIGH loss is treated as CAC.** Option C chosen over Option B (variable per-provider credits) to avoid the perverse incentive where users always pick the cheapest provider and erase the quality signal. Rationale in `website/tier-decision.md`.

Source of truth: `website/tier-decision.md`. Real eval data: `website/pricing-analysis.md`.

### 2.2 Landing + billing → US-first

- `frontend/src/pages/PlansPage.tsx` — USD by default. Geo-detect flips to INR for `navigator.language` starting with `en-in`/`hi` or timezone `Asia/Kolkata`.
- `website/index.html` — same geo-detect pattern. USD toggle default is unchecked; India visitors auto-flip.
- Legal copy (Terms §8, Privacy data-collection, DPA sub-processors) now reads "Stripe (US/global) · Razorpay (India)".
- PRO card copy corrected from stale "50 runs/month" to "20 credits / month · ≈ 20 LOW, 6 HIGH, or 10 Mobile runs".

### 2.3 Stripe as prod default gateway (env-gated)

`application-prod.yml` now declares:
```yaml
billing:
  default-gateway: ${BILLING_DEFAULT_GATEWAY:stripe}
  stripe:
    enabled: ${BILLING_STRIPE_ENABLED:true}
```

**Takes effect only when ops wires 5 env vars on prod GCE:**
- `STRIPE_SECRET_KEY`
- `STRIPE_PUBLISHABLE_KEY`
- `STRIPE_WEBHOOK_SECRET`
- `STRIPE_PRO_PRICE_ID`
- `STRIPE_ENTERPRISE_PRICE_ID`

Dev profile untouched — Razorpay still the local default so devs without Stripe secrets aren't blocked.

Fallback: set `BILLING_STRIPE_ENABLED=false` + `BILLING_DEFAULT_GATEWAY=razorpay` to revert to pre-session behaviour.

### 2.4 Batch APIs — declined

Assessed OpenAI Batch / Anthropic Message Batches / Gemini Batch Prediction. All three are single-shot request/response. No multi-turn tool-call loop support. Our agentic workloads (planner, sequential worker, reviewer) — which account for ~90% of run spend — are not eligible.

Only eligible workloads: `RunLedgerAgent` + `MemoryCompactionService`. At 1k runs/month, 50% discount saves ~$2-3/mo. Not worth the engineering effort.

**Real cost levers identified (not shipped here, follow-ups):**
- Anthropic prompt caching on planner + reviewer paths
- Fix Gemini 10× MEDIUM token bloat (v2.5.13 regression — see `website/pricing-analysis.md` §7)
- Tier-gate HIGH + mobile to BYOK (Option E from pricing-analysis)

## 3 · State at session end

- **Branch**: `dev`, clean working tree
- **Build**: `mvn compile -q` ✓ · `npm --prefix frontend run build` ✓
- **Branch ahead**: main is 5 commits behind dev (same as before this session — we didn't touch main)
- **Feature branch**: `feature/ux-overhaul-v1` pushed to origin, ff-merged into dev
- **Backend LlmProvider enum**: canonical `GEMINI` (legacy `GOOGLE` string accepted as input via compat shim in `ProjectService.updateSettings` + `RunRequest.aiProvider` regex + `GeminiNativeToolClient`). Post-rename verified live via Apr 21 19:10 eval — all 3 Gemini cells completed cleanly.

## 4 · Blocking for Product Hunt launch

Owner indicated in brackets. See `website/product-hunt-launch.md` §6 for the canonical list.

1. **`website/images/og-hero.png`** [Design] — placeholder shipped (Pillow-generated, 68KB). Replace before PH with a real product-hero asset. Referenced from `website/index.html:30`.
2. **6 product screenshots** [Design] — dashboard, run detail w/ verified score + SignalBreakdown, project overview tab (tech debt panel + generated docs), architecture viewer, validation report, mobile preview with QR.
3. **60-90s demo video** [Founder] — script in `website/product-hunt-launch.md §6.4`. Flow: spec → plan gate → SSE worker execution → reviewer → verified 92 score + PR → mobile QR preview.
4. **Hunter** [Founder/GTM] — someone with >500 followers + 3+ hunts in 90 days.
5. **Stripe env vars on prod GCE** [Ops] — 5 vars listed in §2.3. Until these land, flip `BILLING_DEFAULT_GATEWAY=razorpay` in prod env to revert, or leave prod untouched (dev default still Razorpay-compatible).

## 5 · Deferred / follow-up work (not blocking)

Ranked by business impact.

### 5.1 Stripe top-up checkout wiring (P1)

Today `BillingController.purchaseTopup` hardcodes Razorpay. US users buying credits hit the wrong gateway.

Scope (~4-6 files, billing-critical — test in Stripe test mode before prod):
- `TopupPurchaseRequest` — add `gateway`, `successUrl`, `cancelUrl` fields
- `TopupOrderInitResult` — make polymorphic (Razorpay order fields OR Stripe checkout URL)
- `RunTopupService.initiatePurchase` — branch on gateway, reuse `StripePaymentGateway.createTopupCheckoutSession` (already exists at `StripePaymentGateway.java:141`)
- `TopupPackage` entity — add `stripePriceId` column + Flyway migration
- `BillingService.processStripeWebhook` — handle `type=topup` metadata in `checkout.session.completed`
- Frontend: `purchaseTopup` flow + Stripe URL redirect

Not launch-blocking because Pro/Max subscriptions already work on Stripe. Only top-up credit purchases hit the Razorpay-only path today.

### 5.2 Country-aware gateway routing (P1)

`BillingEntity` interface doesn't expose `getCountry()`. `PaymentGatewayRouter.resolveDefault` can only fall back to a global default. Needs:
- `BillingEntity.getCountry()` added
- `AppUser.country` + `Organization.country` persisted (Flyway migration)
- `PaymentGatewayRouter.resolveDefault` branch: country=IN → Razorpay, else → Stripe
- Captures country on registration flow (signup form) + billing-profile edit

Until shipped, routing is "stripe by default in prod (env override), per-entity preference respected". Works but imprecise.

### 5.3 `currency-gateway-map` wired in router (P2)

`application.yml:587-591` declares `{INR: RAZORPAY, USD: STRIPE, EUR: STRIPE, GBP: STRIPE}` but `PaymentGatewayRouter` never reads it. Trivial: add `@Value("#{${billing.currency-gateway-map}}") Map<String,String>` and consult it before `resolveDefault`.

### 5.4 Stripe refund path (P2)

`BillingService.issueProratedRefund:961` has a TODO — Stripe refunds on upgrade are currently no-ops. Razorpay refunds work. User-visible consequence: someone on Stripe Pro who upgrades mid-cycle to Max gets no proration credit.

### 5.5 Gemini token bloat fix (P1 — cost lever)

From `website/pricing-analysis.md §7`: Gemini MEDIUM historical 93k → current 920k input tokens (10×). Accuracy also regressed 100 → 85. Likely causes: v2.5.13 planner-prompt growth + requirement-graph + mobile-preference-block too heavy for Gemini flash-preview.

Action: A/B the planner prompt variants on Gemini only. Keep current for Anthropic + OpenAI. Track in `docs/plans/` — not yet written.

### 5.6 Mobile accuracy floor (P1 — quality lever)

Mobile-iOS accuracy Gemini 65, Anthropic 72, OpenAI 78. All below the 80 target. Needs targeted planner tuning for RN+Expo — possibly a second prompt fragment for mobile that's not yet created.

### 5.7 ModelResolver adaptive routing bypasses strict-provider

Bug #1c from v2.5.9 eval (still open). `ModelResolver.selectAdaptiveProvider` silently switches providers based on historical success rate even when `-Dexecutespec.strict-provider=true`. Needs strict-mode guard or opt-out flag.

## 6 · Where things live

| Purpose | Path |
|---|---|
| Locked pricing doc | `website/tier-decision.md` |
| Real cost data | `website/pricing-analysis.md` |
| PH launch kit | `website/product-hunt-launch.md` |
| Press kit | `website/press-kit/README.md` |
| OG image placeholder | `website/images/og-hero.png` |
| Prod billing config | `src/main/resources/application-prod.yml:139-158` |
| Plans page (USD-default) | `frontend/src/pages/PlansPage.tsx:14-23` |
| Landing (geo-detect) | `website/index.html` end-of-file script |
| Eval results (Apr 21) | `scripts/v2-eval-results/v2-eval-20260421-{162904,191051}{,-raw}/` |
| Eval specs | `scripts/v2-eval-specs/{low,medium,high,mobile-android,mobile-ios}.txt` |

## 7 · Session rules + memory updates

New rule added to user memory: [feedback_no_coauthor_in_commits.md](../../memory/feedback_no_coauthor_in_commits.md) — do not add `Co-Authored-By: Claude` trailers to commits. Applied to this session's single commit `d91761a7` and going forward.

Existing rules still in force (quick ref):
- Ask before every `git push`
- `feature/*` → `dev` → `main`, never commit directly to `dev`
- `ls V*.sql` before creating new Flyway migrations
- Never modify an applied Flyway migration
- Confirm major/minor/patch before version bumps
- V2 uses "gemini" not "google" (now enforced by the enum rename)
- `git pull --ff-only` before branching

## 8 · For the next session

Likely first asks:
- **"Did PH traffic convert?"** — look at GA4 `G-Y6JSSQ2Y83` + `run_cost_log` + `billing.subscribe` payloads by gateway for Day 0/1/7. Success metrics table in `website/product-hunt-launch.md §9`.
- **"Ship Stripe top-up"** — scoped in §5.1 above.
- **"Why are we losing money on Anthropic HIGH?"** — intentional, see §2.1. If user wants to revisit: Option B (variable credits) is the alternate, 10 LOC change in `CostEstimator.estimateRunTierAware`.
- **"Mobile accuracy still at 70?"** — §5.6, planner prompt work.

Context I'd load first next session: `website/tier-decision.md`, `website/pricing-analysis.md §4`, this handoff. 3 files covers the whole pivot.
