# Provider/model coding-quality cohort (unverified offline adjudication)

**Status: `UNVERIFIED_OFFLINE_ADJUDICATION` — not live-provider evidence and not a release gate.**

The checked-in artifacts contain self-declared task/evaluator results, but do not include the
server-side run records, request/response or SSE captures, reservation IDs, or immutable
before/after provider-ledger evidence required to prove live dispatch. The identical patch
artifacts also cannot establish independent per-provider behavior. Retain this report for forensic
audit only; it must not enable capability profiles, prompt overlays, routing priors, or provider
quality claims.

Date: 2026-07-15  
Scope: bounded LOW coding route; DeepSeek plus two approved comparators  
Cohort artifact: [`provider-quality-cohort-20260715/deepseek-cohort.json`](provider-quality-cohort-20260715/deepseek-cohort.json)

## Result

The artifact *claims* that the canonical `GET /api/v2/test/low-coding` route dispatched the same fixture, prompt fingerprint,
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

This does **not** close the live provider-dispatch or bounded coding-quality evidence gate. It does
not establish frontier/SOTA parity, broad repository coding quality,
automatic handoff uplift, or statistical ranking: one tiny deterministic fixture and three attempts
per provider are insufficient for those claims. The evaluator, patch artifacts, prompt/tool/harness
fingerprints, and reconciled ledger rows are retained for audit. Gemini was intentionally not called.

The artifact claims six calls were reconciled at the approved $0.10 safe maximum per task. That
claim is not independently proven because reservation and ledger evidence is absent; no provider
quality or routing decision may rely on it.
