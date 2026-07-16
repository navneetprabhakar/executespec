# Free-Tier Economics — Tuned Gemma 4 12B vs DeepSeek API (DRAFT)

**Status: DRAFT with labeled assumptions (2026-07-16).** Every number marked [A] is an assumption to replace with measured data (from `run_step_usage` ledgers and an actual serving benchmark). Purpose: decide whether self-hosting the tuned Gemma 4 12B beats the current DeepSeek-API free tier, and at what user scale.

## Current free-tier cost (DeepSeek API)

- Pricing (verified 2026-07-02 in application.yml): deepseek-v4-pro-native $0.435/M input (cache miss), $0.003625/M cache hit, $0.87/M output. Flash: $0.14 / $0.0028 / $0.28.
- [A] Typical small BUILD run: ~350k input (heavily cache-discounted), ~25k output → ~$0.08–0.15/run on Pro, ~$0.03–0.06 on Flash. TB data point: 445 trials cost $24.08 → **$0.054/trial average** — consistent with the low end.
- Free tier cap: 5 runs/mo → **raw COGS ≈ $0.25–0.75/user/mo** [A]. At 1,000 active free users: $250–750/mo. At 10,000: $2.5k–7.5k/mo.

## Self-hosted tuned Gemma 4 12B

Serving option [A — verify current GCP pricing before deciding]:

| Setup | Est. cost | Est. throughput (12B, batched, vLLM-class) |
|---|---|---|
| 1× L4 24GB (g2-standard-8), on-demand | ~$0.70–0.90/hr → ~$500–650/mo | [A] ~500–1,500 tok/s output under batching |
| Same, spot/preemptible | ~$0.25–0.40/hr → ~$180–290/mo | same, with eviction risk |
| 1× A100 40GB, on-demand | ~$3–4/hr → ~$2.2k–2.9k/mo | [A] ~2,500–5,000 tok/s |

- [A] One L4 at 50% utilization ≈ 1–3M output tok/hr ≈ roughly 40–120 small runs/hr — comfortably thousands of free-tier runs/day.
- Break-even vs DeepSeek at ~$0.05–0.10/run: one on-demand L4 ($600/mo) pays for itself at **~6,000–12,000 runs/mo** [A] — i.e., ~1,200–2,400 free users at the 5-run cap, less on spot.

## Read (draft conclusion)

1. **Below ~1,000 active free users, DeepSeek API is cheaper and zero-ops.** The switch is not an economics play at current scale (zero users) — it's a *capability and margin-at-scale* play.
2. **The real arguments for owned SLM:** (a) frontier-lab price changes can't break the tier; (b) fine-tuned behavior tuned to the ES harness (the TB evidence says harness+small model punches up); (c) at 10k+ free users the API bill crosses ~$3–8k/mo while 1–2 GPUs stay ~$0.5–1.5k; (d) the same serving stack hosts the world model.
3. **Hidden costs to price in before deciding:** serving-ops burden on a solo founder (on-call for GPU infra), autoscaling/cold-start complexity, eval + regression gates per fine-tune release, and Gemma license compliance for commercial hosted serving [verify Gemma 4 terms of use — action item].
4. **Recommendation (draft):** keep DeepSeek for free tier at launch; run Gemma behind an internal flag as a shadow route (same requests, compare quality + cost on real traffic); switch when (a) quality gate passes k≥3 on the tier-eval, (b) active free users > ~1,500, or (c) the world-model serving stack has to exist anyway — whichever comes first.

## Action items

1. Replace [A] rows with a real vLLM benchmark of the tuned checkpoint on one L4 and one A100 (a day of work, <$50).
2. Pull actual per-run free-tier COGS from `run_step_usage` once design partners generate traffic.
3. Legal check: Gemma 4 license for commercial hosted inference + fine-tune distribution.
4. Re-verify GCP GPU pricing (us-east4) — table above is [A].
