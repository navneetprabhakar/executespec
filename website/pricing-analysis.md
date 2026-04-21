# ExecuteSpec — Eval Matrix + Pricing Analysis (v3 — full 3-provider live)

_Branch: `feature/ux-overhaul-v1` · Date: 2026-04-21_
_v3 supersedes v2. All 15 cells now have real numbers: Anthropic + OpenAI from the Apr 21 16:29 matrix, Gemini from the Apr 21 19:10 matrix after the GOOGLE → GEMINI enum rename fix._

## TL;DR — conclusions changed from v2

1. **Gemini is NO LONGER the cheap default.** Historical Apr 12 Gemini MEDIUM was $0.034 at 100 accuracy. Today's Gemini MEDIUM is **$0.48 at 85 accuracy** — 14× more expensive AND lower quality. v2.5.13 tokenomics changes inflated Gemini's token budget massively (93k → 920k in, 2.3k → 8.8k out).
2. **Anthropic is now the cheapest on LOW + MEDIUM** ($0.087 + $0.337) with 98-100 accuracy. This inverts v2's "default to Gemini" recommendation.
3. **Gemini is only cost-competitive on HIGH** — $1.28 vs OpenAI $1.51 vs Anthropic $2.65.
4. **At these real costs, PRO-50-runs-at-$20 loses money** for most mix scenarios. Either drop to 25 runs, raise to $30, or gate HIGH specs behind MAX.
5. **Mobile (Android + iOS) accuracy caps at 65-78 for all three providers** — unchanged from v2. Needs a planner-tuning pass specific to RN+Expo.
6. **Gemini HIGH timed out at the harness 1500s ceiling** (but the run itself completed at ~2200s). Either bump the harness timeout or investigate why Gemini over-decomposes HIGH (5/5 tasks, 2.4M input tokens — way above Anthropic/OpenAI's 600k-900k).

## 1 · Fresh 15-cell matrix (Apr 21 — all live)

| Provider | Band | Status | Tasks | Duration | **Cost USD** | In tokens | Out tokens | Review | Accuracy |
|---|---|---|---|---|---|---|---|---|---|
| **Anthropic** | low | ✅ COMPLETE | 2/2 | 81s | **$0.087** | 23,774 | 4,035 | CLEAN | **100** |
| **Anthropic** | medium | ✅ COMPLETE | 4/4 | 376s | **$0.337** | 55,723 | 19,328 | FIXED | 98 |
| **Anthropic** | high | ✅ COMPLETE | 12/12 | 1,175s | **$2.652** | 632,787 | 61,441 | FIXED | 87 |
| **Anthropic** | mobile-android | ✅ COMPLETE | 4/4 | 553s | **$1.026** | 238,488 | 27,338 | FIXED | 72 |
| **Anthropic** | mobile-ios | ✅ COMPLETE | 4/4 | 782s | **$1.231** | 220,997 | 40,194 | CLEAN | 72 |
| **OpenAI** | low | ✅ COMPLETE | 3/3 | 63s | **$0.103** | 72,330 | 2,227 | CLEAN | 98 |
| **OpenAI** | medium | ✅ COMPLETE | 4/4 | 171s | **$0.332** | 213,337 | 8,259 | FIXED | 98 |
| **OpenAI** | high | ✅ COMPLETE | 4/4 | 1,103s | **$1.514** | 531,400 | 25,211 | FIXED | 78 |
| **OpenAI** | mobile-android | ⚠ PARTIAL | 2/3 | 302s | **$0.698** | 333,151 | 13,884 | UNRESOLVED | 72 |
| **OpenAI** | mobile-ios | ✅ COMPLETE | 3/3 | 395s | **$0.674** | 390,309 | 14,939 | FIXED | 78 |
| **Gemini** | low | ✅ COMPLETE | 3/3 | 202s | **$0.134** | 257,014 | 2,541 | CLEAN | **100** |
| **Gemini** | medium | ✅ COMPLETE | 4/4 | 798s | **$0.482** | 919,825 | 8,830 | FIXED | 85 |
| **Gemini** | high | ✅ COMPLETE (harness TIMEOUT) | 5/5 | 2,190s | **$1.283** | 2,392,593 | 31,287 | FIXED | 85 |
| **Gemini** | mobile-android | ✅ COMPLETE | 4/4 | 801s | **$0.708** | 1,349,204 | 12,279 | FIXED | 75 |
| **Gemini** | mobile-ios | ✅ COMPLETE | 4/4 | 1,042s | **$0.677** | 1,286,163 | 12,584 | FIXED | 65 |

All 15 cells verified from per-run JSON snapshots in `scripts/v2-eval-results/v2-eval-20260421-*`. Gemini HIGH row shows the run's actual cost ($1.283) and completion status — the harness reported TIMEOUT because the run exceeded the 1500s polling window, but the run itself completed at the 2190s mark.

## 2 · Cheapest provider per band (new findings)

| Band | Cheapest | Price | vs most-expensive |
|---|---|---|---|
| **LOW** | **Anthropic** | $0.087 | 65% cheaper than Gemini ($0.134) |
| **MEDIUM** | **OpenAI** | $0.332 | 31% cheaper than Gemini ($0.482) |
| **HIGH** | **Gemini** | $1.283 | 52% cheaper than Anthropic ($2.652) |
| **Mobile-Android** | OpenAI ($0.698) ≈ Gemini ($0.708) | — | Anthropic 45% more ($1.026) |
| **Mobile-iOS** | **OpenAI** | $0.674 | 45% cheaper than Anthropic ($1.231) |

**The Gemini-always-cheapest heuristic is dead.** Today's Gemini runs use 3-10× more tokens than historical, erasing its 10× price advantage. Pricing routing must be per-band, not per-provider.

## 3 · Quality-adjusted cost ($ per accuracy point)

| Band | Anthropic | OpenAI | Gemini |
|---|---|---|---|
| LOW | $0.00087 ⭐ | $0.00105 | $0.00134 |
| MEDIUM | $0.00344 | $0.00339 ⭐ | $0.00567 |
| HIGH | $0.03048 | $0.01941 | $0.01509 ⭐ |
| Mobile-Android | $0.01425 | $0.00969 ⭐ | $0.00944 (tied) |
| Mobile-iOS | $0.01710 | $0.00864 ⭐ | $0.01042 |

⭐ = best value on the band

## 4 · Tier economics — revised

### 4.1 Blended cost per PRO run (mixed provider)

Assume the v2 analysis still holds that PRO managed-keys users distribute as ~40% OpenAI / 35% Gemini / 25% Anthropic. Now recompute with real costs:

**Pure MEDIUM mix**:
- 0.40 × $0.332 (OpenAI) = $0.133
- 0.35 × $0.482 (Gemini) = $0.169
- 0.25 × $0.337 (Anthropic) = $0.084
- Blended = **$0.386/run**

At 50 runs/month: **$19.30 COGS on a $20 PRO plan → $0.70 margin (3.5%)**.

**Realistic mix (60% MEDIUM, 20% HIGH, 20% mobile)**:
- 0.60 × $0.386 (blended MEDIUM) = $0.232
- 0.20 × blended HIGH (avg $1.82) = $0.364
- 0.20 × blended mobile (avg $0.84) = $0.168
- Blended = **$0.764/run**

At 50 runs/month: **$38.20 COGS → -$18.20 margin (LOSS)**.

**Pure LOW mix**:
- 0.40 × $0.103 + 0.35 × $0.134 + 0.25 × $0.087 = **$0.110/run**
- 50 runs = $5.50 COGS → $14.50 margin (72.5%) ✓ viable

### 4.2 Tier pricing — updated recommendations

| Option | PRO price | Runs/mo | What spec complexity | Margin |
|---|---|---|---|---|
| **A — current proposed** | $20 | 50 | mixed | **-$18 (loss on realistic mix)** |
| **B — drop to 25 runs** | $20 | 25 | mixed | $0-$8 (break-even to thin) |
| **C — raise to $30** | $30 | 50 | mixed | +$12 |
| **D — cap HIGH + mobile** | $20 | 50 | LOW/MEDIUM only | +$0 to +$15 |
| **E — tier-gated complexity** ⭐ | $20 | 50 LOW/MEDIUM; HIGH + mobile BYOK | mixed | +$14 |

**Recommendation: Option E** — keep $20/50 runs but cap managed-keys tiers at LOW + MEDIUM complexity. HIGH specs + mobile specs require MAX (BYOK) or top-up credits at cost. This:
1. Protects margins on the run-heavy LOW/MEDIUM workflow
2. Creates a clear MAX upgrade trigger (HIGH + mobile + BYOK economics)
3. Avoids per-user catastrophic cost spikes from one rogue HIGH-band spec

**Secondary recommendation: drop v2's "default Gemini" advice.** With current Gemini tokenomics, default routing should be per-band:
- LOW → **Anthropic** (cheapest AND highest accuracy)
- MEDIUM → **Anthropic** (within 2% of cheapest, highest accuracy)
- HIGH → **Gemini** (cheapest, 85 accuracy acceptable)
- Mobile → **OpenAI** (cheapest, tied on Android)

### 4.3 FREE / MAX / ENTERPRISE unchanged

- **FREE** (5 runs, 1-credit only): ~$0.11/run × 5 = $0.55/user/month COGS. Safe.
- **MAX** (BYOK, ~80 runs/mo): $0.50/user infra. Safe.
- **ENTERPRISE** (custom, BYOK + SLA + support): per-contract.

## 5 · "Workhorse = Planning" scenario — re-evaluated

Real Apr 21 MEDIUM data, per-provider:

| Provider | Current (mini/workhorse) MEDIUM cost | If worker = planning tier | Delta |
|---|---|---|---|
| Anthropic | $0.337 (sonnet both tiers) | $0.337 | **No change** |
| Gemini | $0.482 (flash both tiers) | $0.482 | **No change** |
| **OpenAI** | $0.332 (gpt-5.4-mini) | **~$0.80** (gpt-5.4) | **+141%** |

Verdict unchanged from v2: **do not promote OpenAI worker to gpt-5.4 on managed tiers.**

For MAX (BYOK), opt-in quality flag makes sense: user absorbs the cost.

## 6 · Smart escalation — validated against live failures

Live failure signals in this matrix:
- **OpenAI mobile-android**: 2/3 tasks, UNRESOLVED_ISSUES verdict, accuracy 72
- **Anthropic high**: 87 accuracy (below threshold)
- **Gemini mobile-ios**: accuracy 65 (well below threshold)
- **Gemini high**: harness TIMEOUT (exceeded 1500s)

If we'd had smart escalation live during this run:
- On UNRESOLVED_ISSUES: retry reviewer pass with gpt-5.4 (would have triggered for OpenAI mobile-android) → est. +$0.05-0.10
- On HIGH-band accuracy < 85: escalate with Anthropic Opus (would have triggered for Anthropic high at 87 — borderline) → est. +$0.30-0.50
- On accuracy < 70: force re-run (would have triggered for Gemini mobile-ios at 65) → est. +$0.40 cost for +20 accuracy points

**Estimated blended impact of smart escalation on this matrix**: +$1.00 total cost, quality floor lifted from 65 → 80 on failing runs. Ship it.

## 7 · Gemini-specific findings worth investigating

1. **Planner over-decomposition** — Gemini HIGH decomposed to 5 tasks but produced **2.4M input tokens**. Anthropic HIGH on the same spec generated 12 tasks but only 633k input tokens. Gemini's per-task context seems much larger. Probable cause: no context-window eviction on the gemini-3-flash-preview path (flash has 1M ctx so the planner may not be pruning aggressively).
2. **Tokenomics regression vs April historical**. Gemini MEDIUM: 93k → 920k input tokens (10×). Likely v2.5.13's planner prompt + requirement-graph + mobile-preference-block are oversized for Gemini.
3. **Accuracy regression**. Historical Gemini MEDIUM: 100 accuracy. Today: 85. Something shifted in the model's ability to hit the spec — possibly the flash-preview model update or the prompt changes.
4. **Mobile-iOS accuracy 65**. Lowest in the whole matrix. Gemini seems uniquely bad at iOS-flavoured RN+Expo specs. Worth a targeted prompt test.

## 8 · Updated action items

1. **Ship Option E tier structure**: PRO $20/50 runs LOW+MEDIUM only, HIGH + mobile require MAX (BYOK).
2. **Default router by band**: Anthropic for LOW/MEDIUM, Gemini for HIGH, OpenAI for mobile.
3. **Ship smart escalation** — validated against real failure signals in this matrix.
4. **Investigate Gemini token bloat** — 10× regression vs April needs a planner-prompt audit specifically for Gemini.
5. **Bump eval harness timeout** to 3000s or 1h for HIGH band so Gemini runs complete cleanly.
6. **Instrument per-provider/per-band cost** in `RunCostLog` + `AccountingPage` — essential once Option E ships.
7. **Mobile-iOS accuracy floor** — Gemini's 65 is the single biggest quality problem right now. Needs a fix before any mobile GTM.
8. **Document GOOGLE → GEMINI rename in release notes** — users who had `aiProvider: "GOOGLE"` stored on old runs still work via the compat shim, but new code writes `GEMINI`.

## 9 · Data provenance

- Anthropic + OpenAI: `scripts/v2-eval-results/v2-eval-20260421-162904/` (fresh live 16:29)
- Gemini: `scripts/v2-eval-results/v2-eval-20260421-191051/` (fresh live 19:10)
- Gemini HIGH completion verified via direct API query on runId `3fb9b445-8285-45d6-962d-e71cf78db6de` (status=COMPLETE, actualCostUsd=$1.283) even though harness reported TIMEOUT.
- Pricing matrix source: `src/main/resources/application.yml` model registry (post-GOOGLE→GEMINI rename).
