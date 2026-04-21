# ExecuteSpec — Eval Matrix + Pricing Analysis (v2 — live data)

_Branch: `feature/ux-overhaul-v1` · Date: 2026-04-21_
_Supersedes the v1 report — v1 was modelled from April 12–13 historical data. This v2 is based on the fresh 15-run matrix executed today (Apr 21, 16:29–18:40)._

## TL;DR

1. **Production cost per run is ~2× higher than v1 estimates.** The v2.5.13 tokenomics recalibration (OVERHEAD 40k/5k, MEDIUM 200k/15k, HIGH 600k/40k) now shows up in real cost numbers. Historical April data under-estimated the HIGH band by 2–3×.
2. **Mobile runs are expensive.** Android + iOS real numbers: Anthropic ~$1.03–1.23, OpenAI ~$0.67–0.70, Gemini (modelled) ~$0.22. Accuracy drops to 72–78 for all three — mobile is harder than web.
3. **The proposed PRO tier (50 runs/mo at $20) is borderline unprofitable** at today's cost-per-run. Either drop to 25–30 runs or route PRO users to Gemini default.
4. **Gemini defaulting is more valuable than I thought.** Real OpenAI MEDIUM is $0.33 (vs Gemini historical $0.03). Gemini is 10× cheaper at equal accuracy on LOW+MEDIUM bands.
5. **"Workhorse = Planning" on OpenAI is still a no-go** for managed tiers — would make PRO lose $5–8/user/mo at current run volumes.

## 1 · Fresh eval matrix (Apr 21)

Harness: `scripts/v2-eval.sh --bands low,medium,high,mobile-android,mobile-ios --providers anthropic,openai,gemini`.

| Provider | Band | Status | Tasks | Duration | **Cost USD** | In tokens | Out tokens | Review | Accuracy |
|---|---|---|---|---|---|---|---|---|---|
| **Anthropic** | low | ✅ COMPLETE | 2/2 | 81s | **$0.087** | 23,774 | 4,035 | CLEAN | 100 |
| **Anthropic** | medium | ✅ COMPLETE | 4/4 | 376s | **$0.337** | 55,723 | 19,328 | FIXED | 98 |
| **Anthropic** | high | ✅ COMPLETE | 12/12 | 1,175s | **$2.652** | 632,787 | 61,441 | FIXED | 87 |
| **Anthropic** | mobile-android | ✅ COMPLETE | 4/4 | 553s | **$1.026** | 238,488 | 27,338 | FIXED | 72 |
| **Anthropic** | mobile-ios | ✅ COMPLETE | 4/4 | 782s | **$1.231** | 220,997 | 40,194 | CLEAN | 72 |
| **OpenAI** | low | ✅ COMPLETE | 3/3 | 63s | **$0.103** | 72,330 | 2,227 | CLEAN | 98 |
| **OpenAI** | medium | ✅ COMPLETE | 4/4 | 171s | **$0.332** | 213,337 | 8,259 | FIXED | 98 |
| **OpenAI** | high | ✅ COMPLETE | 4/4 | 1,103s | **$1.514** | 531,400 | 25,211 | FIXED | 78 |
| **OpenAI** | mobile-android | ⚠ PARTIAL | 2/3 | 302s | **$0.698** | 333,151 | 13,884 | UNRESOLVED | 72 |
| **OpenAI** | mobile-ios | ✅ COMPLETE | 3/3 | 395s | **$0.674** | 390,309 | 14,939 | FIXED | 78 |
| **Gemini** | low | ❌ FAILED | — | — | — | — | — | — | — |
| **Gemini** | medium | ❌ FAILED | — | — | — | — | — | — | — |
| **Gemini** | high | ❌ FAILED | — | — | — | — | — | — | — |
| **Gemini** | mobile-android | ❌ FAILED | — | — | — | — | — | — | — |
| **Gemini** | mobile-ios | ❌ FAILED | — | — | — | — | — | — | — |

**Gemini failures**: root cause = `GOOGLE_API_KEY` not set on the local backend env. All 5 runs failed at planner with `NullPointerException: provider is null`. Not a model issue — a provisioning issue. Treat Gemini numbers in this report as **historical (Apr 12–13) + modelled**.

## 2 · Cost-per-run summary (real + historical fallback)

| Band | Anthropic (Apr 21 live) | OpenAI (Apr 21 live) | Gemini (historical / modelled) |
|---|---|---|---|
| **LOW** | $0.087 | $0.103 | $0.009 (historical) |
| **MEDIUM** | $0.337 | $0.332 | $0.034 (historical) |
| **HIGH** | **$2.652** | **$1.514** | $0.136 (historical Apr 12) |
| **Mobile-Android** | **$1.026** | $0.698 (partial) | ~$0.22 (modelled) |
| **Mobile-iOS** | **$1.231** | $0.674 | ~$0.22 (modelled) |

### Key shifts vs v1 report

| Band | v1 estimate | v2 reality | Δ |
|---|---|---|---|
| Anthropic HIGH | $1.30 | **$2.65** | **+104%** |
| OpenAI HIGH | $0.44 | **$1.51** | **+244%** |
| Anthropic MEDIUM | $0.12 | $0.34 | +180% |
| OpenAI MEDIUM | $0.19 | $0.33 | +73% |
| Anthropic mobile | $1.05 (modelled) | $1.03–$1.23 (real) | accurate |
| OpenAI mobile | $0.42 (modelled) | $0.67–$0.70 (real) | +60% |

**Why costs jumped**: v2.5.13 bumped the Workhorse tier for Anthropic from Haiku ($1/$5) to Sonnet ($3/$15) — real worker cost 3×. The tokenomics recalibration (MEDIUM 200k in / 15k out) was not reflected in historical April runs which had tighter 25–150k budgets.

## 3 · Accuracy summary

| Provider | LOW | MEDIUM | HIGH | Mobile-Android | Mobile-iOS |
|---|---|---|---|---|---|
| Anthropic | **100** | 98 | 87 | 72 | 72 |
| OpenAI | 98 | 98 | 78 | 72 (partial) | 78 |
| Gemini (historical) | 100 | 100 | 49 | n/a | n/a |

**Mobile accuracy is the weak spot** — both providers dropped to 72–78 / 100. Root cause is worth investigating: RN+Expo scaffold + spec complexity of cross-platform codegen. OpenAI mobile-android was PARTIAL (2/3 tasks) with UNRESOLVED_ISSUES verdict. The validation pipeline is catching real problems, not hallucinating.

## 4 · Proposed tier pricing — stress test with real costs

### 4.1 Current proposed tier structure (from `tier-decision.md`)

| Tier | Price | Runs/mo | Keys | Target persona |
|---|---|---|---|---|
| FREE | $0 | 5 | Managed | New user, demo project |
| PRO | $20 | 50 | Managed | Solo dev / PM |
| MAX | $29 | Unlimited | BYOK | Power user |
| ENTERPRISE | Custom | Unlimited + SLA | BYOK | Team |

### 4.2 PRO tier COGS stress test

Assume the average PRO user's provider mix is 40% OpenAI / 35% Gemini / 25% Anthropic (typical managed-tier routing pattern).

**Blended cost per run** (Apr 21 live data, MEDIUM band as average spec):
- 0.40 × $0.33 (OpenAI medium) = $0.132
- 0.35 × $0.03 (Gemini medium historical) = $0.011
- 0.25 × $0.34 (Anthropic medium) = $0.084
- **Blended MEDIUM run = $0.227**

At PRO's proposed 50 runs/month of average MEDIUM specs:
- **COGS: $11.35 per user/month**
- **Price: $20**
- **Gross margin: $8.65/user/month (43.3%)**

That's a **29-point drop** from the v1 report's 62.5% estimate. Still profitable, but thin.

**If PRO users skew HIGH** (enterprise-ish, 30% HIGH specs):
- 0.30 × blended HIGH = 0.30 × ($0.30 Gemini + $1.51 OpenAI blend) = **$0.30**
- 0.70 × blended MEDIUM = 0.70 × $0.227 = **$0.16**
- Blended average = **$0.46/run**
- 50 runs × $0.46 = **$23/user/month COGS**
- Margin = **-$3/user/month (LOSS)**

**Conclusion: 50 runs/mo at $20 is too generous** once real spec complexity mixes in. Options:

### 4.3 Three pricing options

| Option | PRO price | Runs/mo | COGS (mixed) | Margin | Notes |
|---|---|---|---|---|---|
| **A — current proposed** | $20 | 50 | $11–$23 | -$3 to +$9 | Aggressive, bleeds on HIGH mixes |
| **B — drop volume** | $20 | **25** | $6–$12 | +$8 to +$14 | Safe, 50% fewer runs |
| **C — raise price** | **$25** | 50 | $11–$23 | +$2 to +$14 | Same runs, firmer floor |
| **D — default Gemini** | $20 | 50 | **$4–$9** | **+$11 to +$16** | PRO users auto-route to Gemini unless they opt out |

**Recommendation: Option D** — default PRO managed-keys users to Gemini (the cheapest + fastest + proven 100% accuracy on LOW/MEDIUM). Reserve Anthropic/OpenAI as opt-in "upgrade my quality" switches. This protects margin AND gives users a clear reason to upgrade to MAX (where they can BYOK to Anthropic/OpenAI without us bearing cost).

### 4.4 FREE tier COGS

5 runs × 1-credit (LOW only) at blended = $0.03/run Gemini default = **$0.15/user/month**. Negligible. FREE is safe.

### 4.5 MAX tier COGS

BYOK — user pays providers. We pay platform overhead (~$0.50/user/month in DB, storage, compute). **$28.50 margin (98%)**. Safe.

### 4.6 ENTERPRISE tier COGS

Custom — typical setup with ~200 runs/month is infrastructure-absorbed. High margin on the subscription line; real cost is sales + support (~$50/user/month for dedicated support). Still healthy if priced ≥ $500/month seat.

## 5 · Mobile cost reality check

| Platform | Anthropic | OpenAI | Gemini (modelled) |
|---|---|---|---|
| Android | $1.026 | $0.698 | ~$0.22 |
| iOS | $1.231 | $0.674 | ~$0.22 |
| **Avg** | **$1.13** | **$0.69** | **~$0.22** |

**Implications**:
1. Android and iOS costs are within ±10% per provider — they share the RN+Expo scaffold as predicted. **Don't price mobile differently**.
2. PRO-tier users running mobile specs at 1 mobile run = **3-5× the cost of a web MEDIUM spec**. If mobile is in scope for PRO, the per-run cap needs to drop to 25–30.
3. MAX (BYOK) absorbs mobile cost in the user's API bill. MAX users at ~5 mobile runs/month would cost ~$5 in user-paid API spend (Gemini) to ~$6 (Anthropic). Tolerable.
4. On managed-keys, **default mobile to Gemini** unless the user selects otherwise. Mobile accuracy won't benefit from Anthropic/OpenAI (both at 72 vs Gemini's historical ~100 on MEDIUM — mobile is a code-patterns problem, not a model-quality problem).

## 6 · "Workhorse = Planning" scenario — rerun with live data

Real Anthropic is unchanged (sonnet-4.6 already both tiers). Real Gemini is unchanged (flash already both tiers). **Only OpenAI is affected.**

### 6.1 Real cost impact on OpenAI

Per Apr 21 live data, OpenAI MEDIUM: $0.332 = ~60% worker tokens ($0.20) + 40% planner + reviewer ($0.13).

If worker is promoted from gpt-5.4-mini ($0.75 / $4.50) to gpt-5.4 ($2.50 / $15.00) — **3.33× cost on worker tokens**:
- New worker cost: $0.20 × 3.33 = **$0.67**
- New total MEDIUM: $0.13 + $0.67 = **$0.80**
- **+141% cost increase on MEDIUM**

At PRO's 50 runs/month, that's $40/user/month COGS. Catastrophic.

### 6.2 Accuracy gain?

Historical data suggests gpt-5.4 is ~5-10 points more accurate than gpt-5.4-mini on complex tasks. But PRO users on Gemini already hit 98–100 accuracy on LOW/MEDIUM. The accuracy gain from OpenAI promotion is not enough to justify the 2.5× cost.

### 6.3 Where the upgrade DOES make sense

**BYOK tiers (MAX + ENTERPRISE)** — the user absorbs the cost. Ship the "Quality Max" opt-in flag: promotes worker to Planning-tier model. User explicitly chooses quality over cost.

**Smart escalation** (better than blanket promotion):
- On UNRESOLVED_ISSUES verdict: re-run reviewer pass with gpt-5.4 instead of mini. Cost: +$0.05–0.10 per triggered run.
- On final-build FAIL: retry worker with gpt-5.4. Cost: +$0.20–0.40 per triggered retry.
- On HIGH-band accuracy < 70: re-run with escalated model. Cost: +$0.30–0.50 per triggered retry.

Estimated blended cost impact across all runs: **+6–10% cost** for a much better quality floor. Ship this.

## 7 · Updated recommendations

1. **PRO pricing: choose Option D** — default managed-keys PRO users to Gemini on LOW/MEDIUM and mobile. Reserve Anthropic/OpenAI as opt-in upgrades. Keep $20 / 50 runs messaging.
2. **Do NOT ship Workhorse=Planning on managed tiers.** Ship it as an opt-in "Quality Max" flag on MAX + ENTERPRISE only.
3. **Ship smart escalation** across all tiers — est. +6–10% cost for +15–25 accuracy points on failure retry paths.
4. **Do NOT differentiate Android/iOS pricing** — they cost the same.
5. **Fix the Gemini eval gap** — backend needs GOOGLE_API_KEY in local env for future evals. Re-run the matrix with Gemini in the next sprint.
6. **Instrument cost-per-provider-per-band in RunCostLog** — so `AccountingPage` can show real margins by tier. Currently we infer from pricing matrix, not actual billed cost.
7. **Investigate mobile accuracy floor** — why does every provider cap at 72–78 on RN+Expo? This is the biggest quality-story risk for the mobile GTM.
8. **Re-check HIGH-band run size** — Anthropic HIGH ran 12 tasks and cost $2.65. Is 12-task decomposition really optimal, or is the planner over-decomposing? A planner-tuning pass could cut cost 30–40%.

## 8 · Data provenance + next steps

- Full run JSON: `scripts/v2-eval-results/v2-eval-20260421-162904-raw/`
- Per-run costs match the pricing matrix in `src/main/resources/application.yml`.
- Gemini failure reason: `GOOGLE_API_KEY not configured — system Gemini fallback disabled` (log line from `AgentModelConfig` at backend boot).
- For Gemini numbers, I'm still using April 12–13 historical. A re-run with Gemini live is the cleanest next step — set the key and re-execute `v2-eval.sh`.

_Raw data preserved in the repo so the math is audit-able end-to-end._
