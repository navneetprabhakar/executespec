# Legacy chat route evidence

The compatibility `POST /api/spec/chat` stream now emits a `route_decision` SSE event after
`ack` and before the legacy tool loop. The payload mirrors the v2 agent-session route contract:

- `route`
- `confidence`
- `reason_code`
- `requires_user_confirmation`
- `assistant_reply`
- `trace_id`

The classifier is advisory. Existing chat tools and plan-gate checks remain authoritative, and a
classifier failure is swallowed so older clients still receive their normal stream. The disabled
feature path is unchanged (`ack` + `done` only), avoiding provider work when `spec.chat.enabled`
is off.

Verification: `SpecChatControllerAccessTest` and the existing agent-intent contract tests pass;
no live provider call is used by this change.
