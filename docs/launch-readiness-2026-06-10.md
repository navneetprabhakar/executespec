# Launch readiness — truth pass + remaining ops steps (2026-06-10)

> Companion to RL-2026-06-019. The repo-side "truth pass" (pricing/claims unified across every
> public surface) is DONE — this doc tracks what it changed and the steps that can only happen
> outside the repo (prod DB checks, coupons, deploys, assets). Work through the checklist top to
> bottom before announcing.

## What the truth pass fixed (repo-side, complete)

| Surface | Was | Now |
| --- | --- | --- |
| `website/index.html` (marketing) | SOLO tier (retired name), invented limits (200/500 credits, $10/$25 caps), "Start 14d trial" (no trial exists), "Credits never expire" (90-day top-up expiry), "signed by ExecuteSpec-bot" (no system PAT), SOC 2 / SSO+SCIM / 99.9% SLA claims, every CTA → `dev.executespec.ai` | DB-true 4-tier ladder (Free / **Pro $29** / Team $39 / Enterprise), honest claims, CTAs → `app.executespec.ai` |
| `website/llms.txt` (LLM crawlers) | 3rd-generation stale: $20 Pro, 5-run FREE, 2-seat minimum, "~80% verified score measured", `/v3/` routes, old multiplicative score formula | Full rewrite, current ladder + additive score + honest compliance line |
| App `PublicPricingPage` | 3 tiers (Pro missing!), "$31 yearly" for a TEAM-yearly plan that has no active billing row | 4 tiers from DB truth + one-line credits explainer ("1 credit ≈ $5, charged at approval, refunded on failure") |
| App `TrustPage` | "SOC 2 Type II — Ready", NDA SOC 2 report row, "Penetration test report · 2026", SSO+SCIM | Honest security story (8-layer sandbox, guards pinned by tests, no-training) + "SOC 2 on the roadmap, ask us" |
| DPA + Terms + admin Compliance card | "SOC 2 controls (audit underway), ISO 27001 alignment", "request a SOC 2 report annually", "Credits never expire", green "Type II ready" pill | True security measures; cycle-reset + top-up-validity billing language; amber "On the roadmap" pill |
| `frontend/index.html` | `<title>ExecuteSpec</title>` only — shared verified-report links unfurled blank | Meta description + full OG/twitter tags (og-hero.png) |
| Onboarding | `/welcome` sandbox picker unreachable post-signup; GitHub-PAT gap discovered at PR-push time; compose cap said PRO max $25 (backend: $5) | `IdeWelcome` empty state carries starter samples inline; PAT pre-flight warning on compose; cap mirrors `resolveRunCostBudget` |
| Proof assets | SWE-bench brief presented as canonical (59%/52%) | Marked **SUPERSEDED** — numbers invalid (untracked LIGHTWEIGHT-tier worker routing); do not quote until the rebuilt-harness re-run lands |
| PH launch kit | Apr-21 vintage ($20 Pro / $29 MAX, 5-run free, old score formula) | Rewritten current (`docs/marketing/product-hunt-launch.md`) |

## Ops checklist (cannot be done from the repo)

1. **Verify the prod tier ladder is what the migrations say.** `GET /api/billing/plans` on
   `app.executespec.ai` must return Free + Pro Monthly/Yearly (USD + INR) + Team Monthly (USD + INR).
   Runtime lifecycle flips via `PUT /api/admin/tiers/{tier}` could have diverged from the migration
   trail — if PRO is missing, re-list it via the admin API (never a direct DB write).
2. **Confirm `app.executespec.ai` is live and serving the current build** — every marketing CTA now
   points there (was `dev.executespec.ai`). Do NOT deploy the website until signup on the prod app
   works end-to-end. Note `deploy.yml` auto-deploys only on `dev` pushes; prod deploy is the manual
   path in `docs/DEPLOYMENT.md`.
3. **Deploy `website/`** (wrangler) after #2. Then verify social unfurl: `https://executespec.ai/images/og-hero.png`
   resolves, and a `/shared/<token>` app link previews correctly in Slack/Discord (opengraph.xyz).
4. **Founder pricing go-live (recommended launch lever — currently dormant).** V177 seeded
   `FOUNDER50_SOLO_LIFETIME` (50% off lifetime, first 100, tier PRO) but `stripe_coupon_id` /
   `razorpay_offer_id` are NULL and the `BillingService.subscribe` consumption wiring was a
   P2.4 follow-up. Before advertising "first 100 get 50% off forever": create the Stripe coupon +
   Razorpay offer, populate the row, and verify a test subscription actually gets the discount.
5. **The three trust-bug engineering gates** (in flight in the review-fix sessions — launch blocks
   on them): real-test scoring silently degrading to LLM-judged on Gradle/quiet-Maven projects;
   OpenRouter-routed runs recording $0 actual cost; the Stripe checkout-session activation bug.
6. **Minimal ops story:** wire Grafana alert destinations (the Telegram bot is already running) for
   the 5 SLOs; stand up a hosted status page or decide explicitly to launch with support-email-only
   (`docs/status-page.md` confirms none is implemented).
7. **Proof assets for the announcement:** record the 60–90s demo video (script in the PH kit §6);
   run 5–10 real specs against known OSS repos and publish the shared verified reports as a small
   gallery — this replaces the retired SWE-bench numbers as launch proof.
8. **Secondary copy sweeps (non-blocking):** `docs/marketing/pricing-analysis.md` + `docs/marketing/tier-decision.md`
   + `website/press-kit/README.md` still carry pre-V176 numbers; refresh or mark historical.
9. **Do not quote any SWE-bench number** (59%/52% or the 7%→67% uplift) anywhere until the
   rebuilt-harness re-run lands (RL-2026-06-001 / `eval_methodology_rebuild`).
