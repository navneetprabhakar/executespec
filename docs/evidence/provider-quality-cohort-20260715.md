# Provider/model quality cohort — bounded audit

Date: 2026-07-15  
Scope: Milestones 8/12 (provider/model intelligence, low-complexity coding, edit/test/repair)  
Status: **NOT VERIFIED**

## Why this is not a quality cohort

No new provider call was dispatched in this audit. The first diagnostic request to the operator-only
`GET /api/v2/test/worker` route (Anthropic/Sonnet, authenticated, 20-second client timeout) connected
to the local JVM but returned no response body before the timeout. The request was stopped before a
cohort was started. The provider ledger was checked immediately before and after the diagnostic:

| Provider | Spent | Reserved | Remaining | Delta in this audit |
|---|---:|---:|---:|---:|
| Anthropic | $2.349960 | $0.000000 | $7.650040 | $0.000000 |
| OpenAI | $2.284355 | $0.000000 | $7.715645 | $0.000000 |
| DeepSeek | $0.877298 | $0.000000 | $9.122702 | $0.000000 |
| OpenRouter | $0.562494 | $0.000000 | $9.437506 | $0.000000 |
| Fireworks | $0.564898 | $0.000000 | $9.435102 | $0.000000 |

Gemini was intentionally not probed, per the current goal instruction. No reservation was left open.

The legacy `/api/v2/test/worker` route is also not a suitable comparator: it hard-codes an arithmetic
`numbers.txt` task and does not exercise repository localization or test/repair. The fixed repository
route is `GET /api/v2/test/low-coding`, which requires an exact provider-budget reservation and is the
correct seam for a future bounded cohort. The existing DeepSeek result remains the only committed
single-task coding evidence:
[`deepseek-edit-test-repair-2026-07-12.json`](deepseek-edit-test-repair-2026-07-12.json).

## Existing project/provider evidence

The current-session project smoke exercised authenticated project read surfaces across ten projects
and thirteen endpoint families (126 HTTP 200, four expected HTTP 204, zero unexpected failures).
Project chat/compile transport was also exercised with Anthropic, OpenAI, native DeepSeek, OpenRouter,
and Fireworks and returned HTTP 200. These are transport/persistence checks only; they do not establish
coding-quality parity, task success, or a provider ranking. Their semantic quality is therefore
`NOT_VERIFIED`.

## Required evidence to close the milestone

Run the fixed `low-python-addition-v1` fixture through the canonical
`LowCodingFixtureController` with the same task, prompt/tool fingerprints, LOW caps, and independent
held-out evaluator for at least three providers (DeepSeek plus two limited comparators). Reconcile
each reservation through `provider-budget-ledger.mjs`, retain patch/evaluator artifacts, and validate
the resulting cohort with `scripts/provider-quality-cohort.mjs`. A separate routing cohort must record
typed availability, quality evidence, route-change reason, category/complexity scope, and cumulative
spend through `scripts/orchestrator-routing-cohort.mjs`.

Until those artifacts exist, do not claim provider/model quality, automatic handoff uplift, or SOTA
parity from the existing transport smoke or single DeepSeek fixture.
