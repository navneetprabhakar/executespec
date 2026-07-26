package com.navneet.executespec.harness;

/** Focused semantic-verification clauses appended to the worker's mandatory test contract. */
final class WorkerVerificationContractPrompt {

  private WorkerVerificationContractPrompt() {}

  static final String TEXT =
      """
  - A missing local runtime, package manager, dependency, service, credential, or sandbox
    capability is an environment limitation, not permission to weaken the requested product.
    Preserve every required language/runtime/framework version, manifest constraint, security
    control, persistence guarantee, and acceptance bar. Use another available verifier only when
    it proves the same contract; otherwise report the exact check as blocked or unverified. Never
    downgrade a version, remove a dependency, relax a test, or rewrite the requirement merely to
    make the current environment green. When a minimum or exact runtime version is required, check
    it before creating environments or installing dependencies and use the matching versioned
    executable when available (for example `python3.12`, not an older generic `python3`).
  - Derive adversarial tests from EVERY semantic clause in TASK_REQUIREMENTS. A symbol with the
    right name is not proof: exercise the documented/default startup path, cross-operation reuse of
    shared idempotency keys, immutable replay responses after later state changes, version mismatch
    through the public request boundary, rollback after a mid-transaction failure, sanitized
    handling of expected persistence failures, and an ordered/versioned later migration applied to
    an already-initialized schema whenever those contracts are assigned to this task. `CREATE IF NOT
    EXISTS` or one baseline DDL file proves initialization, not forward migration.
  - For concurrent idempotency, the lookup, business-state decision/mutation, and first-decision
    persistence must be one atomic critical section/transaction. A race test must force concurrent
    callers to observe the initial miss before either stores; scheduler luck is not proof. Replays
    must consume quota/state once and return the exact original response.
  - Win an atomic database claim for the scoped idempotency key before the business mutation, or
    acquire an equivalent datastore serialization boundary before the initial lookup. Never mutate
    business state and then rely on a later unique-key insert. A concurrent loser must roll back,
    wait/re-read the committed winner, and replay it for an identical payload; expected unique,
    lock, and busy races must be mapped to the documented response, never an intermittent 5xx. For
    SQLite, use a real write transaction or claim-row protocol; SELECT FOR UPDATE is ignored.
  - Composite tenant/subject/operation/idempotency keys must use a collision-safe structured key or
    escaped/length-prefixed encoding. Raw delimiter concatenation (tenant + ":" + subject) is not
    isolation when caller-controlled components can contain the delimiter.
  - "Bounded memory" requires eviction of every live bucket/index/map in the instance serving
    requests, not a detached store created only for a cleanup goroutine. Advance the injected clock,
    trigger eviction, and assert the actual limiter/store size falls. Likewise, cancellation must
    reach blocking core work through context/token propagation; checking only before the call does
    not honor cancellation in flight.
  - An "authenticated tenant" cannot be a raw configurable header copied into context. Tests must
    present a credential that middleware verifies before deriving tenant identity, and must reject
    an attacker-selected tenant header by itself.
  - Preserve complete user journeys across task boundaries. When adding an empty/loading/error
    state or another conditional branch to a shared page, prove that a fresh user can still perform
    every required primary action (for example create the first item without loading demo data).
    Do not make a newly added secondary action the only path into an existing feature.
  - Validate recovery data as a whole invariant, not field-by-field only: required collection
    members must be present exactly once, stable identities must remain unique, and values rejected
    by the public mutation boundary (such as blank titles) must also be rejected on restore.
  - A DOM-shape assertion does not prove responsive layout, visual focus, or contrast. When the spec
    requires those properties, verify the actual CSS rule/computed style or use the project's
    browser/visual accessibility test convention; do not claim a viewport test merely because the
    same elements exist in DOM order at every width.
  - Tests are maintained product artifacts in the conventional test tree; root-level scratch smoke files are forbidden, and one-off reproduction files belong under `/tmp` outside the deliverable.
""";
}
