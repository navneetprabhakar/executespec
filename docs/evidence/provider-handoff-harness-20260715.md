# Provider handoff fixture harness — contract evidence

Date: 2026-07-15  
Status: **IMPLEMENTED / NOT LIVE-EXECUTED**

`ProviderHandoffFixtureHarness` adds a versioned operator-only contract for the
cohort that was previously blocked by the single addition fixture. Version
`v1` accepts three deterministic fixture ids:

- `repository-localization-v1`
- `edit-test-repair-v1`
- `stuck-handoff-v1`

The `POST /api/v2/test/provider-handoff` route is restricted to the `local`,
`dev`, and `benchmark` profiles and `SUPER_ADMIN`. The request must explicitly
provide the primary and fallback provider/model, distinct external reservation
ids scoped to the caller-supplied `runId`, an exact two-provider allow-list, and one handoff trigger (`ALWAYS`,
`ON_PROVIDER_ERROR`, or `ON_STAGNATION`). Each pass is authorized immediately
before dispatch through `ProviderDispatchAuthorization`.

The worker request sets `strictProvider=true`, so YAML failover cannot silently
introduce a third provider. A fallback pass exists only when the explicit
trigger fires. The response records primary/fallback outcomes and evaluator
status plus final artifact, evaluator, and harness SHA-256 fingerprints.

Verification performed without provider credentials or live calls:

```text
mvn -q -Dtest=ProviderHandoffFixtureHarnessTest,LowCodingFixtureHarnessTest test  PASS
mvn -q -DskipTests compile                                                   PASS
mvn -q spotless:check                                                        PASS
```

This proves route and safety semantics only. It does **not** establish provider
quality, handoff uplift, broad repository quality, or SOTA parity. A future
operator run must reserve and reconcile each provider pass independently and
attach an independent held-out evaluator artifact.
