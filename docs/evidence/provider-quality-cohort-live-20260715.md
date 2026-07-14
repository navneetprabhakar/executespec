# Live provider/model coding-quality cohort

Date: 2026-07-15  
Scope: bounded LOW coding route; DeepSeek plus two approved comparators  
Cohort artifact: [`provider-quality-cohort-20260715/deepseek-cohort.json`](provider-quality-cohort-20260715/deepseek-cohort.json)

## Result

The canonical `GET /api/v2/test/low-coding` route dispatched the same fixture, prompt fingerprint,
tool-schema fingerprint, LOW caps, and independent held-out evaluator for three tasks on each of
three providers:

| Provider | Model | Tasks | Held-out cases | Passed | Iterations | Reconciled spend |
|---|---|---:|---:|---:|---:|---:|
| DeepSeek | `deepseek-v4-pro-native` | 3 | 6 | 6 | 4, 4, 4 | $0.30 |
| Anthropic | `claude-sonnet-5` | 3 | 6 | 6 | 4, 4, 5 | $0.30 |
| OpenAI | `gpt-5.4` | 3 | 6 | 6 | 4, 4, 4 | $0.30 |

The independent evaluator is [`scripts/heldout-evaluate-low-python-addition.py`](../../scripts/heldout-evaluate-low-python-addition.py).
It loads each exported patch as a separate Python module and checks both `add(2, 3) == 5` and
`add(-2, 7) == 5`; this is distinct from the harness's source-substring check. The cohort validator
returned `PASS` with `artifactLevel=true` and `independentHeldOutEvaluator=true`.

## Interpretation boundary

This closes the minimum live provider-dispatch and bounded coding-quality evidence gate for these
three routes. It does **not** establish frontier/SOTA parity, broad repository coding quality,
automatic handoff uplift, or statistical ranking: one tiny deterministic fixture and three attempts
per provider are insufficient for those claims. The evaluator, patch artifacts, prompt/tool/harness
fingerprints, and reconciled ledger rows are retained for audit. Gemini was intentionally not called.

All six calls were reconciled at the approved $0.10 safe maximum per task; no reservation remains
open and no provider exceeded its $10 cap.
