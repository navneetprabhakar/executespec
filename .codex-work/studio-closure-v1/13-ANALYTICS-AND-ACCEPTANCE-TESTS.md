# ExecuteSpec Studio — Analytics and Acceptance Contract

## Event envelope

Every product event includes: studioSurface, tier, personaMode, projectId, taskId where applicable, source, stateBefore, stateAfter, outcome, recoveryUsed, latencyBucket, and redacted errorClass. It excludes prompts, code, secrets, client names, repository contents, and document bodies.

## North-star quality measures

- idea-to-first-visible-preview completion rate and verified latency;
- change-to-fresh-check rate;
- publish success with post-deploy evidence;
- recovery success without project abandonment;
- upgrade-resume continuity;
- casual-to-professional graduation without object loss;
- client-boundary violations: zero;
- false “working/published/verified” claims: zero.

## Acceptance suites

1. Free, Pro, Max complete the same core Project/Task/Output grammar.
2. Every intent gate preserves the current Task through purchase or dismissal.
3. Both desktop panes collapse to icon rails and restore without content loss.
4. Tablet/mobile retain decisions, evidence, publish, rollback, and recovery.
5. All billable/destructive/external/client-boundary actions show target, effect, authority, and recovery.
6. Generated tests are validated before being cited as evidence.
7. CI runs trigger automatically where configured and on demand.
8. BYOK and Max are never conflated.
9. Client A data, context, secrets, memory, and analytics cannot appear in Client B.
10. All 173 requirements resolve to a Figma composition and deterministic assertion.

