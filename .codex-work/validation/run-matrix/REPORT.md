# DeepSeek Flash live run matrix — 2026-07-22

## Corrected outcome — clean rerun

The first attempt below overlapped a Maven rebuild and is **invalidated as product execution evidence**. A second sequential matrix was run after the backend was confirmed healthy on port 8080, with no Maven commands. The clean rerun still did **not** produce a successful code-generation run, but for a different, explicit environment/policy reason: the classifier recorded `Live LLM transport is disabled. Reserve provider budget before dispatch.` Workers then stopped before the first provider call (`0` tokens, `$0`, no tools or writes). This proves orchestration/gating behavior, but it does not validate live DeepSeek generation.

Clean evidence lives under `clean-rerun/`.

| Clean case | Run | Result |
|---|---|---|
| PROTOTYPE BUILD / LOW | `a34697e8…` | FREE route projected `deepseek-v4-flash-native`; `SINGLE_SHOT` / LOW; terminal FAILED before provider dispatch, 0 tokens/$0. Task row selected WORKHORSE `deepseek-v4-pro-native`, but no call occurred. |
| PRODUCTION BUILD / gate | `e2d3707c…` | Reached `AWAITING_APPROVAL`; `SMALL`, LOW task; exact SHA-256 fingerprint + 1 confirmed credit approval returned HTTP 200; terminal FAILED before provider dispatch, 0 tokens/$0. |
| Generic PATCH request | `876be916…` | Accepted by `POST /api/runs`, but persisted/executed as `runMode=BUILD`; terminal FAILED before provider dispatch. Generic customer submit therefore does not expose PATCH semantics. |

The clean production gate is a pass. Live model execution remains **BLOCKED/NOT VALIDATED**, not failed model quality.

## Invalidated first attempt

The cases in the following table ran while overlapping Maven rebuilds caused `NoClassDefFoundError` for `RunRequestFingerprint`. Their worker failures must not be treated as a product regression. They remain only as historical orchestration artifacts.

## Cases

| Case | Account | Requested | Observed | Result |
|---|---|---|---|---|
| `build-prototype-low` (`4e40f7a1…`) | FREE | PROTOTYPE / BUILD / LOW | Submission projected Flash for planner/worker/reviewer; terminal FAILED, 0 cost | FAIL |
| `build-production-low` (`1273aebf…`) | FREE | PRODUCTION / BUILD / LOW | Classified `SINGLE_SHOT`, task `LOW`; parked at `AWAITING_APPROVAL`; survived backend restart; stale fingerprint returned 409; exact fingerprint + 1 confirmed credit returned 200; worker then FAILED before any LLM call | GATE PASS / EXECUTION FAIL |
| `patch-prototype-low` (`17226050…`) | FREE | PROTOTYPE / PATCH / LOW | API normalized the customer submission to `runMode=BUILD`; `SINGLE_SHOT`; FAILED with `VALIDATION_EVIDENCE_FAILED`, 0 cost | PATCH NOT REACHABLE through generic submit |
| paid exact-pin probes | PRO, TEAM | exact `deepseek-v4-flash-native` | 403: exact per-run model selection requires Enterprise | EXPECTED POLICY; no paid-tier Flash run |
| higher-complexity probe | FREE | PRODUCTION / longer multi-module spec | POST returned 500 after restart/re-login | FAIL; no run allocated |
| Enterprise exact-pin probe | ENTERPRISE | PROTOTYPE / BUILD / exact Flash | fixture login returned 401 after restart | NOT REACHABLE |
| multi-repo | TEAM fixtures | two linked repositories | Every existing TEAM project exposed zero linked repos | NOT REACHABLE |

## Key evidence

- FREE submissions returned `plannerModel`, `workerModel`, and `reviewerModel` as `deepseek-v4-flash-native`.
- The approved LOW task later recorded `model=deepseek-v4-pro-native` in its task row despite the Flash projection. No provider call occurred, but this means the FREE Flash lock is not consistently represented at task routing time.
- Production approval correctly enforced plan identity: a fingerprint accidentally computed with a trailing newline was rejected with 409. SHA-256 over the exact persisted `planJson` was accepted with HTTP 200.
- Approved worker evidence: `iterations=1`, `toolCalls=0`, `fileWrites=0`, all token counters 0, cost `$0`, duration ~83 ms, failure `Worker threw an unrecoverable exception`.
- The continuation advisor proposed OpenRouter after the failure, but benchmark-prior enforcement blocked it. No non-DeepSeek provider was executed.

Raw sanitized requests/responses and run details are in this directory. Credentials and bearer tokens were removed.

## Cleanup

No run remained actively executing. The production gate run terminated FAILED after approval. The PATCH and prototype runs were already terminal FAILED. The failed higher-complexity POST allocated no run. Nothing required cancellation.
