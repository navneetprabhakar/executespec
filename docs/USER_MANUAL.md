# ExecuteSpec User Manual

> Current manual for the Console control-plane web app. The retired Signal protected chrome is not described here; surviving `frontend/src/signal/` files are public/admin/shared components only.

## Main Navigation

Protected users land in the Console shell (`frontend/src/console/`). The route-level product surfaces render inside that control-plane frame. The shell has a top bar (brand mark, plan-gate indicator, **Start** button, command palette, theme switch, notification bell, account menu), a left navigation rail, and — on phones and tablets — a slide-out drawer plus a bottom tab bar mirroring the same items.

The primary navigation has five entries:

| Nav item | Purpose |
| --- | --- |
| Start | Submit a new spec (the Compose surface). |
| Work | List runs, inspect status, and open the plan, live, and result views. |
| Projects | Manage linked repos, architecture, docs, memory, chat, reviews, and settings. |
| Team | Manage org members and invites. |
| Setup | Everything else: account, billing and plans, integration credentials, provider keys, org settings and model matrix, deployments, and (for operators) admin and the benchmark dashboard. |

Outside the rail, the top bar also exposes the command palette (⌘K) for jumping to any surface, a notification bell with an unread badge, and a per-decision view. Super-admins additionally get an **Operator** section in the left rail (Admin, Benchmarks).

## Submit A Run

1. Open Compose.
2. Enter the spec.
3. Select project/repositories when needed.
4. Optionally choose provider/model/cost-cap controls allowed by your tier.
5. Submit.
6. Review the plan at the plan gate.
7. Approve or reject.
8. Watch live execution.
9. Review the result, report, issues, files, cost, logs, and PR links.

Important behavior:

- Production runs pause at the plan gate.
- Prototype/skip-approval paths exist but are tier/flag dependent.
- Cost caps can lower risk but cannot exceed platform/tier ceilings.
- BYOK orgs skip platform cost caps where the server allows it.

## Read A Result

Run result should answer:

- What was requested?
- What plan was approved?
- Which tasks ran?
- Which files changed?
- Did build/tests pass?
- What did reviewer find or fix?
- What verified score was assigned?
- Which evidence backs that score, and which parts are deterministic vs LLM-
  assessed?
- Was a PR opened?
- What did the run cost?

The Logs tab reads persisted run-event replay where available; it is not only the ephemeral live SSE stream.
The validation report includes a verification ledger; treat `verifiedScore` as a
summary of measured signals, not a substitute for spot-reading the diff and
output examples before external publication.

## Project Workbench

Use Projects when the work needs repository memory or repeated context:

- Link repos.
- Generate architecture.
- Upload reference docs.
- Generate setup/explanation docs.
- Ask project chat questions grounded in docs/code/memory.
- Run code review or PR review.
- Dispatch tech-debt or review-comment autofixes.
- Manage project members and public share links.

See [`features/project-workbench.md`](features/project-workbench.md).

## Models And Keys

Direct providers:

- Anthropic
- OpenAI
- Gemini
- DeepSeek

Aggregators:

- OpenRouter
- Fireworks

FREE users get the configured default model only. TEAM/ENTERPRISE BYOK users can provide supported user/org keys. ENTERPRISE org admins can override the org model matrix.

See [`features/models-and-routing.md`](features/models-and-routing.md).

## Billing And Quota

- FREE has limited monthly runs and a fixed model default.
- TEAM is self-serve, BYOK-capable, and supports 1-20 seats.
- ENTERPRISE is custom and unlocks org-level controls.
- Failed runs refund the monthly run counter through the refund listener.
- Top-up runs can be used where the tier/quota path allows them.

See [`features/billing-and-access.md`](features/billing-and-access.md).

## Sharing

There are two sharing modes:

- Cross-org project membership through invites. Requires org external-sharing flag and role/tier checks.
- Public read-only share links. Separate from the org external-sharing flag.

`PROJECT_VIEWER` is read-only. Write endpoints must use write guards.

## Benchmarks

Benchmark surfaces are operator/dev surfaces. The in-app benchmark dashboard tracks events from external harnesses; it is not the official scorer.

See [`features/benchmarks.md`](features/benchmarks.md).

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Run stuck at plan gate | Open the plan view and approve/reject; check credit confirmation. |
| Provider unavailable | Check user/org/system key resolution and provider support for the selected model. |
| FREE user cannot choose model | Expected; FREE has fixed default model. |
| Project invite accepted but project missing | Check project membership and org/project guard behavior. |
| Preview/deploy failing locally | Check GCP ADC, Docker, and profile-specific env; local warnings may disable preview/archive surfaces. |
| Benchmark dashboard empty | External harness must publish benchmark events into the tracking endpoint. |
