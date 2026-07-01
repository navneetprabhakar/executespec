# Coding Agent CLI, TUI, and Web UI Expectations

Date: 2026-07-01

Scope: expectation document and adversarial assessment for a developer/manager using an AI coding product for daily coding, operations work, sprint tasks, greenfield builds, experimentation, continuation, session management, token/cost audit, decision audit, SDLC, and AIDC workflows.

Evidence used: current ExecuteSpec screenshots supplied in the conversation plus source scan of the web agent and CLI/TUI surfaces. This is a product-experience audit, not a backend capability audit.

## Executive Diagnosis

ExecuteSpec has many of the right primitives: chat sessions, durable turns, token/cost telemetry, run cards, approvals, gates, evidence, reports, diffs, local evidence, backend sessions, CLI commands, TUI routes, and audit ledgers.

The experience problem is not absence of machinery. The problem is that the machinery is exposed in the wrong order.

The current experience feels like an internal execution control plane presented to end users. A developer wants a coding workbench. A manager wants a work and risk cockpit. ExecuteSpec currently asks both personas to understand runs, sandboxes, gates, evidence tabs, cost tabs, activity logs, model rankings, route decisions, provider policies, and session scopes before it gives them a clean answer to four basic questions:

1. What are we working on?
2. What did the agent change?
3. Is it correct?
4. What did it cost and who approved it?

Until those four questions are first-class, the product will keep feeling powerful but awkward.

## What Developers Look For In A Coding CLI / TUI

Developers do not primarily want a terminal dashboard. They want a fast, repo-aware working loop.

Core expectations:

- Start in the current repository and understand branch, diff, dependencies, test commands, package manager, and likely task context.
- Let the user type natural language immediately. The first screen should be an input, not a product tour.
- Preserve local muscle memory: keyboard-first, no mouse dependency, no modal maze, no fragile visual chrome.
- Make local versus remote execution obvious, but not dominant.
- Provide ask, plan, implement, review, test, and continue modes without forcing the user to learn platform internals.
- Show a compact live state: model, mode, branch, session, changed files, tests, tokens, credits/cost.
- Handle file references naturally: `@file`, pasted stack traces, selected files, diff context, issue links, terminal output.
- Prefer diffs and test results over chat prose. The answer should become code quickly.
- Make dangerous actions explicit: shell, write, delete, network, secrets, git push, PR creation, deployment.
- Allow interruption and recovery: stop, resume, rewind, fork, continue last, compact context.
- Never lose task context across turns. "As I said" must work inside a session.
- Summarize token/cost usage without making a normal developer stare at dollar COGS unless authorized.
- Support offline-ish/local degraded paths where possible: local grep, local diff, local tests, local evidence.

Manager expectations in CLI/TUI are narrower:

- Quick read-only status: what changed, what failed, what needs approval.
- Approve/reject gates if necessary.
- Export evidence or open web view.
- The TUI should not become the primary manager console.

## What Developers And Managers Look For In A Coding Web UI

The web UI should not try to be a full IDE clone. It should be the command center and review surface.

Developer web expectations:

- Project/session dashboard: active work, last session, pending approvals, recent branches, changed files, failed tests, open PRs.
- Chat that owns artifacts: code blocks become files, files become diffs, diffs become preview/test/PR actions.
- Multi-file output as an artifact tree, not a giant markdown blob.
- Rich preview: generated app, screenshots, logs, test output, terminal snippets, PR diff.
- Continuation: resume previous project, fork a session, continue from failed run, compare model attempts.
- Clear state machine: ideation, planning, waiting approval, implementing, testing, reviewing, done, blocked.
- Token/AI-credit visibility at session, run, task, and model-call levels.
- Audit trail in human terms: user asked, model planned, user approved, files changed, tests ran, PR opened, credits charged.

Manager web expectations:

- Work queue: tasks by owner, project, status, risk, sprint, PR, blocked reason.
- Approvals inbox: plan approvals, expensive model upgrades, deploy approvals, policy exceptions.
- Cost/risk cockpit: AI credits used, remaining, projected burn, anomalous sessions, model/provider mix.
- Evidence view: test status, reviewer result, score, changed files, PR link, who approved what.
- Governance: RBAC, SSO, policy, audit export, provider policy, BYOK status, retention.
- Team visibility without noisy implementation logs.

## Required Functionality List

CLI/TUI:

- `chat` default: immediate prompt in current repo.
- Repo context discovery: branch, status, project type, package manager, test commands.
- Modes: ask, plan, edit, review, test, local-only, remote-sandbox, safe/autonomous.
- Session controls: list, resume, continue latest, fork, compact, rename, archive.
- Context controls: attach files, attach folders read-only, include diff, include command output.
- Code actions: apply patch, inspect diff, revert run, create checkpoint, run formatter, run tests.
- Gate controls: approve/reject plan, approve shell, approve write, approve push, approve model upgrade.
- Evidence: changed files, commands run, tests, screenshots, logs, cost/tokens, export bundle.
- PR flow: create branch, open PR, update PR, show CI, respond to review comments.
- Cost/token: always-visible compact footer; detailed ledger via command.
- Safety: permission policy, allow once/always/session, dry run, command preview.
- Accessibility: plain mode, no-color mode, stable terminal rendering, copyable output.

Web UI:

- Chat command center with persistent session rail and artifact rail.
- Project dashboard with active sessions/runs/PRs/approvals.
- Work queue and sprint/task board.
- Run detail with narrative summary first, raw logs second.
- File artifact tree with expand/collapse default collapsed.
- Multi-file generated output package: apply/copy/download/preview/test per file or group.
- Diff viewer, test viewer, logs viewer, evidence viewer.
- Token/AI-credit ledger at chat, run, task, and provider-call levels.
- Manager approvals inbox.
- Billing and usage dashboard with role-sensitive dollar visibility.
- Audit export: PDF/JSON/CSV.
- Model/provider policy and model matrix visibility.
- Session memory controls: compact, pin context, clear, fork, continue.
- Notifications: blocked, approval needed, run done, budget threshold, tests failed.

## Detailed Experience Expectation

The product should be organized around one atomic object:

`Task Thread = conversation + context + plan + files + tests + approvals + cost + evidence + PR`

Everything else is secondary. Runs, sandboxes, model calls, gates, ledgers, and reports are implementation objects behind the task thread.

Primary developer loop:

1. User opens CLI or web on a project.
2. Product shows latest task/session and a clean composer.
3. User asks for work.
4. Product classifies silently unless it needs clarification.
5. If code changes are needed, product shows plan and estimated credits.
6. User approves or edits plan.
7. Agent changes files.
8. UI streams semantic progress: reading, editing, testing, reviewing.
9. UI shows changed files, test result, token/credit usage, and next action.
10. User can continue, fork, revert, open PR, export evidence, or hand off.

The UI should avoid making the user reason about "run", "sandbox", "remote turn", "route decision", "action contract", "evidence ledger", or "model ranking" unless they deliberately open diagnostics.

## ExecuteSpec Actual Comparison

What exists:

- Web agent has durable sessions, scoped sessions, turns, managed-AI balance, gates, capabilities, active run cards, run polling, live cost polling, usage from turns, and stream event handling.
- Web run detail has tabs for overview, code, steps, timeline, activity log, evidence, cost, PR, tests, and ask.
- Web agent renders assistant code blocks collapsed by default.
- Web agent has a chat drawer, approval drawer, run card, usage pill, managed AI balance, and local handoff.
- CLI/TUI has routes for launcher, session, workspace, runs, evidence, diff, permissions, and settings.
- CLI/TUI has session history, context, compact, fork, backend session dashboard, local evidence index, local diffs, run cost, plan gates, checkpoints, rewind, and permission controls.

Critical gaps:

- Web first impression is "remote sandbox operator", not "coding agent". That is a category error for day-to-day coding.
- The chat space is visually starved. Too much empty space, too little structured artifact surface.
- "New sandbox", "remote sandbox", "plan-gated", "PR-oriented", "local files never touched" dominate the language. This reads like compliance framing, not productivity framing.
- Session usage exists, but the user had to ask where token/credits are. That means discoverability failed.
- Usage currently says "AI credits pending" at session level. That is not good enough after a completed turn.
- Dollar cost is still present in CLI/TUI cost surfaces and done lines; role-sensitive cost language must be consistent everywhere.
- Web code output collapses blocks, but multi-file work is still not represented as a first-class file package.
- Activity log and evidence are separated from the user's mental model. The user wants "what happened", not raw event taxonomy.
- The run result tabs are broad but flat. They are a database schema in navigation form.
- The product leaks internal failure states like STOP_FAILED and model ranking into the user path before translating them into useful decisions.
- TUI breadth is high, but the default experience risks feeling like a terminal admin console rather than a coding assistant.
- CLI/TUI routes are useful, but `/route runs`, `/route evidence`, `/route diff`, `/tasks cost`, etc. should be secondary accelerators, not the core mental model.
- Session memory failure is catastrophic. If a user says "like I said" and the agent cannot use prior turns, the chat product is broken regardless of backend strength.

## Aggressive Assessment

ExecuteSpec currently feels designed from the backend outward. The web UI is showing the orchestration architecture. The CLI/TUI is showing the command inventory. Neither is yet ruthless enough about the user's actual working loop.

The web UI is trying to be Claude, Codex, Linear, GitHub Actions, billing console, audit console, and benchmark dashboard in one shell. That creates breadth, but not confidence. The user has to hunt for meaning.

The CLI/TUI has the opposite risk: it has impressive terminal app coverage, but a coding CLI wins by reducing surface area, not increasing it. Claude Code and Codex feel strong because the center is always the prompt, the diff, the test result, and the next action. Everything else is available, but not loud.

The strongest product opportunity is to stop presenting "runs" and "sandboxes" as the primary unit. Present "tasks" and "sessions". A sandbox is an execution detail. A run is an audit detail. A model route is a diagnostic detail. A task is the product.

## Recommended Product Direction

P0:

- Rename/reframe Web Agent from "remote sandbox operator" to "Work session" or "Agent workspace".
- Make the task thread the primary page: chat + files + evidence + cost + decisions in one layout.
- Add a right artifact rail: Files, Diff, Preview, Tests, PR, Cost.
- Add a left session/task rail: Recent, Active, Waiting Approval, Failed, Done.
- Replace Activity Log with "Work Timeline": asked, planned, approved, edited, tested, reviewed, charged, PR opened.
- Make token/AI-credit usage always visible in the session header and final assistant bubble.
- Convert code blocks into file artifacts with default collapsed preview and explicit file names.
- Support multi-file generated answers as an artifact bundle.
- Hide raw model ranking, STOP_FAILED, provider internals, and dollar COGS behind diagnostics unless super admin.

P1:

- Add a project work queue: tasks, status, branch, PR, risk, owner, sprint, blocked reason.
- Add session resume/fork/compact controls in both web and CLI, visible without command hunting.
- Add "continue last project" and "continue failed run" as primary launcher actions.
- Add manager dashboard: approvals, spend, blocked runs, risk, PR/test state.
- Add evidence export from the task thread, not only run detail.

P2:

- Build role-specific home pages: Developer cockpit, Manager cockpit, Admin cockpit.
- Add model comparison only as an explicit "try another model" workflow.
- Add sprint integration once the core task-thread UX is sane.

## Bottom Line

The product should feel like:

"I gave it a task. It understood my project. It made a plan. I approved. It changed files. It ran tests. It showed me the diff, evidence, PR, and credits. I can continue tomorrow."

Right now it often feels like:

"I started a sandbox, watched internal events, found cost in another tab, saw evidence in another tab, got model/routing artifacts, and had to infer what the agent actually did."

That gap is the experience tax.
