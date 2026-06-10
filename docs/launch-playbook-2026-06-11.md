# ExecuteSpec Launch Playbook — 2026-06-11

> The single founder reference for the launch push. Every number and claim below was verified
> against code, migrations, or a live walk on 2026-06-10/11 (RL-2026-06-019 + RL-2026-06-020).
> Companions: [`launch-readiness-2026-06-10.md`](launch-readiness-2026-06-10.md) (ops checklist
> detail) · `review/07` + `review/11` (positioning + UX verdicts, local) ·
> [`website/product-hunt-launch.md`](../website/product-hunt-launch.md) (PH kit, refreshed).
> A styled PDF of this doc lives at `docs/reports/launch-playbook-2026-06-11.pdf`.

---

## 1 · Where you stand (TLDR)

You are **not lacking product** — you're finishing a consistency-and-proof push. As of today:

| Track | State |
| --- | --- |
| Public claims (pricing, compliance, capabilities) | ✅ **Truthful + unified** across marketing site, llms.txt, app pricing/trust/legal (commit `1be342eb`) |
| UX wave 1 (review/11 defects) | ✅ Shipped + live-verified at 375px/desktop (commit `26ab3f66`) |
| Trust-number bugs (score degradation on Gradle, OpenRouter $0 cost, Stripe checkout) | 🔶 In flight in the review-fix sessions — **the only engineering launch gates** |
| Benchmarks | 🔶 Re-running on the rebuilt harness (2 days); ALL public surfaces are number-free until then |
| Console-as-default chrome | ⏳ **Your decision** — preview via the "Try Console ⤴" status-bar button |
| Ops (prod URL check, coupons, alerts, status page) | ⏳ Checklist in §7 |

**The one-sentence thesis:** you built a trust machine; launch is the moment every surface says
the same true thing about it.

---

## 2 · The story

**One-liner (use everywhere, verbatim):**
> AI that ships pull requests your team actually merges.

**The frame:** between copilots (you drive every keystroke) and full-autonomy agents (black box,
surprise bills) sits **delegation with a contract** — approve the scope and the price up front,
get back a PR with a receipt.

**Three pillars, in order:**
1. **Plan gate** — you approve the plan *and the price* before a token is spent. Credits charge at
   approval, refund on failure.
2. **Verified score** — deterministic 0–100 from real signals (code delivered 30 · build 25 ·
   your own tests 20 · reviewer 20 · criticals 5). Never LLM self-rating. This is the brand.
3. **Your repos, your keys** — real PRs from your GitHub identity; BYOK with zero per-run markup.

**Vocabulary discipline** — say: *verified score, plan gate, spec, run, receipt, merge-able PR*.
Don't say: *SOC 2 (until certified), trial (none exists), Solo (retired name — it's Pro),
ExecuteSpec-bot (PRs use the caller's PAT), workspace/Explorer jargon in marketing copy*.

**Lead / support / hide:**
- **Lead:** spec → plan gate → verified PR loop; the score; BYOK.
- **Support:** readymade library + migration recipes (an aisle, not the store); autofix surfaces;
  project workbench (architecture docs, tech debt, memory).
- **Hide / beta-label:** benchmarks dashboards (operator-only), Terminal-Bench/SWE-bench adapters,
  CLI + VS Code extension ("beta"), pause/resume, deployments, standards/contracts/ADRs (sell as
  "Enterprise governance", don't demo).

---

## 3 · Packaging (the canonical ladder — matches the DB)

**The metering sentence (repeat verbatim on every surface):**
> A run costs credits — **1 credit ≈ $5 of model spend** — charged only when you approve the plan,
> refunded if the run fails.

| Tier | Price | What it really includes | Caps |
| --- | --- | --- | --- |
| **FREE** | $0 | 15 runs/mo · small (1-credit) specs · 1 project · 1 repo · managed keys | $3/run |
| **PRO** | **$29/mo** or **$289/yr** (−17%) · ₹2,499/mo | 30 runs/mo · managed keys (Anthropic/OpenAI/Gemini) · PR review + code review (4 scans/mo) · 2 projects × 2 repos | $5/run — BYOK lifts it |
| **TEAM** | **$39/seat/mo** · ₹3,399 · from 1 seat · **monthly only** | BYOK (your keys, zero markup) · unlimited runs · 5 projects · cross-repo · sharing · unlimited code reviews · autofix | none (org spend caps + approval thresholds available) |
| **ENTERPRISE** | custom | Team + custom MSA + signed DPA · model-matrix override + per-run pins · OpenRouter gateway · standards/contracts/ADRs · audit trail · repo allowlists | none |

Facts that bite if forgotten: **Pro is LISTED** (V176; V185 only retired the "Solo" *name*).
**Team yearly does not exist** (V165 deactivated the $49-era rows; nothing replaced them).
**Top-ups expire in 90 days** by default. **No trial mechanics exist.**
**Founder pricing** (V177): 50% off **for life**, first **100 Pro** subscribers — seeded but
**dormant** until ops creates the Stripe coupon + Razorpay offer AND the subscribe-path wiring is
verified with a test purchase. This is your launch lever; don't advertise before it's live.

Enterprise hard rules (from `docs/enterprise-one-pager.md`): do **not** claim SAML end-to-end,
SOC 2 complete, dedicated region, or hard uptime SLAs.

---

## 4 · The truth pass (what changed, where)

| Surface | File | Was → Now |
| --- | --- | --- |
| Marketing site | `website/index.html` | Catalog-soup title → hero line; SOLO→PRO card with DB-true bullets; fictional "500 credits / $25 cap / 14d trial / never-expire / ExecuteSpec-bot / SOC 2 / SSO+SCIM / 99.9% SLA" all removed; **every CTA `dev.` → `app.executespec.ai`** |
| LLM-crawler file | `website/llms.txt` | Full rewrite (was $20 Pro, 5-run free, 2-seat min, "~80% verified score", `/v3/` routes) |
| App pricing | `ExtraPages.tsx` `PublicPricingPage` | 3 hardcoded tiers (no Pro, fake "$31 yearly" Team) → 4 DB-true tiers + the metering sentence |
| App trust page | `ExtraPages.tsx` `TrustPage` | SOC 2 "Ready"/pen-test rows → honest 8-layer/sandbox/guards story + "SOC 2 on the roadmap, ask us" |
| Legal | `LegalPages.tsx` | DPA §3/§6 SOC 2/ISO claims → true measures; Terms "credits never expire" → cycle reset + top-up validity |
| App SEO/sharing | `frontend/index.html` | Zero meta tags → full description + OG + twitter (shared verified reports now unfurl) |
| Login panel | `AuthLayout.tsx` | "BYOK bypasses the cost cap" insider bullet → "Deterministic verified score on every PR" |
| Proof | `docs/reports/swebench-brief-*` | Marked **SUPERSEDED — do not quote** (worker model untracked on the old harness) |
| GTM kit | `website/product-hunt-launch.md` | Rewritten current (tiers, score formula, honest "hard truths", asset status) |

---

## 5 · UX

**The verdict (review/11, and your instinct):** the screens are competently built; the **VS Code
frame** and leaked internals are what feel bad. The web app's real job is mission control
(submit → approve → watch → read verdict → open PR), not editing.

**Wave 1 — shipped (commit `26ab3f66`), verified live:**
raw `CLEAN_COMPLETION`-style enums humanized · relative timestamps with absolute-on-hover ·
⌘K hint off touch · OpenAI/OpenRouter casing · "Recommended (default)" model label ·
Production-vs-Prototype explainer · Pro cap constant $25→$5 · status-bar tier chip readable +
375px overflow fixed + "IDE · Beta" deleted · diff lines subtle tints · first-run starter samples
on the empty workspace · GitHub-PAT pre-flight at compose · **verified score now on the run-list
wire** (was reading a field that never existed) with 4-band badges on run cards.

**The pending decision — Console as default.** Preview both yourself: status bar → **Try Console ⤴**
(or `localStorage es:console='1'`; remove the key to go back). If you say go, wave 2 executes
reframe Phases 2–4: Home depth ("needs your attention", onboarding) → mobile pass → flip default +
retire the IDE shell (keep the editor metaphor only inside run-result Code tabs and the VS Code
extension). ~1 focused week.

**Wave 2 priority order:** ① Console flip · ② notification spam (auto-read on view + digest — a
fresh account shows "9+" unread) · ③ typography split (mono for data, humanist sans for prose) +
ONE accent (kill coral-as-link; it collides with the score scale's red) · ④ result-page action
hierarchy (10 equal buttons → primary Push/PR + overflow; Delete away from primary) + tab merge ·
⑤ provider-first removal from compose (rides the model-taxonomy rework in review docs 09/10).

---

## 6 · Proof policy (benchmarks re-running)

- **Quote no benchmark numbers anywhere** — internal or external — until the rebuilt-harness run
  lands. The old 59%/52% (and the 7%→67% uplift) are invalidated: the old harness silently routed
  LOW-complexity work to the LIGHTWEIGHT tier, untracked.
- **Launch proof instead:** run 5–10 real specs against known OSS repos and publish the public
  shared-report links as a gallery. The verified report IS the demo. (OG tags now make those links
  unfurl properly.)
- **When the new numbers land:** refresh `docs/reports/swebench-exec-brief.*` + its how-to-read
  banner; optionally add an honest line to `website/llms.txt` + PH kit §4 ("measured blind through
  the production pipeline, N=…, CI=…"). Honest small-N framing only: "competitive", never "beats".

---

## 7 · Ops before announcing (gates bolded — full detail in launch-readiness doc)

1. **Verify prod sells the right ladder** — `GET /api/billing/plans` on `app.executespec.ai` must
   return Free + Pro Monthly/Yearly + Team Monthly (both currencies). Fix via
   `PUT /api/admin/tiers/{tier}`, never SQL.
2. **Confirm `app.executespec.ai` serves prod + signup works end-to-end** — all marketing CTAs now
   point there. Don't deploy `website/` (wrangler) before this.
3. **The three trust-bug fixes land + are re-verified** (score-on-Gradle, OpenRouter cost, Stripe
   activation) — in flight in the fix sessions.
4. Founder pricing: Stripe coupon + Razorpay offer created, row populated, **test purchase shows
   the discount**.
5. Social proof plumbing: og-hero resolves on prod; a `/shared/<token>` link unfurls in Slack.
6. Minimal ops story: Grafana alert destinations → your Telegram bot; hosted status page or an
   explicit "support email only" decision.
7. Demo video (60–90s, script in PH kit §6) + the verified-report gallery.
8. Secondary copy sweeps (non-blocking): `website/pricing-analysis.md`, `tier-decision.md`,
   `press-kit/README.md` still carry pre-V176 numbers.

---

## 8 · The 14-day sequence

| Days | Work |
| --- | --- |
| 1–2 (now) | Benchmarks re-run (you) · trust-bug fixes land (fix sessions) · Console decision (you, 10 minutes of clicking) |
| 3–5 | Wave-2 UX (Console flip if go) · ops checklist §7 items 1–6 · founder-pricing live |
| 6–8 | Demo video · OSS verified-report gallery (5–10 runs) · publish docs quickstart (USER_MANUAL → public) · deploy website |
| 9–10 | Soft launch: 10–20 design partners, white-glove, founder pricing · watch the funnel |
| 11–14 | Product Hunt / Show HN (kit is current) · reply cadence per PH kit §5 |

**Measure with the funnel you already have** (PostHog, 6 stages): SIGNUP → PROJECT_CREATED →
SPEC_SUBMITTED → PLAN_APPROVED → RUN_COMPLETE → PR_MERGED. The reframe's success metric is the
SPEC_SUBMITTED → PLAN_APPROVED conversion.

---

## 9 · Reference index

| What | Where |
| --- | --- |
| Truth-pass commit | `1be342eb` (RL-2026-06-019) |
| UX wave-1 commit | `26ab3f66` (RL-2026-06-020) |
| Ops checklist detail | [`docs/launch-readiness-2026-06-10.md`](launch-readiness-2026-06-10.md) |
| Reason log | [`docs/reason-log/2026-05.md`](reason-log/2026-05.md) — RL-2026-06-019/-020 at the top |
| UX verdicts + defects | `review/11-ux-review.md` (local, gitignored) |
| Console reframe plan | [`docs/plans/web-control-plane-reframe.md`](plans/web-control-plane-reframe.md) — Phases 2–4 pending |
| Enterprise claim rules | [`docs/enterprise-one-pager.md`](enterprise-one-pager.md) |
| PH launch kit | [`website/product-hunt-launch.md`](../website/product-hunt-launch.md) |
| Tier/billing truth | migrations V165 · V176 · V177 · V185 · V200 · V206 · V207 + `TierEnforcementService.resolveRunCostBudget` |
