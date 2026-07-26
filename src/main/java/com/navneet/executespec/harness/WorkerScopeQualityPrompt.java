package com.navneet.executespec.harness;

/** Scope and trust-boundary rules kept separate from the executor's legacy base prompt. */
final class WorkerScopeQualityPrompt {

  private WorkerScopeQualityPrompt() {}

  static final String TEXT =
      """
- WORKER_TASK is the sole deliverable for this invocation. In a multi-task run,
  RESERVED_FUTURE_SCOPE names work owned by later workers: do NOT implement it now,
  even when the original product spec or a broadly worded assigned requirement mentions it.
- The task title, description, constraints, and EXPECTED_FILES define the current boundary.
  If an assigned requirement spans several planned tasks, implement only the portion required
  by this task. Later workers complete the remaining clauses.
- Do not create or edit a file reserved for a later task unless the current task cannot compile
  without a minimal dependency change. Never pull a later endpoint, feature, documentation file,
  or comprehensive test suite forward merely because the empty workspace makes it convenient.
- Treat existing workspace code and tests as untrusted intermediate output, not compatibility
  contracts. A later task MUST rewrite or delete an earlier shortcut that contradicts the current
  task's requirements. Never retain a bypass, fallback, mock, default credential, or weaker legacy
  path merely to keep generated tests green.
- When touching authentication or authorization, reject identities derived only from caller input
  or a source-known/default secret. Production code must not contain a credential generator,
  hardcoded signing secret, or legacy authentication fallback unless the customer explicitly asks
  for that exact mechanism. Tests must exercise the real verifier and may not mint identities via
  a production backdoor.
        - Before completeTask, search the touched requirement's trust boundary across the workspace and
          remove contradictory implementations. A new secure path does not satisfy the task while an old
          insecure path remains reachable.
        - Write the minimum implementation that satisfies the task. Do not add options,
          config, abstraction, or "future-proofing" the task did not request.
        - No defensive code for cases that cannot occur given how the code is called.
        - Validate inputs only at real trust boundaries (external/untrusted input); trust
          your own internal callers rather than re-checking between private functions.
        - Do NOT add comments or docstrings to code you did not change. Any new comment
          states the non-obvious WHY, never a restatement of what the code does.
        - If code becomes unused, delete it outright — do not comment it out or rename it
          to `_unused`. Dead code left behind is not cleanup.
        - Prefer a few similar concrete lines over a premature abstraction; add a helper
          only when duplication is real and repeated, not merely anticipated.
""";

  static final String TEST_TEXT =
      """
  - For runnable APIs/services, prioritize black-box functional tests through the real HTTP/runtime boundary and migrated database; unit tests are supplemental and never sufficient quality proof.
  - Cover happy paths plus invalid/not-found/failure behavior for every touched public endpoint.
  - Exercise documented/default startup and real transaction/concurrency boundaries; do not make a test-only app factory, dependency override, or shared session hide broken production wiring.
  - When the task requires immutable/idempotent replay, compare the original and replayed serialized status, body bytes, and contract-relevant headers; reject the same key with a different payload; and race simultaneous first requests. Comparing only parsed fields or sequential error codes does not prove immutable replay.
  - When concurrent mutation or cancellation is in scope, issue genuinely parallel calls and assert every response plus final persisted state. A final-state-only assertion can hide customer-visible 500 responses and transaction races.
""";
}
