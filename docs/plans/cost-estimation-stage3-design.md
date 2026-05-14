# Cost estimation — Stage 3 design (per-spec LLM judge)

> Status: **deliberately deferred per the decision gate at the bottom of this doc.**
> Stages 1 + 2 are shipped + live; the gate that authorises Stage 3 has not fired.
> Author: 2026-05-13 audit, run 6024f510. Status confirmed 2026-05-14.

## Status update — 2026-05-14

- **Stage 1 (RunCostTracker formula)** — SHIPPED. Lives at
  `src/main/java/com/navneet/executespec/cost/RunCostTracker.java`. Adds reviewer + accuracy +
  codemap + cache-write components on top of the static formula.
- **Stage 2 (CostHistoryService k-NN)** — SHIPPED. Lives at
  `src/main/java/com/navneet/executespec/cost/CostHistoryService.java`. Returns P25/P75 of
  actual costs from `run_cost_history` when ≥ 10 similar historical runs exist for a
  `(planStrategy, provider, repoCount, taskCount)` tuple. Wired in `RunOrchestrator.persistEstimate`.
  Falls back to Stage 1 for novel shapes.
- **Stage 3 (per-spec LLM judge)** — NOT shipped, and correctly NOT shipped. The decision
  gate below says "build only after Stage 2 has 30+ days of collected data + at least one
  trigger fires." We're at day 1 of the Stage-2 deployment on this branch's runs. **Building
  Stage 3 today would violate the doc's own gate.** Re-evaluate this in mid-June 2026 once
  Stage 2 has the data needed to know whether Stage 3's marginal accuracy gain is justified.

The doc below is preserved as the design ready to pick up *if* the gate fires.

## What Stages 1 + 2 already do

- **Stage 1** (RunCostTracker formula): adds reviewer / accuracy / codemap / cache-write components, bumps multipliers. Closes the static-formula gap. Per-shape estimate quality bounded by how well the bands match real distributions.
- **Stage 2** (CostHistoryService k-NN): when ≥ 10 similar historical runs exist for a (planStrategy, provider, repoCount, taskCount) tuple, return P25/P75 of their actual costs. Self-calibrating. Falls back to Stage 1 for novel shapes.

The combination is statistically strong for **common** shapes. Stage 3 addresses the long tail where similarity scoring on (strategy, provider, repoCount, taskCount) is too coarse — e.g. two runs with the same shape but radically different spec ambition.

## Stage 3 — when to build

Build when one of these is true after Stage 2 has 30+ days of data:

| Trigger | Threshold |
|---|---|
| Per-shape variance is huge | StdDev / P50 > 0.8 across the K samples → similarity scoring isn't capturing real cost drivers |
| Long-tail miss rate | Actual cost > P95 of historical band for > 10% of runs in a shape |
| Specific shapes never converge | Novel projects (one-off project types) where k-NN never gets ≥ 10 samples |
| Customer complaints | Manual feedback that estimates are wrong |

If none of these trigger, Stages 1+2 are sufficient — **do not build Stage 3**.

## Stage 3 shape

A single Anthropic Haiku call (or Gemini Flash — cheapest available LIGHTWEIGHT) at run-submission time:

```
INPUT:
  - The new spec (truncated to 4k chars)
  - The Stage-1 formula estimate as a baseline
  - The Stage-2 k-NN P25/P75 (when available)
  - 5 nearest historical runs: (spec excerpt, planStrategy, repoCount,
    actualCostUsd) — fetched via spec-embedding similarity, NOT just the
    shape-similarity Stage 2 uses

OUTPUT:
  - { lowUsd, highUsd, confidence: HIGH|MEDIUM|LOW, reasoning: "..." }

PROMPT:
  "Given this spec and these K most-similar past runs (by spec embedding),
   estimate the cost range. Consider: complexity hidden in the spec text
   (e.g. 'rewrite all routers' is more than 'fix typo in router'), implicit
   work (testing, docs), and historical actuals. Be honest about confidence."
```

### Cost of running Stage 3

- Haiku rate: $1 / 1M in, $5 / 1M out
- Stage 3 call: ~3,000 in / 200 out = $0.004 per estimate
- Even at 10,000 runs/month = $40/month total

### Why not Sonnet

Cost estimation is mostly pattern matching against historical data — doesn't need PLANNING-tier reasoning. Haiku is enough and 30× cheaper.

## Data dependencies

Stage 3 needs spec embeddings in `run_cost_history`. Add a column when Stage 2 first proves insufficient:

```sql
ALTER TABLE run_cost_history
  ADD COLUMN spec_embedding vector(1536),  -- text-embedding-3-small
  ADD COLUMN spec_excerpt   TEXT;          -- first 1k chars for prompt inclusion
```

`EmbeddingService.embed(spec)` already exists for RAG; reuse it. Cost: $0.00002 per embedding (text-embedding-3-small).

## Implementation surface (when ready)

- New class `cost/LlmCostEstimator.java` — single method `Optional<Estimate> estimateFromLlm(SpecContext)`
- New table column migrations (V_next)
- New `pgvector` similarity query in `RunCostHistoryRepository.findSimilarBySpec(embedding, limit)`
- Wire in `RunOrchestrator.persistEstimate` BEFORE the Stage 2 k-NN call:
  ```
  Optional<Estimate> stage3 = llmCostEstimator.estimateFromLlm(...);
  if (stage3.isPresent()) return stage3.get();
  Optional<Estimate> stage2 = costHistoryService.estimateFromHistory(...);
  if (stage2.isPresent()) return stage2.get();
  return costTracker.estimateRunTierAware(...);  // Stage 1 fallback
  ```

## Open questions for Stage 3

1. **Embedding model choice**: text-embedding-3-small ($0.02/1M) is the default. Could try Voyage Code for code-heavy specs.
2. **K (similarity neighbours)**: 5 in the prompt vs 20 in the prompt? Test both.
3. **Confidence -> UI surface**: should we show LOW-confidence estimates with a "≈" prefix? Or hide them and fall through to Stage 1?
4. **Per-org calibration**: BYOK orgs use Anthropic-listed prices, not our discounted rates. Need an `is_byok` filter or per-org cost rescale.
5. **Caching**: cache `(spec hash → estimate)` for the first 5 min to handle resubmits + retries cheaply.

## Decision gate

Before building Stage 3, answer:

- [ ] Has Stage 2 had ≥ 30 days of data on dev or prod?
- [ ] Did at least one trigger (above) fire?
- [ ] Is the engineering cost (≈3-4 days) justified by the projected accuracy gain?

If any answer is no, **defer Stage 3**.
