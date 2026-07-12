# Fixed-repo LOW coding harness

The backend now contains an operator/test-only `LowCodingFixtureHarness` with no HTTP route and no automatic provider dispatch.

- Source: `src/main/java/com/navneet/executespec/benchmark/LowCodingFixtureHarness.java`
- Test: `src/test/java/com/navneet/executespec/benchmark/LowCodingFixtureHarnessTest.java`
- Fixture: `calculator.py`, initially `return a - b`; evaluator requires `return a + b`.
- Tier caps: LOW, 60 iterations, 4,096 output tokens/call.
- Evidence fields: provider, model, tier, run id, prompt/tool-schema/harness SHA-256 fingerprints, worker outcome, iterations, tool calls, evaluator result.
- Safety: blank reservation IDs fail before worker dispatch. The caller must reserve/reconcile through `scripts/provider-budget-ledger.mjs`; live requests remain guarded by `LlmRouter`/`ProviderTestBudgetGuard`.

Deterministic verification: `mvn -q -Dtest=LowCodingFixtureHarnessTest test` and `mvn -q spotless:check` pass. No live provider call was made while adding or testing the harness.
