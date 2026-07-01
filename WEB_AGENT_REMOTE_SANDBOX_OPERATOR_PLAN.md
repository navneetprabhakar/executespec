# Web Agent Remote Sandbox Operator Plan

Date: 2026-06-29  
Branch: `codex/web-agent-remote-sandbox-ux-plan`  
Primary surface: `frontend/src/ide/IdeAgent.tsx`, `agentPanels.tsx`, `agentState.ts`, `agentSession.ts`  
Backend surface: `src/main/java/com/navneet/executespec/agentsession/*`, run detail APIs, run event bridges

## Product Thesis

Web Agent should not try to be the local-hands TUI. The web product should become the remote
sandbox operator: a user describes work, selects or confirms a repo/project context, creates a
remote sandbox run, watches the sandbox work, reviews plan/cost/evidence, and ships or continues
from a clear outcome.

The rest of ExecuteSpec remains intact:

- CLI and VS Code stay responsible for local-hands workflows and local file/shell permissions.
- Compose, Work, Projects, Setup, Team, Billing, and run detail remain available.
- Web Agent becomes the highest-conversion path into remote sandbox execution, not a parallel
  product shell.

The current UI is too weak for this job. It has the primitives - sessions, gates, route decisions,
action cards, run polling, cost, and provider data - but it presents them as generic chat plus
internal settings. Competitors win because they make the agent's operating mode obvious.

## Implementation Status

Updated: 2026-06-29

Implemented on `codex/web-agent-remote-sandbox-ux-plan`:

- Remote-sandbox launcher empty state and old-transcript dock behavior.
- Typed `create_cloud_run` sandbox proposal card and local-hands handoff card.
- Inline sandbox plan-gate card and richer live sandbox cockpit with phase/evidence/route KPIs.
- Console active-work banner suppression for `/agent`, `/plan`, `/live`, and `/result`.
- Command-palette entries for new sandbox, waiting approvals, and latest sandbox.
- Chats drawer reframed as "Remote sandboxes" with awaiting-approval queue and summary counts.
- Backend session-list contract now includes additive `AgentSessionResponse.latestLinkedRun*`
  fields derived from the latest `agent_turn.linked_run_id`, so cross-session drawer rows can show
  latest sandbox title/status without loading every transcript.
- Frontend reload recovery hydrates the pinned sandbox card from durable
  `AgentTurnResponse.linkedRunId`.
- Mobile agent-bar labels and starter action controls were tightened after screenshot smoke showed
  weak controls under the legacy Signal button reset.
- Second remediation pass from the PDF findings:
  - Historical transcript is collapsed into a compact "Recent answer" disclosure when the latest
    visible turn is settled assistant history, so the launcher/proposal/approval surface owns the
    first viewport without hiding a newly submitted user prompt.
  - A persistent pre-run sandbox contract appears in the launcher, proposal card, and composer:
    isolated remote clone, branch shown in the plan, detected tests, PR target confirmation, cost
    cap before run, and no local files touched.
  - Plan-gate cards and the drawer approval inbox now show project context, provider/model route,
    risk, task count, submitted age, credits, estimated cost, hard cap, and inline approve/reject
    controls.
  - Trust-boundary details are visible before approval: selected-project repo token scope, secrets
    not shown in chat, audit-trail persistence, and approval before code execution.
  - Settings now lead with "Sandbox policy" and user-facing labels; provider/capability/route
    internals moved into an "Operator diagnostics" disclosure.
  - Managed AI copy is renamed to "Managed AI balance"; when managed credits are not configured it
    renders an explicit policy state instead of the broken-looking `0/0` balance.
  - The `/agent` mobile top CTA now reads "Sandbox" instead of an ambiguous plus-only affordance,
    and the top bar shows a pending-approval pill when gates exist.
  - Project/run agent routes now refuse to reuse an all-project selected session; scoped routes only
    hydrate matching scoped sessions or create a fresh scoped WEB session, preventing stale
    non-run proposals from failing later at confirmation time.
  - WEB streaming read-only turns now use a read-only system prompt in `SpecChatService`, so the
    agent no longer advertises or complains about unavailable legacy run-control tools when run
    creation should happen through the structured `create_cloud_run` proposal path.

Known remaining API gap:

- The current `GateDecisionCard` backend DTO does not expose a requester/owner field. The UI cannot
  truthfully show "owner" in the approval queue until the backend read model adds that field, so the
  remediation uses project, route, age, risk, cost/cap, and task evidence instead of inventing an
  owner label.

Still required before goal completion:

- Run the broader local verification matrix manually.
- Redeploy the dev target via the local deploy path; do not use GitHub CI.
- Run the full deployed screenshot-backed persona test with stricter coverage of launcher,
  proposal, gate, approval inbox, settings, command palette, mobile, run cockpit, and error/empty
  states.
- Produce the final PDF report with remediation matrix, persona verdicts, adversarial findings,
  screenshots, explicit evidence limits, and remaining release blockers.

## Evidence Used

- Current code on `origin/main`:
  - `frontend/src/ide/IdeAgent.tsx`
  - `frontend/src/ide/agentPanels.tsx`
  - `frontend/src/ide/agentState.ts`
  - `frontend/src/ide/agentSession.ts`
  - `src/main/java/com/navneet/executespec/agentsession/AgentActionCatalog.java`
  - `src/main/java/com/navneet/executespec/agentsession/dto/AgentSessionDtos.java`
  - `src/main/java/com/navneet/executespec/agentsession/AgentRunEventBridge.java`
- Prior screenshot audit artifacts:
  - `output/pdf/ExecuteSpec_Web_UI_Persona_Audit_2026-06-29.pdf`
  - `output/web-ui-persona-audit-2026-06-29/notes.md`
- Competitor references:
  - OpenAI Codex Web: `https://developers.openai.com/codex/cloud`
  - Claude Code: `https://code.claude.com/docs/en/overview`
  - Claude Code GitHub Actions: `https://code.claude.com/docs/en/github-actions`
  - Cursor Agent / Bugbot: `https://cursor.com/docs/agent/overview`, `https://cursor.com/docs/bugbot`
  - Devin: `https://docs.devin.ai/get-started/devin-intro`
  - Replit Agent: `https://docs.replit.com/replitai/agent`

## Current-State Diagnosis

### What Is Good Enough To Keep

- `IdeAgent` already has durable sessions, route decisions, run proposal confirmation, plan-gate
  approval, active-run polling, cost polling, file attachments, and scoped `/agent`,
  `/projects/:id/agent`, `/runs/:id/agent` routes.
- `AgentActionCatalog` keeps WEB capabilities separate from CLI/VSCODE local-only capabilities.
  This is correct and should not be broken.
- `RunCard` already carries phase, current task, progress, cost, Open, PR, and Stop.
- `GateCard` already carries task count, credits, PR consequence, approve, and reject.
- The Console shell already has Start, Agent, Work, Projects, Team, Setup, live recent work, command
  palette, and mobile bottom nav.

### What Is Currently Bad

- The default `/agent` state reads like a chat transcript, not a remote sandbox operating console.
- Old sessions can become the hero state, which is a conversion killer for a new user evaluating
  the agent.
- "Start a cloud run" is weak positioning. Users need to understand "remote sandbox" before they
  trust the flow.
- The run card is too small and passive for the main job. It does not show enough live work,
  evidence, changed files, tests, review status, or next best action.
- Plan gate and post-run evidence are split across cards/routes. The agent does not feel like it is
  owning the job end to end.
- Settings hide important operator facts under internal terms like capabilities, route confidence,
  and Run API.
- Mobile works but wastes prime vertical space and pushes proof/actions below the fold.
- Competitors frame the mode better:
  - Claude Code: direct tool/permission visibility.
  - Codex Web: task delegation and PR flow clarity.
  - Cursor: background agent + IDE context.
  - Devin: autonomous workspace with shell/browser/IDE cues.
  - Replit Agent: plan/build/test/deploy/checkpoint immediacy.

## North-Star Journey

The target loop for Web Agent:

1. User lands on `/agent`.
2. UI makes the mode explicit: "Remote sandbox operator."
3. User enters work or chooses a high-intent starter.
4. Agent resolves project/repo context or asks one focused question.
5. UI presents a remote sandbox proposal with scope, repo, branch, model/provider, cost cap,
   approval policy, and expected artifact.
6. User confirms creation.
7. Plan gate appears as a decision checkpoint with tasks, risk, cost, and PR/deploy consequence.
8. User approves.
9. Live sandbox cockpit shows phase, current task, latest tool, changed files, tests/build/reviewer
   evidence, cost/tokens, and stop/attach/open controls.
10. Terminal state shows verdict, diff/evidence/report/PR/deploy actions, and clear next step.
11. User can ask follow-up questions in the same run-scoped agent session.

## Recommended Fixes

### P0 - Reframe `/agent` Around Remote Sandboxes

Owner files:

- `frontend/src/ide/agentPanels.tsx`
- `frontend/src/ide/agentState.ts`
- `frontend/src/ide/IdeAgent.tsx`
- `frontend/src/ide/ide.css`

Changes:

- Replace generic empty state with a remote sandbox launch state:
  - Headline: "Run work in a remote sandbox."
  - Subcopy: "Plan, code, test, review, and open a PR without touching your local machine."
  - Primary prompt examples:
    - "Implement a small backend change and open a PR"
    - "Fix a failing test in this project"
    - "Review this plan before creating a sandbox"
  - Secondary actions:
    - "Use current project"
    - "Pick project"
    - "View waiting approvals"
- Do not default to an old transcript as the first visual object on `/agent`.
  - If a session is selected, show a compact "Recent session" chip, not a full historical transcript
    above the launcher.
  - Preserve chat history in the drawer; do not erase it.
- Rename visible `create_cloud_run` presentation from "Start a cloud run" to "Create remote
  sandbox run."
- Replace settings labels:
  - `Run API` -> `Sandbox creation`
  - `Confidence` -> hide by default; expose only in an "Diagnostics" disclosure.
  - `What it can do` -> `Available actions`.

Acceptance checks:

- First screenshot of `/agent` shows the remote sandbox thesis above the fold.
- A new user can identify what will happen before sending a prompt.
- Old transcript no longer dominates the first viewport.
- `IdeAgent.test.tsx` pins the new empty state and copy.

### P0 - Build A Sandbox Proposal Card

Owner files:

- `frontend/src/ide/agentPanels.tsx`
- `frontend/src/ide/agentState.ts`
- `src/main/java/com/navneet/executespec/agentsession/dto/AgentSessionDtos.java` if extra proposal
  fields are needed
- `src/main/java/com/navneet/executespec/agentsession/AgentTracePayloadFactory.java` if route proof
  needs to be exposed

Changes:

- Split `ActionCard` into clear variants:
  - `SandboxProposalCard` for `create_cloud_run`.
  - `LocalHandoffCard` for `local_tool_action` / `local_code_task`.
  - `GenericActionCard` only for future low-volume actions.
- Sandbox proposal card must show:
  - Spec summary.
  - Project/repo context.
  - Execution target: Remote sandbox.
  - Cost model: "No run credits charged until plan approval."
  - Approval policy: "You approve the plan before coding starts."
  - Expected artifact: PR / patch / report.
  - Provider/model when known.
  - Primary CTA: "Create sandbox plan."
  - Secondary CTA: "Edit request."
- If project context is missing, the card should stop and ask for project selection rather than
  creating a vague run.

Acceptance checks:

- Explicit code-change prompts produce a proposal card, not a raw chat answer.
- The card explains cost and approval before user clicks.
- The user can reject/edit before sandbox creation.
- Unit tests cover cloud-run proposal rendering and non-run/local handoff rendering.

### P0 - Upgrade The Live Sandbox Cockpit

Owner files:

- `frontend/src/ide/agentPanels.tsx`
- `frontend/src/ide/agentState.ts`
- `frontend/src/ide/IdeAgent.tsx`
- `src/main/java/com/navneet/executespec/agentsession/AgentRunEventBridge.java`
- `src/main/java/com/navneet/executespec/agentsession/AgentValidationReportEventBridge.java`
- Existing run detail/cost/report APIs

Changes:

- Replace current compact `RunCard` with `SandboxRunCard`:
  - Title and status.
  - Remote sandbox badge.
  - Phase timeline: Queued -> Planning -> Approval -> Coding -> Build -> Review -> Evidence -> Done.
  - Current task and progress.
  - Latest tool or event, if available.
  - Changed files count, if available.
  - Build/test/reviewer/evidence status, if available.
  - Live cost and token usage.
  - Primary actions by phase:
    - Awaiting approval: Review plan, Approve, Reject.
    - Running: Open live view, Stop.
    - Complete: Review result, View evidence, View PR, Download report.
    - Failed/needs attention: Open logs, Retry/continue if available.
- Bridge or read enough event evidence so the cockpit can show more than run status:
  - Already bridged: task-wave, goal decisions, continuation decisions/actions, validation evidence.
  - Missing for cockpit: latest tool call, changed files, build/test progress, reviewer pass/fail.
  - If missing event data cannot be safely exposed in one pass, use run detail/report polling for
    terminal proof and leave latest-tool as a follow-up.

Acceptance checks:

- During a running sandbox, the card shows current phase and useful next action.
- On terminal runs, card shows evidence actions before generic actions.
- No raw JSON appears in chat.
- Tests cover run card phase/action matrix.

### P1 - Add A Plan Gate Decision Sheet

Owner files:

- `frontend/src/ide/agentPanels.tsx`
- Existing `IdePlan` can remain the full page.

Changes:

- Keep full `/plan/:id`, but let Web Agent render an inline decision sheet:
  - tasks
  - risk
  - credits/cost
  - provider/model
  - branch/PR target if known
  - Approve & run
  - Reject
  - Open full plan
- Do not make users leave the agent just to understand the gate.

Acceptance checks:

- A plan waiting at the gate is understandable inline.
- Approving from the agent updates the active run card and de-dupes the gate list.

### P1 - Conversion And Navigation Cleanup

Owner files:

- `frontend/src/console/ConsoleShell.tsx`
- `frontend/src/console/AnnouncementBanner.tsx`
- `frontend/src/ide/agentPanels.tsx`
- `frontend/src/ide/IdeAgent.tsx`

Changes:

- Collapse or hide the announcement banner on `/agent`, `/live/:id`, `/plan/:id`, and mobile work
  surfaces after first view.
- Make the top-bar CTA context-aware:
  - On `/agent`: "New sandbox"
  - On other pages: keep "Start"
- Add command palette commands:
  - "Agent: New sandbox"
  - "Agent: Waiting approvals"
  - "Agent: Open latest sandbox"
  - "Run: View evidence"
  - "Run: Open PR"
- Improve chats drawer:
  - section by Active sandbox, Waiting approval, Recent chats.
  - show run status when session is run-scoped.

Acceptance checks:

- Mobile `/agent` first viewport contains prompt, mode, and primary action.
- Command palette supports the sandbox workflow.
- Announcement does not steal active-work pixels.

### P1 - Terminal State Evidence First

Owner files:

- `frontend/src/ide/agentPanels.tsx`
- `frontend/src/ide/IdeRunResult.tsx` only if link targets or tab names need adjustment.

Changes:

- For complete/failed sandbox cards, order actions:
  1. Evidence
  2. Changed files / code
  3. PR
  4. Report PDF
  5. Deploy / Preview, when available
- Add a short verdict strip:
  - Verified score or "No verified score."
  - Build/test/reviewer state.
  - Known caveat if patch/benchmark run skipped validation.
- Avoid optimistic language when evidence is missing.

Acceptance checks:

- A skeptical user can decide whether to trust the run from the card without hunting through tabs.

### P2 - Sharpen Visual Hierarchy Without Redesigning The Whole App

Owner files:

- `frontend/src/ide/ide.css`
- `frontend/src/ide/agentPanels.tsx`

Changes:

- Keep the existing dark Console aesthetic.
- Use stronger section hierarchy:
  - mode strip
  - proposal card
  - cockpit card
  - evidence footer
- Avoid marketing cards, decorative gradients, and oversized hero sections.
- Use compact icons from the existing icon set or lucide if already available in the frontend.
- Keep card radius <= existing system radius.

Acceptance checks:

- New UI still looks like ExecuteSpec, not a separate product.
- Dense but scannable on 1280px desktop and 390px mobile.

## Backend/API Work Likely Needed

The first implementation pass can be mostly frontend. Backend changes should be added only where
current APIs cannot support the remote sandbox cockpit.

Recommended backend additions, in priority order:

1. `AgentRunEventBridge`: include more run event types in run-scoped agent sessions:
   - `TOOL_CALL`
   - `TASK_STARTED` / `TASK_COMPLETED` if emitted
   - `BUILD_RESULT` / `TEST_RESULT` if emitted
   - `REVIEWER_RESULT` if emitted
2. Read-model helper for agent run cockpit:
   - `GET /api/agent/runs/{runId}/cockpit` or extend existing run detail if already sufficient.
   - Must be guarded by existing run read access.
   - Should return stable display fields, not raw event JSON.
3. Ensure validation evidence is available immediately after terminal state:
   - If report generation is async, cockpit should show `Evidence pending` with refresh behavior.
4. Do not expose local-only capabilities to WEB.
   - `AgentActionCatalog` is currently correct: `local_code_task`, `local_git_diff`,
     `local_git_status`, and `resolve_project` are CLI/VSCODE only.

Backend non-goals:

- Do not let browser/JWT local execution bypass PAT/local client restrictions.
- Do not add web shell permissions.
- Do not let Web Agent execute local file operations directly.

## Implementation Slices

### Slice 1 - Positioning And Empty State

Ship first because it changes conversion without backend risk.

Tasks:

- Add remote sandbox empty state.
- De-emphasize historical transcript on `/agent`.
- Rename cloud-run action copy.
- Update settings copy.
- Add tests.

Validation:

- `npm --prefix frontend run format:check`
- `npm --prefix frontend run lint`
- focused frontend test for `IdeAgent`
- Browser screenshots desktop/mobile for `/agent`.

### Slice 2 - Proposal And Gate Cards

Tasks:

- Split action cards by action type.
- Add `SandboxProposalCard`.
- Add richer inline gate decision sheet.
- Preserve local handoff card for CLI/VS Code paths.
- Add tests for proposal/gate states.

Validation:

- Existing agent tests.
- Browser route test for explicit code-change prompt.
- Confirm no run is created before explicit confirmation.

### Slice 3 - Sandbox Cockpit

Tasks:

- Replace run card with `SandboxRunCard`.
- Add phase/action matrix.
- Add evidence-first terminal state.
- Pull from `getRun`, `getLiveCost`, `getRunReport` as needed.
- If current event data is insufficient, add a narrow backend read model.

Validation:

- Frontend reducer/helper tests for status -> phase/action.
- Backend tests if a new cockpit endpoint or event bridge is added.
- Manual run from proposal through plan gate to terminal state.

### Slice 4 - Conversion Navigation And Mobile

Tasks:

- Context-aware top-bar CTA.
- Command palette additions.
- Announcement suppression on work routes/mobile.
- Mobile compact cockpit layout.
- Chats drawer grouping.

Validation:

- Desktop screenshot pass.
- 390px mobile screenshot pass.
- Keyboard smoke: command palette, send, approve, stop/open.

### Slice 5 - Deploy And Persona Test

Tasks:

- Build frontend.
- Run focused backend tests if backend changed.
- Deploy to dev.
- Re-run comprehensive persona test on deployed dev.
- Generate a new screenshot-backed PDF.

Validation:

- No GitHub CI. Manual local commands only.
- Deployed `dev.executespec.ai` smoke.
- Browser screenshots for all persona journeys.

## Manual Test Matrix After Fixes

### Functional Journeys

1. New visitor/login path:
   - Login screen.
   - Post-login `/agent`.
   - First-run remote sandbox launcher.
2. Code-change prompt:
   - enter explicit implementation request.
   - verify sandbox proposal.
   - confirm.
   - inspect plan gate.
   - approve.
   - watch run cockpit.
3. Plan-only prompt:
   - ask for a plan/review.
   - verify no run is created.
   - verify route stays read-only or proposal-only.
4. Existing run:
   - open `/runs/:runId/agent`.
   - ask about evidence.
   - verify run-scoped answer and evidence links.
5. Project-scoped agent:
   - open `/projects/:id/agent`.
   - ask project/code question.
   - verify project context and sandbox proposal path.
6. Failure/needs-attention run:
   - inspect failed or partial run.
   - verify cockpit shows logs/evidence/next action, not false success.
7. Mobile:
   - `/agent`, proposal, gate, running card, terminal card.

### Local Commands

Frontend:

```bash
npm --prefix frontend run format:check
npm --prefix frontend run lint
npm --prefix frontend run build
```

Backend if touched:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -q -Dtest='*Agent*Test,*Orchestrator*Test,*Run*Test' test
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn compile -q
```

Deploy:

```bash
# Use the repo's existing dev deploy path, manually.
# Do not run GitHub CI.
```

## Persona Review Protocol After Deployment

Every persona must be adversarial. Each should try to disprove the product, not confirm it.

### Persona A - Solo Developer Replacing Claude Code

Task:

- "Implement a small backend change in a repo, test it, and show me exactly what changed."

Adversarial questions:

- Do I understand where code will run?
- Do I know whether my local machine is touched?
- Can I see commands/tools and proof?
- Is the diff/evidence easier or harder than Claude Code?
- Would I trust this after one failed run?

Pass bar:

- User can create and monitor a remote sandbox without reading docs.
- Evidence and next actions are obvious.

### Persona B - Senior Engineer Reviewing A Remote Run

Task:

- "Review this run and decide if it is safe to merge."

Adversarial questions:

- Can I find files changed, tests run, reviewer findings, and PR status quickly?
- Does the UI distinguish deterministic proof from LLM claims?
- Are missing scores or skipped validation explained honestly?
- Is the run cockpit better than opening the PR directly?

Pass bar:

- Evidence is visible above the fold or one click away.
- Weak proof is labeled, not hidden.

### Persona C - Engineering Manager Evaluating Team Workflow

Task:

- "Can this become my team's coding-agent control plane?"

Adversarial questions:

- Can I see work in progress, blocked gates, failures, costs, and ownership?
- Is the Agent a convincing first stop, or do I still have to train users around Work/Compose?
- Does it create confidence faster than Codex Web/Devin?
- Are conversion CTAs sharp enough?

Pass bar:

- Agent explains itself and routes users to the right decision without handholding.

### Persona D - Security/Platform Owner

Task:

- "Decide whether browser-based remote sandbox execution is safe enough to allow."

Adversarial questions:

- Are local-vs-remote boundaries explicit?
- Are destructive/billable actions confirmation-gated?
- Are provider, cost, repo, branch, and artifact boundaries visible?
- Is there any path where web gets local file/shell authority?

Pass bar:

- The UI makes safety constraints visible and the backend contract enforces them.

### Persona E - Startup Buyer / Conversion Reviewer

Task:

- "Land, understand value, create first sandbox, and decide whether to pay."

Adversarial questions:

- Is the first screen concrete?
- Does it show outcome, not platform internals?
- Does it reduce fear around cost and repo access?
- Is the path shorter than Codex Web/Replit Agent?

Pass bar:

- First meaningful action is obvious within 10 seconds.

## Separate Adversarial UX Review

Compare ExecuteSpec against:

- OpenAI Codex Web
- Claude Code
- Cursor Agent/Bugbot
- Devin
- Replit Agent

Scoring dimensions:

| Dimension | Question |
| --- | --- |
| Mode clarity | Do users understand whether work is local, remote, or cloud? |
| First action | Is the first high-value action obvious? |
| Trust | Are permissions, costs, repo/branch, and artifact boundaries visible? |
| Live work | Can users see what the agent is doing right now? |
| Evidence | Are diff, tests, review, PR, report, and failure reasons easy to find? |
| Recovery | Can users stop, retry, continue, or inspect failure without guessing? |
| Mobile | Can a user monitor/approve safely on mobile? |
| Conversion | Does the surface make the product feel inevitable or confusing? |

UX review output:

- PDF report with screenshots.
- Competitor comparison table.
- Persona-by-persona adversarial notes.
- UX-specific severity list:
  - P0 conversion blockers
  - P1 trust blockers
  - P2 polish/accessibility
- Clear answer: "Can Web Agent compete as a remote sandbox operator today?"

## Success Metrics

Pre-launch success is manual and evidence-based:

- New user can describe Web Agent's execution mode after 10 seconds on `/agent`.
- Explicit coding prompt produces a proposal, not silent run creation.
- User can answer "What will this cost and when am I charged?" before approval.
- During run, user can identify phase/current task/cost/next action.
- On terminal state, evidence beats generic deploy/share actions in the hierarchy.
- Mobile approval path does not require hunting below multiple screens.

Post-launch product metrics to instrument:

- `/agent` visit -> first prompt submit.
- first prompt -> sandbox proposal shown.
- proposal shown -> confirmed.
- confirmed -> plan gate opened.
- plan gate opened -> approved.
- approved -> terminal run.
- terminal run -> evidence viewed.
- terminal run -> PR viewed/pushed/deployed.
- abandonment at each step.

## Risks And Mitigations

- Risk: Web Agent becomes a second Compose page.
  - Mitigation: proposal and cockpit are chat-native; Compose remains advanced/manual.
- Risk: Product over-promises local control in browser.
  - Mitigation: all copy says remote sandbox; local-hands remains CLI/VS Code.
- Risk: More event data requires broad backend work.
  - Mitigation: start with existing `getRun`, `getLiveCost`, `getRunReport`; add a cockpit endpoint only
    if necessary.
- Risk: UI gets busier.
  - Mitigation: progressive disclosure; first viewport shows mode, prompt, proposal/cockpit only.
- Risk: Competitor comparison becomes marketing theatre.
  - Mitigation: adversarial review must use screenshots and current product docs, and must call failures
    plainly.

## Explicit Non-Goals

- No browser local shell/file permission system.
- No IDE clone.
- No decorative landing-page redesign.
- No marketplace or advanced policy screens.
- No broad backend refactor unless cockpit data is impossible with existing APIs.
- No GitHub CI for validation; run local/manual gates and deploy manually.

## Definition Of Done For The Follow-Up Implementation

- Code changes are implemented on a branch cut from `main`.
- Documentation is updated with each major implementation slice.
- Local frontend gates pass.
- Backend focused tests pass if backend changed.
- Dev deploy succeeds.
- Deployed Web Agent can complete the remote sandbox journey through a real run or clearly named
  live-data substitute if external provider/run capacity blocks a full run.
- Comprehensive persona test is rerun on deployed dev.
- Separate adversarial UX review is completed.
- Final PDF contains screenshots from the fixed deployed UI, persona findings, competitor comparison,
  and severity-ranked recommendations.
