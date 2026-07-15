# Provider handoff live cohort — blocked at HTTP dispatch (run 2)

Date: 2026-07-15  
Scope: DeepSeek native primary + Anthropic fallback, localization fixture, explicit
`ALWAYS` handoff, exact two-provider allow-list.

## Attempt

The local backend was healthy at `http://localhost:8080/actuator/health` and was
running with the `local` profile. An authenticated SUPER_ADMIN request was prepared
for the operator-only route:

```text
POST /api/v2/test/provider-handoff
runId: handoff-localization-20260715-1784088387
fixtureId: repository-localization-v1
fixtureVersion: v1
primary: deepseek / deepseek-v4-pro-native
fallback: anthropic / claude-sonnet-4-6
allowedProviders: [deepseek, anthropic]
handoffTrigger: ALWAYS
```

The request remained open for approximately 3 minutes 25 seconds with no response
body, no HTTP status, and no output file. A second unauthenticated `{}` probe also
timed out after 5 seconds while the health endpoint continued to return `200`.

## Safety result

Two ledger reservations were created at a conservative `$0.65` safe maximum each:

| Provider | Model | Reservation | State before stop | State after stop |
|---|---|---|---|---|
| DeepSeek | `deepseek-v4-pro-native` | `2eb4f7d2-5596-4d49-a271-38751c30ccc7` | RESERVED | CANCELED |
| Anthropic | `claude-sonnet-4-6` | `a3ddbfb9-01d4-4ac0-9a06-227cb68a78ce` | RESERVED | CANCELED |

Because both reservations were still `RESERVED`, the controller had not reached the
per-pass `authorizeReservedTest` boundary. The canonical ledger therefore records:

- provider calls: **0**
- provider spend delta: **$0.00**
- remaining reservations: **0**
- provider locks/breaches: **none**
- held-out evaluator output: **not produced**
- tokens, cache/reasoning usage, and latency: **not provider-measurable**

## Decision

This is not provider-quality evidence and must not be labelled as a failed coding
attempt. The live paired cohort is blocked by a non-responsive local HTTP route (or
request execution surface) even though the health endpoint is responsive. Do not
dispatch another fixture until the route can return a bounded response and a no-call
reservation/reconciliation smoke proves the execution path.

