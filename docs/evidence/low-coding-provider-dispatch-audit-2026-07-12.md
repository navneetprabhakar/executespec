# LOW coding provider-dispatch audit

Date: 2026-07-12  
Scope: canonical backend router and controlled provider-budget guard  
Provider calls dispatched by this audit: **none**

## Determination

The existing guarded endpoint can safely dispatch a synthetic WorkerAgent probe, but it cannot prove a **real LOW coding task** across providers. `WorkerTestController` (`src/main/java/com/navneet/executespec/test/WorkerTestController.java:45-111`) hard-codes an arithmetic `numbers.txt` fixture, a generic worker prompt, `maxIterations=15`, and `maxTokensPerCall=1024`; it has no `complexity=LOW`, repository checkout, code-localization request, or test/repair evaluator beyond `sum.txt == 60`.

The canonical router/guard seam itself is safe:

- `LlmRouter.call` invokes `ProviderTestBudgetGuard.authorize` before every test-mode request (`src/main/java/com/navneet/executespec/agent/LlmRouter.java:322-330`).
- The guard enforces exact provider/model/run reservation scope through `scripts/provider-budget-ledger.mjs authorize-dispatch`.
- A reservation can be reconciled or closed as unknown after dispatch; direct CLI calls require an external wrapper because they do not inherit the in-process guard.

## Why no dispatch was made

Running the endpoint for Anthropic/OpenAI/Gemini/DeepSeek would produce four more synthetic transport probes, not a real LOW coding/localization/edit-test-repair measurement. The existing DeepSeek probe is already recorded in `docs/evidence/deepseek-edit-test-repair-2026-07-12.json`; duplicate probes would spend budget without closing the objective gap.

## Required next seam

Add a separately authorized benchmark harness (not a customer endpoint) that:

1. accepts a fixed, repository-local LOW task fixture with a known bug and hidden deterministic evaluator;
2. resolves the real LOW tier/model through `SequentialExecutor.tierConfigFor`, rather than hard-coded token/iteration values;
3. runs localization → edit → test → repair through `LlmRouter` with one reservation per provider/run;
4. records provider/model, resolved tier, prompt/tool-schema fingerprints, iterations, tool calls, latency, test/build outcome, patch hash, and reconciled cost; and
5. deletes the temporary workspace and emits a bounded evidence artifact.

Until that harness exists, classify provider status for this objective as `NOT_VERIFIED` rather than treating `/api/v2/test/worker` arithmetic probes as LOW coding evidence.
