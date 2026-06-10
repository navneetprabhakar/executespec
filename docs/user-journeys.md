# User Journeys

> System-wide journeys that are not scoped to one project.

## Journey 1 - Signup To First Run

1. User signs up through the public auth page.
2. Backend creates the user, personal org, and free subscription state.
3. User lands in the protected IDE workspace.
4. User opens Compose and submits a spec.
5. Backend creates a run and opens the plan gate.
6. User approves.
7. User watches live execution.
8. User reviews result, score, logs, cost, and PR links.

Expected constraints:

- FREE users get the configured free-tier model only.
- Monthly run quota applies.
- Production runs require plan approval.

## Journey 2 - BYOK Setup

1. User opens API key settings.
2. User adds supported provider keys.
3. Backend stores encrypted keys on user or org according to endpoint/tier.
4. A future run resolves keys in user/org/system order where allowed.

Supported providers in current key lanes:

- Anthropic
- OpenAI
- Gemini
- OpenRouter
- DeepSeek
- Fireworks

See [`features/models-and-routing.md`](features/models-and-routing.md).

## Journey 3 - Upgrade To TEAM

1. User opens Billing/Plans.
2. User selects TEAM and seat count.
3. Gateway checkout starts.
4. Webhook or checkout completion activates subscription.
5. User/org tier and billing subscription stay aligned.

TEAM supports 1-20 seats.

## Journey 4 - Org Admin Model Matrix

1. ORG_ADMIN opens Org settings.
2. Backend returns effective matrix: YAML defaults plus org overrides.
3. Enterprise org admin updates sparse override cells.
4. Backend validates model cells against registered provider matrix.
5. Future runs resolve overrides at run start.

FREE users do not get this matrix.

## Journey 5 - External Project Invite

1. Org admin enables external sharing.
2. Project admin/admin invites an external email with project role.
3. Invitee accepts with matching email.
4. Backend creates `ProjectMember`.
5. Project appears in the invitee's project list.

`PROJECT_VIEWER` remains read-only.

## Journey 6 - Account And Compliance

Users can:

- manage auth/session state,
- create/revoke PATs,
- export user data,
- update integration credentials,
- review notifications.

Operators can:

- inspect payment audit,
- manage feature flags,
- run local/dev benchmark/test endpoints,
- inspect benchmark dashboard.
