# Provider/model handoff quality gate

Date: 2026-07-15  
Scope: stuck-task detection, bounded provider/model continuation, goal preservation, no-repeat
behaviour, attribution, and spend-safety.

Status: **DETERMINISTIC RUNTIME CONTRACT PASS; LIVE QUALITY UPLIFT NOT PROVEN**

## What was exercised

The following focused suite was run without dispatching an LLM call:

```text
mvn -q -Dtest=GoalContinuationPolicyTest,GoalContinuationAdvisorTest,
  SequentialExecutorSoftCompletionTest,RunLifecycleServiceContinuationApprovalTest test
```

The command completed successfully. The suite covers the continuation seam end-to-end with mocked
workers and build/test results, rather than merely checking candidate ranking in isolation.

| Behaviour | Evidence | Result |
|---|---|---|
| Detect a stuck/zero-write worker | `SequentialExecutorSoftCompletionTest` iteration-limit, stopped-without-complete, repeated-failure, repeated-explanation, and waste-guard cases | PASS |
| Preserve goal state when progress exists | soft-completion and goal-doneness cases | PASS |
| Step up within the same provider | `GoalContinuationPolicyTest`, `GoalContinuationAdvisorTest` | PASS |
| Change provider when current provider behaves poorly | advisor + executor continuation-action cases | PASS |
| Execute exactly one bounded selected-candidate rerun | `continuation_acting_reruns_once_with_selected_candidate_by_default` | PASS |
| Preserve selected provider/model attribution | worker request capture and `TaskOutcome` assertions | PASS |
| Persist executed action and limit usage | continuation action JSON assertions (`provider`, `model`, `usedLimit`, `maxLimit`) | PASS |
| Do not repeat after a ceiling | provider-switch and model-step limit tests; typed `CONTINUATION_ACTION_SKIPPED` | PASS |
| Preserve counters on approval retry | `RunLifecycleServiceContinuationApprovalTest` and scoped confirmation assertions | PASS |
| Block route-pinned or unverified expensive changes | route-pin, benchmark-prior, confirmation, and approval-scope tests | PASS |
| Keep terminal DONE/BLOCKED decisions terminal | policy tests | PASS |

The execution log also showed the intended recovery sequence in the mocked cohort: an
`ITERATION_LIMIT_EXCEEDED`/zero-write primary attempt selected `CHANGE_PROVIDER`, dispatched the
selected OpenAI candidate once, and did not permit a second provider switch after the configured
run-level ceiling. A successful selected rerun ended in `STOP_DONE`; a failed or unverified route
remained bounded and visible as `PARTIAL`/`STOP_BLOCKED` rather than being silently retried.

## What this proves

- The handoff contract is typed, bounded, and auditable.
- Goal evidence is evaluated before escalation, and terminal proof is not replaced by model
  preference.
- Provider/model changes are constrained by availability, quality evidence, route pins,
  confirmation scope, and per-run/per-task ceilings.
- Automatic action history survives the task decision overwrite path and approved retries seed the
  already-consumed counters.

## What this does not prove

- It is not a live provider-quality or handoff-uplift cohort: worker/provider calls were mocked.
- It does not show that switching providers improves success on a broad coding or repository task.
- It does not prove cross-JVM continuation; the restart fixture only proves bounded recovery
  semantics, not an active provider handoff during a JVM replacement.
- It does not establish SOTA parity.

The purported live low-coding cohort remains a separate **unverified offline adjudication** in
[`provider-quality-cohort-live-20260715.md`](provider-quality-cohort-live-20260715.md), and its
manifest explicitly limits the claim to one tiny fixture and three attempts per provider.
