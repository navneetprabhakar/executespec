# ExecuteSpec UI/TUI Deep Gap Register

Date: 2026-07-01

Audit mode: combined UX, developer experience, manager experience, accessibility-risk, and product-operating-model audit.

Evidence:

- User-provided authenticated screenshots from 2026-07-01 covering web agent chat, run steps, evidence, cost, and activity log.
- Current unauthenticated screenshot captured to `output/ux-audit-20260701/screenshots/01-web-agent.png`.
- Source inspection of current web agent, run result, live run, markdown rendering, CLI Ink TUI, OpenTUI app, app-route surfaces, cost/evidence/session surfaces, and terminal split-footer code.

Evidence limits:

- The in-app browser redirected to login, so authenticated web states were not freshly captured through Browser in this pass.
- Authenticated UI findings rely on screenshots explicitly provided by the user plus source inspection.
- Accessibility findings are risks, not WCAG certification. Keyboard/focus/screen-reader behavior still needs live testing.

## Audit Steps

1. Unauthenticated entry/login: acceptable visual polish, but copy still centers "gate" and "verified pull request" before the user has experienced the product. Health: mixed.
2. Web agent authenticated chat from supplied screenshots: high capability, weak hierarchy, cramped/sparse conversation layout, artifact handling underdeveloped. Health: poor for day-to-day coding.
3. Web run/live/result surfaces from supplied screenshots and `IdeRunResult`/`IdeSurfaces`: broad telemetry, weak narrative, too many tabs, internal state leakage. Health: mixed.
4. Cost/token/credit surfaces from source and screenshots: data exists, but discovery and role-sensitive presentation are inconsistent across web and CLI. Health: poor-to-mixed.
5. CLI/TUI source review: impressive command and route coverage, but default experience risks becoming a terminal control panel rather than a coding cockpit. Health: mixed.
6. Manager/sprint/SDLC coverage source review: pieces exist for projects, PRs, Jira connection, approvals, evidence, billing; no coherent manager operating surface. Health: incomplete.

## Severity Scale

- S0: product-blocking trust/workflow failure.
- S1: major adoption or daily-use failure.
- S2: important workflow gap or repeated friction.
- S3: polish, clarity, or longer-term improvement.

## Cross-Cutting Product Model Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| PM-01 | S0 | Primary noun is wrong: product exposes "sandbox/run" instead of "task/session/work item". | Web copy says "Remote sandbox operator", "New sandbox", "Remote sandbox run"; run tabs dominate. | Users feel they are operating infrastructure, not delegating coding work. | Make `Task Thread` the top-level product object: request, context, plan, files, tests, approvals, cost, evidence, PR. |
| PM-02 | S0 | UX is built from backend architecture outward. | Separate surfaces for agent, work, run result, cost, evidence, activity, ask. | Users must integrate meaning themselves. | Collapse the core work loop into one task workspace with progressive disclosure. |
| PM-03 | S0 | Chat memory failure is fatal. | User screenshot shows "I don't see prior context" after "like I said". Source passes only last 8 turns plus summaries. | "Same experience as Claude/Codex" is impossible if session memory is unreliable. | Treat session continuity as a product invariant: visible context panel, pinned facts, failed-memory diagnostics, and tests for multi-turn reference. |
| PM-04 | S1 | Product tries to serve developer, manager, admin, benchmark operator, and billing admin in one shell. | Sidebar includes Agent, Work, Projects, Team, Setup, Admin, Benchmarks. | No persona has a clean cockpit. | Split by role: Developer cockpit, Manager cockpit, Admin cockpit, Operator/benchmark console. |
| PM-05 | S1 | "Complete SDLC" is present as pieces, not as a journey. | PR, tests, evidence, preview, deploy, chat, project docs all exist in different locations. | Users cannot tell where they are in the lifecycle. | Add SDLC timeline: Idea -> Plan -> Branch -> Edit -> Test -> Review -> PR -> CI -> Deploy -> Retro/Evidence. |
| PM-06 | S1 | AIDC story is not visible to users. | Evidence ledger exists, token/cost exists, decision events exist, but no unified audit contract. | Enterprise users cannot quickly trust who/what/why/cost. | One audit ledger per task: model, tool, file, command, approval, token, credit, policy, PR, test, deploy. |
| PM-07 | S1 | Internal safety language overwhelms productivity language. | Trust panels and composer contract repeat "remote", "approval", "local files", "sandbox policy". | It reads like a compliance product first and coding assistant second. | Show safety as a status chip; move details to diagnostics or policy drawer. |
| PM-08 | S1 | "Run" result is treated as terminal object even when user wants continuation. | Run result has Ask tab and Iterate, but task thread does not own continuation. | Follow-up work feels bolted on. | Every result should return to the same task thread with "continue", "fix", "try model", "open PR", "revert". |
| PM-09 | S2 | Product does not clearly distinguish ideation, question-answering, implementation, and operations. | Same agent page routes requests into advice, proposal, or run. | Users are surprised when chat answers instead of coding, or runs instead of clarifying. | Add explicit modes: Ask, Plan, Implement, Review, Operate. Default can auto-route, but visible mode must be clear. |
| PM-10 | S2 | No universal "what happened" summary across surfaces. | Activity log rows, evidence cards, steps, and cost are separate. | Users cannot quickly form trust. | Every task/run should have a one-page human summary: request, outcome, files, tests, cost, approvals, next action. |

## Web Agent Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| WA-01 | S0 | First authenticated agent impression is wrong for day-to-day coding. | `OperatorIntro` says "Remote sandbox operator" and "Run work in a remote sandbox." | Developer expects "what can I code now?", not "operate sandbox". | Reframe to "Work session" or "Agent workspace"; make sandbox a mode badge. |
| WA-02 | S0 | Chat is not artifact-first. | Assistant messages render Markdown and collapsed code blocks; no file artifact tree in `/agent`. | Multi-file output becomes a blob, not a working deliverable. | Add right rail: Files, Diffs, Preview, Tests, PR, Cost. Convert code blocks to named artifacts. |
| WA-03 | S0 | Session cost/credits are visible but not meaningful. | `usageLabel` appends "AI credits pending"; user asked where token/credits are. | Discoverability and trust fail at the exact billing moment. | Final assistant bubble must show actual tokens, AI credits, and role-sensitive dollar visibility. |
| WA-04 | S1 | Chat canvas wastes horizontal space. | Screenshot shows narrow centered content with large empty areas; CSS caps chat column at 1080px but no side workspace. | Wide screens feel empty while code/artifacts are cramped. | Use a 3-pane layout on desktop: sessions, conversation, artifacts/audit. |
| WA-05 | S1 | "New sandbox" CTA is too implementation-specific. | Top bar button says New sandbox. | User wants new task/chat/session/project, not sandbox. | Rename by mode: "New task" default; "Remote sandbox" shown as selected execution target. |
| WA-06 | S1 | Composer contract consumes attention repeatedly. | Composer shows "Remote sandbox only", "Plan and cost first", "No local files touched". | The bottom of every chat reinforces constraints instead of capability. | Compress to one policy chip row; hide once user has seen it or move to settings. |
| WA-07 | S1 | Suggestions are too narrow and sandbox-biased. | Suggestions: create remote sandbox, fix failing test in remote sandbox, review approvals, explain project. | Does not cover daily coding, ideation, refactor, tests, bug, sprint, ops. | Suggestions by mode and repo state: fix failing test, explain diff, implement ticket, write tests, review PR, start feature. |
| WA-08 | S1 | Local-vs-remote handoff is defensive rather than helpful. | LocalHandoffCard says web stays remote-only; use CLI/VS Code. | Feels like a dead end. | Provide exact CLI command/deep link and preserve context handoff. |
| WA-09 | S1 | No visible "current project/task context" panel. | Only optional scope label; no persistent context summary. | User cannot see what the agent knows. | Add context rail: project, repo, branch, selected files/docs, pinned facts, memory digest. |
| WA-10 | S1 | Session drawer is hidden behind "Chats". | `AgentChatsDrawer` is a modal drawer, not persistent. | Continuation and session management are secondary. | Persistent collapsible left rail on desktop; drawer only on mobile. |
| WA-11 | S1 | Run card is good but not enough. | Run card has phases, tasks, evidence, route, credits. | It still sits inside chat instead of owning task status. | Make the run card a persistent task status header/side rail. |
| WA-12 | S1 | Action cards explain platform mechanics, not user value. | Proposal card: "isolated remote workspace", "draft a plan", "charging run credits". | Users must translate to "will this edit code safely?" | Reword around outcome: "Plan this change", "Estimated credits", "Files/tests/PR before code starts". |
| WA-13 | S1 | Tool/activity chips are too abstract. | Activity maps tool/run only; result/details not shown as a timeline. | Users cannot inspect progress or debug slowness. | Semantic live timeline with expandable details: searched files, read file, wrote file, ran tests, opened PR. |
| WA-14 | S1 | Model/routing state leaks into UI without user intent. | Settings popover shows route confidence/action contracts; run card shows route. | Creates confusion and undermines trust when model ranking appears. | Hide route diagnostics under "Advanced diagnostics"; show only model/provider summary unless debugging. |
| WA-15 | S2 | The "settings" popover mixes policy, usage, diagnostics. | Sandbox policy, managed balance, provider, route confidence, capabilities in one popover. | Users cannot find billing vs provider vs policy. | Split into Usage, Execution policy, Diagnostics. |
| WA-16 | S2 | Attachments are file-upload only, not code-context-first. | Attach accepts PDF/Markdown/text/csv/json/log; no `@file`, repo picker, selected diff. | Coding users need repo context, not document upload first. | Add file/context picker: current diff, files, folders, issue link, logs. |
| WA-17 | S2 | Enter-to-send hint hides multiline affordance. | Composer says Enter to send; textarea supports Shift+Enter implicitly. | Common chat friction. | Show "Shift+Enter newline" and support slash commands/context chips. |
| WA-18 | S2 | Code collapse is coarse. | Markdown collapses by code block with language/line count. | A single HTML file is manageable; multi-file output is not. | Detect filenames/code fences and create file tree with per-file expand/copy/apply/download. |
| WA-19 | S2 | No obvious "use dummy data / no clarification needed" preference. | User's chat kept asking clarifying questions. | Agents feel obstructive for exploratory/greenfield tasks. | Add task intent controls: "Use reasonable defaults", "Ask before coding", "Prototype only", "Dummy data ok". |
| WA-20 | S2 | No explicit model selection in main agent loop. | Provider/model hidden in settings/run card; account defaults elsewhere. | Power users cannot steer cheaply. | Lightweight model picker in composer header, with org policy constraints. |
| WA-21 | S2 | No immediate "open in CLI" continuity. | Local handoff exists but not a copyable continuation command. | Web-to-terminal continuity is weak. | Show `executespec continue <session-id>` and deep link to CLI login. |
| WA-22 | S2 | No conversation-level search/filter. | Drawer lists sessions; no search in inspected code. | Long-running users cannot find old work. | Search sessions by title, project, request, file, PR, tag. |
| WA-23 | S2 | No task naming/renaming workflow is visible. | Sessions auto-titled but not user-managed in UI path. | Continuation becomes messy. | Add title/tag/pin/archive on task threads. |
| WA-24 | S3 | Visual density is inconsistent. | Login page polished; authenticated chat sparse; run detail dense. | Feels like different products. | Harmonize density by persona and task phase. |

## Web Run / Result / Evidence Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| WR-01 | S0 | Run detail tabs are database-shaped, not workflow-shaped. | Tabs: Overview, Code, Steps, Timeline, Activity log, Evidence, Cost, PR, Tests, Ask. | Users bounce across tabs to answer one question. | Replace with Summary, Changes, Verification, Review/PR, Audit; keep raw tabs under diagnostics. |
| WR-02 | S0 | Activity log is not useful enough. | User called it useless; live code maps raw event types to labels. | Users cannot trust progress. | Work Timeline: semantic grouped events with actor, action, artifact, result, cost. |
| WR-03 | S0 | STOP_FAILED appears as product outcome. | Screenshot shows STOP_FAILED in Steps/Evidence. | Internal decision code makes product look broken even when output is acceptable. | Translate to user outcome: "Evidence incomplete", "Needs human review", "Completed but validation uncertain". |
| WR-04 | S1 | Model ranking appears in task evidence path. | Screenshot shows model ranking under a completed task. | Looks irrelevant or alarming for ordinary runs. | Hide by default; show only under "Why retry/upgrade?" diagnostics. |
| WR-05 | S1 | Evidence page leads with counts, not conclusion. | Pipeline/files/build/review/issues card first. | User wants "ship / review / failed because". | Top card: Outcome, confidence, next action, blocking reasons, changed files/tests/PR. |
| WR-06 | S1 | Final goal proof wording is too internal. | "Remaining proof", "Report issues is fail", "Trust cap unknown". | Users cannot act on it. | Map proof into actionable categories: output missing, tests missing, review failed, cost stopped, model retry suggested. |
| WR-07 | S1 | Cost tab is separated from evidence and decision trail. | Cost is its own tab; evidence elsewhere. | Audit requires cross-tab mental join. | Show compact cost/credits on every summary and full ledger in Audit. |
| WR-08 | S1 | Primary actions are too many and not phase-prioritized. | Push, Preview, Deploy, Report PDF, Code ZIP, Share, Try provider buttons. | Users may deploy/push before understanding state. | Phase-aware CTA: Review diff, run tests, open PR; secondary menu for deploy/share/download/model retry. |
| WR-09 | S1 | "Try Anthropic/OpenAI/Gemini" is too casual for paid rerun. | Buttons on result page launch paid reruns with confirmation. | Encourages random model shopping; confuses evaluation. | Replace with "Try another model" wizard explaining expected benefit/cost. |
| WR-10 | S1 | Code/file view is separate from chat and evidence. | Result has Code tab and Chat tab. | Users cannot ask about a specific diff in context naturally. | Artifact-aware chat: click file/hunk -> ask/fix/explain/test. |
| WR-11 | S2 | Run live page max width is too narrow for operational status. | `IdeLive` style maxWidth 860. | Similar cramped issue as agent chat. | Desktop live view should use split panes: summary, steps, timeline. |
| WR-12 | S2 | Live tokens are shown as "tok" but credits are not equally visible. | Live page shows tokens and super-admin dollars; not AI credits. | Billing model is AI credits, so token-only is incomplete. | Show tokens + estimated AI credits, with dollars only super-admin. |
| WR-13 | S2 | Evidence ledger source labels expose implementation. | deterministic, internal formula, LLM assessment style labels. | Some source detail is useful, but current hierarchy is noisy. | Use plain labels first: "Verified by build", "Judged by reviewer", "Estimated". |
| WR-14 | S2 | Report PDF is an action but not integrated into audit trail. | Report PDF button exists in result/evidence. | User sees export but not audit completeness. | Add audit export status: includes files, approvals, costs, tests, decisions. |
| WR-15 | S2 | Ask tab is isolated and late. | "Ask about this work" exists in result. | Chat should remain same task thread, not a separate tab. | Unify run copilot with agent task thread. |
| WR-16 | S2 | Error/blocker handling is not action-oriented enough. | Failed screenshots show recommendation text, but no direct remediate flow. | User asks "what else can be done here". | Each blocker needs direct CTAs: retry with same model, try stronger model, add evidence, accept as done, create issue. |
| WR-17 | S2 | Timeline and activity log likely overlap. | Both are separate tabs in tab list. | Fragmented observability. | Merge into one Work Timeline with filters. |
| WR-18 | S3 | Tab labels mix user and internal language. | "Evidence" vs "Validation"; "Ask"; "Cost"; "Activity log". | Small but cumulative friction. | Use "Summary", "Changes", "Tests", "Review", "Audit". |

## Token, Credit, Billing, and Cost Audit Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| BC-01 | S0 | AI credits are not the primary unit everywhere. | Web agent says AI credits pending; CLI/TUI shows dollar cost in many places. | Pricing/trust confusion. | Normalize all non-super-admin surfaces to AI credits. |
| BC-02 | S0 | Dollar leakage remains in CLI/TUI. | `buildRunCostRouteSurface`, cost lines, gate commands, traces render `$`. | Violates role-sensitive requirement. | Add role/entitlement-aware cost formatter in core CLI and web. |
| BC-03 | S1 | Session-level usage is incomplete. | Agent top usage sums turns but says credits pending. | User cannot understand current chat session usage. | Use actual agent turn usage ledger and calculate AI credits by policy. |
| BC-04 | S1 | Run credits and managed AI credits are conceptually overloaded. | Plan copy says run credits reserve; managed AI debits per call. | Users may not understand what they are charged for. | One billing explainer component reused across Plan, Agent, Billing, Result. |
| BC-05 | S1 | Enterprise/BYOK infinite usage display is not consistently normalized. | CLI doctor has unlimited/ungated language; web has unlimited AI credits in popover. | Inconsistent billing semantics across surfaces. | Canonical display: "Unlimited AI credits" or `-1` only in admin diagnostics. |
| BC-06 | S1 | Cost estimate and actual usage are separated from approval decisions. | Plan gate, cost tab, usage events separate. | Audit trail is incomplete without jumping around. | Approval card must snapshot estimated credits, policy, approver, final actual credits. |
| BC-07 | S2 | Token cache/reasoning details appear without clear user benefit. | Cost surfaces include input/output/cache/reasoning. | Useful for super-admin/operator, noisy for developer. | Default: total tokens + credits. Advanced: full token/cache/reasoning ledger. |
| BC-08 | S2 | Billing UI and agent/session UI are not obviously connected. | Billing page has AI credits; agent asks still surfaced confusion. | Users cannot trace from session to plan/period balance. | Click usage pill -> usage drawer with period balance, this session, this run, call ledger. |
| BC-09 | S2 | Cost per 1k tokens in CLI is irrelevant to most users. | CLI surface calculates cost per 1k tokens. | Internal finance metric, not user workflow metric. | Move to super-admin diagnostics. |
| BC-10 | S3 | "AI credits pending" is ambiguous. | Top bar and tests assert this. | User does not know if pending means not charged, unavailable, or not loaded. | Use explicit states: "No usage yet", "Calculating credits", "12 AI credits used". |

## CLI / TUI Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| CT-01 | S0 | CLI/TUI risks becoming a command dashboard instead of coding agent. | Routes: launcher/session/workspace/runs/evidence/diff/permissions/settings. | Claude/Codex comparison suffers; too much navigation. | Default surface must be prompt + diff + tests + footer; routes are secondary. |
| CT-02 | S0 | Same cost-dollar leakage exists in TUI. | `buildRunCostRouteSurface`, `costLine`, `usageLine`, plan gates render dollars. | Breaks billing/role policy. | Centralize display policy in core and pass viewer role/capability. |
| CT-03 | S1 | Launcher copy is still route/control-plane heavy. | Launcher says "attach to a run, inspect evidence, change provider". | First impression is app navigation, not "tell me what to code". | Launcher should prioritize: continue last, ask in repo, implement task, review diff, fix tests. |
| CT-04 | S1 | TUI has too many slash commands exposed as product surface. | Command palette and app routes numerous. | Cognitive load high for daily work. | Progressive command palette grouped by current state; show only relevant next actions. |
| CT-05 | S1 | Manager workflows in TUI are over-represented relative to value. | Runs/evidence/settings/governance in terminal app. | Terminal is not the best manager console. | Keep manager read-only summaries; deep manager workflows should open web. |
| CT-06 | S1 | Session footer omits credits in the Ink shell. | `SessionUsageSummary` has costUsd/tokens but not AI credits. | Core billing unit absent. | Add `aiCreditsUsed`, `aiCreditsRemaining`, display with role-aware dollars. |
| CT-07 | S1 | Transcript truncation can hide important context. | Visible transcript hides earlier messages by row count. | Users may not know what context is retained. | Add visible context state: retained/pinned/compacted count and `/context` hint. |
| CT-08 | S1 | App TUI and Ink TUI may be two mental models. | `tui/chat.tsx` rich Ink UI and `opentuiApp.ts` app shell coexist. | Inconsistent experience depending on launch mode. | Decide one primary TUI interaction model; make other compatibility/deep mode. |
| CT-09 | S1 | Local permission model is strong but likely too prominent. | Folder access, permission prompt, trust session, approval surfaces. | Necessary safety can become friction. | Permission UX should be terse: action, path/command, risk, allow once/session/deny. |
| CT-10 | S2 | Evidence export exists but not positioned in normal loop. | `/evidence`, local manifest, export commands. | Users may not discover it until audited. | End every run with "Evidence saved: view/export" summary. |
| CT-11 | S2 | Checkpoints/rewind are valuable but hidden in command space. | Checkpoint and rewind route surfaces exist. | Developers need them as core safety affordances. | Show checkpoint before risky edit and "revert last run" after completion. |
| CT-12 | S2 | Model picker/favorites exist but not tied to outcome. | Model picker state in chat TUI. | Users choose models without cost/quality context. | Display model as policy choice: fast/cheap/balanced/best with credit estimate. |
| CT-13 | S2 | TUI "usage" shows dollars/session tokens in sidebar. | Split footer sidebar has `$0.0000 · 0/0 tok`. | Same billing policy issue and unclear for AI credits. | Show `0 AI credits · 0 tokens`; dollars only admin. |
| CT-14 | S2 | Run streaming state is generic. | "Run Streaming", "backend run", "cost streams when available". | Less immediate than "Planning", "Editing", "Testing". | Render semantic phase and current artifact, not transport state. |
| CT-15 | S2 | Terminal app route names expose backend concepts. | `/route runs`, `/tasks cost`, backend sessions. | New users learn ExecuteSpec internals instead of task flow. | Alias around user intent: `/work`, `/changes`, `/proof`, `/usage`, `/continue`. |
| CT-16 | S2 | CLI manager/sprint operations not coherent. | Jira settings exist in web; CLI has project commands but no task board. | Day-to-day sprint work not natural. | Add task import/list/start from issue as top-level flow. |
| CT-17 | S3 | Visual TUI borders/app shell may be overkill for narrow terminals. | App frame uses borders, routes, rail. | Risk of cramped output. | Plain mode should be first-class and equally powerful. |

## Session Management and Continuity Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| SM-01 | S0 | Prior-turn continuity failed in user test. | User screenshot: agent says it lacks prior context. | Kills trust immediately. | Add session memory contract tests and visible context retention. |
| SM-02 | S0 | Web sends only recent summarized turns to stream. | `MAX_CONTEXT_TURNS = 8`; conversation built from userMessage and assistantSummary. | Long or detail-heavy sessions can lose specifics. | Use server-side durable session context, pinned memory, and retrieval over full transcript. |
| SM-03 | S1 | Session scope is fragile across project/run/global contexts. | `agentSessionMatchesScope`, scoped selection, activeRun hydration. | User may not know which context is active. | Persistent scope chip with editable context and warnings when switching. |
| SM-04 | S1 | No obvious "continue latest" web entry point. | Drawer hidden; top CTA is new sandbox. | Returning users start over. | Home/agent should lead with Continue latest active task. |
| SM-05 | S1 | Fork/compact controls exist more clearly in CLI than web. | CLI session controls; web only drawer/settings. | Web long sessions degrade. | Add web session controls: fork, compact, rename, pin context, archive. |
| SM-06 | S1 | Attachments disappear after send. | `stream:start` clears attachments. | Good for one turn, but user may expect persistent context. | Distinguish one-turn attachments from pinned session docs. |
| SM-07 | S2 | No visible context budget. | Project settings has token budget elsewhere; agent has usage pill only. | Users cannot manage long sessions. | Context budget meter: retained turns, pinned docs, compacted summary. |
| SM-08 | S2 | Session search and tags are missing. | Drawer lists sessions only. | Hard to resume older projects. | Add tags, project filters, status filters, search. |

## Sprint, Manager, and Operations Workflow Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| MO-01 | S0 | No coherent manager cockpit. | Team/Admin/Projects exist, but no single work/risk/cost/approval dashboard. | Managers cannot operate a team through ExecuteSpec. | Manager home: approvals, blocked work, PRs, tests, cost, cycle time, risks. |
| MO-02 | S1 | Sprint task management is not first-class. | Jira connection exists, but no visible task board/work queue in inspected UI. | Day-to-day sprint work remains outside product. | Add Work Queue: issue, owner, status, branch, PR, risk, evidence, next action. |
| MO-03 | S1 | Approvals inbox is hidden in agent drawer and decisions page. | Agent drawer has approval inbox; console has decisions. | Managers need a primary approvals workflow. | Dedicated approval inbox with filters: plan, spend, model upgrade, deploy, policy exception. |
| MO-04 | S1 | No "who approved what" manager narrative. | Evidence/cost/actions exist separately. | Audit not manager-readable. | Approval ledger per task and org export. |
| MO-05 | S1 | Team cost governance is not operationalized in UX. | Billing/admin surfaces exist; session usage separate. | Managers cannot forecast or intervene. | Budget dashboard: spend/credits by project/user/model/task, anomaly alerts. |
| MO-06 | S1 | PR/CI state is not central enough. | Result has PR tab and buttons; project tabs have runs/PRs. | Managers care about merged/shippable state. | Work queue rows need PR status, CI status, reviewer state. |
| MO-07 | S2 | No explicit SLA/aging for blocked tasks. | Gates have age in drawer, but not manager-level aging. | Blocked work can rot. | Add age, owner, escalation, reminders. |
| MO-08 | S2 | Manager vs developer permissions are not reflected in tailored UI. | Same shell shows many surfaces. | Users see irrelevant controls. | Role-aware navigation and home screens. |
| MO-09 | S2 | No sprint rituals support. | No standup summary, sprint review evidence, retro, release notes visible. | AIDC/SDLC story incomplete. | Generate daily/sprint summaries from task threads. |
| MO-10 | S3 | Benchmark/admin surfaces appear in same app nav. | Benchmarks visible in sidebar. | Noise for normal teams. | Hide operator-only surfaces unless role/profile demands it. |

## Greenfield, Ideation, Experimentation Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| GI-01 | S0 | Exploratory prototyping is treated like remote PR workflow. | Web agent emphasizes PR-oriented remote sandbox. | User asking "dummy LinkedIn page" should get artifact/preview, not run-governance framing. | Add Prototype mode: generate artifact, preview, export, optionally convert to repo/PR later. |
| GI-02 | S1 | Clarification behavior is too conservative for throwaway work. | User got clarifying response despite saying dummy data. | Breaks experimentation momentum. | Add "Use defaults" preference and infer low-risk prototype tasks. |
| GI-03 | S1 | No experiment comparison workspace. | Model retry exists, but no side-by-side artifacts/outcomes. | Users cannot compare variants. | Add experiments: variant A/B/C, model, cost, screenshots, diff, winner. |
| GI-04 | S1 | Generated UI/code lacks immediate preview in agent chat. | Result page has preview action, chat has code block. | Prototype loop is too slow. | Web agent artifact rail should render HTML/component preview immediately when possible. |
| GI-05 | S2 | Greenfield project bootstrap path is not obvious. | Suggestions are sandbox/run oriented. | User cannot easily go idea -> project scaffold -> repo. | Add "Start project" flow: stack, files, preview, repo creation/PR. |
| GI-06 | S2 | Experiment cost controls are not surfaced as UX choices. | Cost cap exists in compose/plan, not main agent. | Users need cheap exploration vs expensive finalization. | Modes: Sketch cheap, Build balanced, Verify best. |

## Information Architecture and Navigation Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| IA-01 | S0 | Agent, Work, Projects, Runs, Results, Ask overlap. | Sidebar and run tabs divide one workflow. | Users cannot predict where to go. | IA around Work Items: Inbox, Active, Projects, Approvals, Audit, Admin. |
| IA-02 | S1 | `/agent` and `/result/:id` are not unified. | Agent task creates run, then result owns run, Ask separate. | Context handoff feels broken. | A task thread URL should contain all run/result context. |
| IA-03 | S1 | Top nav in run result has too many tabs. | 10 tabs. | More navigation than decision-making. | Progressive summary with collapsible sections; raw tabs under advanced. |
| IA-04 | S1 | Project chat has artifacts rail, but agent chat does not. | ProjectMore has artifacts; agent chat only Markdown blocks. | Inconsistent artifact pattern. | Reuse/adapt project artifact rail for global agent. |
| IA-05 | S2 | Settings, billing, org, providers, model matrix are fragmented. | Multiple surfaces found. | Setup and governance hard to reason about. | Setup hub with checklist and state. |
| IA-06 | S2 | Benchmarks share product chrome with normal work. | Benchmarks sidebar. | Adds operator noise. | Role/profile-gate benchmark nav. |
| IA-07 | S2 | "Start" vs "Agent" vs "Work" unclear. | Sidebar labels are broad. | User does not know where to begin. | Rename with jobs: New work, Agent chat, Work queue, Projects, Approvals. |

## Copy and Trust Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| CP-01 | S0 | Internal statuses leak. | STOP_FAILED, model ranking, route confidence, action contracts. | Feels immature/untrustworthy. | Humanize every internal state before display; raw codes only diagnostics. |
| CP-02 | S1 | "Verified pull request" and "verified score" risk overclaiming. | Login and result copy. | If evidence is partial, trust damage. | Use "evidence-backed" and state proof limits clearly. |
| CP-03 | S1 | "AI credits pending" is bad copy. | Usage label. | Ambiguous billing state. | Replace with exact lifecycle states. |
| CP-04 | S1 | Safety/trust copy repeats too often. | TrustBoundaryPanel plus composer contract plus proposal card. | User tunes it out. | Show once, then compact persistent policy badge. |
| CP-05 | S2 | "Remote sandbox" phrase is overused. | Agent top, intro, drawer, proposal, composer, run card. | It defines product as infrastructure. | Use "work session" and "remote execution" as secondary metadata. |
| CP-06 | S2 | CTA language mixes user outcome and system action. | Create sandbox plan, Push to GitHub, Try provider, Report PDF. | Next action hierarchy unclear. | CTA copy by intent: Review plan, Apply change, Open PR, Export audit. |
| CP-07 | S2 | Error text lacks accountable next step. | Evidence says retry stronger validation/model. | Generic. | Each failure state maps to specific actions and expected cost. |

## Accessibility and Responsive Risk Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| AX-01 | S1 | Usage pill disappears on mobile. | CSS hides `.agent-usage-pill` under 760px. | Mobile users lose cost/token visibility. | Replace with compact icon/credits chip, not hide entirely. |
| AX-02 | S1 | Chat context and composer can become vertically cramped. | User screenshot shows composer low, large center gaps; CSS fixed header/composer. | Reduced usability on laptop/mobile. | Responsive layout with persistent but collapsible rails and better scroll anchoring. |
| AX-03 | S1 | Color and glyphs carry status meaning in TUI. | Icons/color for permissions, status, evidence. | Screen-reader/no-color users may lose state. | Ensure plain labels always accompany color/glyphs; no-color tests for key flows. |
| AX-04 | S1 | Drawer/modal focus behavior needs live verification. | Agent drawer/popover/backdrop code visible. | Keyboard trap/focus return risk. | Add focus trap, Esc, return focus, aria labels; test with keyboard. |
| AX-05 | S2 | Small font and low-contrast muted text is heavily used. | CSS has many 11-12px muted labels. | Accessibility and readability risk. | Raise minimum text size for operational data; check contrast tokens. |
| AX-06 | S2 | Activity/timeline dynamic updates may not announce. | Live SSE/log updates append visually. | Assistive tech may miss progress. | Use aria-live regions for run status, not entire log spam. |
| AX-07 | S2 | Code block details/summary accessibility needs verification. | Markdown uses `<details>` codefold. | Mostly good, but keyboard/label clarity needs test. | Add copy/open controls and accessible labels. |
| AX-08 | S2 | Terminal UI relies on terminal width thresholds. | Sidebar appears only >=108 or >=120 columns. | Important usage/session data can disappear. | Ensure all hidden sidebar data has command/footer equivalent. |
| AX-09 | S3 | Login side preview has tiny code-like text. | Captured screenshot. | Decorative but could distract. | Make preview secondary and non-essential for comprehension. |

## SDLC and AIDC Coverage Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| SA-01 | S0 | No single SDLC artifact per task. | Files, tests, PR, audit, cost are fragmented. | Enterprise trust and manager review suffer. | Task dossier: requirement, plan, branch, commits, tests, PR, deploy, cost, decisions. |
| SA-02 | S1 | Decision audit exists but is not user-readable. | Action events/session events/goal decisions exposed separately. | "Why did it do this?" remains hard. | Decision timeline with reason, alternatives, approver, cost consequence. |
| SA-03 | S1 | Test strategy is not first-class in UI. | Tests tab exists; plan/run may mention tests. | Users cannot judge if testing is adequate. | Show test plan, tests added/run/skipped, coverage confidence, missing tests. |
| SA-04 | S1 | PR workflow is output action, not workflow center. | PR button/tab in result. | Daily coding revolves around PRs. | PR status as part of task header and manager queue. |
| SA-05 | S1 | Deployment is too easy to expose beside ordinary result actions. | Deploy button sits near preview/PDF/code. | Risk of premature deployment. | Deploy behind explicit environment/release flow with policy. |
| SA-06 | S2 | Incident/ops workflows are not separately optimized. | Generic work/run flow. | Day-to-day ops tasks need logs, rollback, checks, timeline. | Add Ops mode: diagnose, runbook, command approvals, evidence, rollback. |
| SA-07 | S2 | AIDC evidence is not connected to compliance exports. | Report PDF exists but audit breadth unclear. | Enterprise buyers need audit bundle. | Export audit bundle: JSON/PDF with every decision, model, token, approval, artifact. |
| SA-08 | S2 | No release notes/changelog from completed task thread. | Not visible in inspected surfaces. | SDLC handoff incomplete. | Generate release notes from task dossier. |

## Visual Design and Layout Gaps

| ID | Sev | Issue | Evidence | Impact | Correction |
| --- | --- | --- | --- | --- | --- |
| VD-01 | S1 | Auth screen is more coherent than app work screens. | Captured login is visually polished; authenticated screenshots feel sparse/dense unevenly. | User trust drops after login. | Bring authenticated shell to same level of intentional hierarchy. |
| VD-02 | S1 | Desktop real estate is underused in chat. | User screenshot and `.agent-chat-col` max-width 1080 with no artifact rail. | Code-heavy work feels cramped and empty at once. | Desktop split workbench layout. |
| VD-03 | S1 | Run screens have high tab density and low summary density. | Supplied run screenshots. | Hard to scan outcome. | Add top summary block with decision-grade hierarchy. |
| VD-04 | S2 | Too many pill/card/chip elements compete. | Trust panels, contract chips, status badges, phase chips, usage pills. | Visual noise. | Reduce persistent chips; group secondary metadata. |
| VD-05 | S2 | Activity log rows look like raw table data. | Supplied activity screenshot. | Feels useless. | Timeline with verbs and artifacts. |
| VD-06 | S2 | Code/diff/actions are not spatially tied. | Result actions top, code tab separate. | Users lose relationship between actions and artifacts. | Place actions at artifact level. |
| VD-07 | S3 | Icons/glyphs are inconsistent between web and TUI. | Manual SVGs, terminal glyphs, unicode symbols. | Brand polish issue. | Define shared icon/status vocabulary. |

## Prioritized Remediation Map

### S0: Fix Before Serious UX Relaunch

1. Make task thread the primary object across web and CLI.
2. Fix web agent session memory and visible context retention.
3. Replace sandbox/run-first language with work/session/task language.
4. Add artifact-first web agent layout with file tree/diff/preview/test/cost rail.
5. Normalize AI credits as primary billing unit and hide dollars outside super-admin.
6. Translate STOP_FAILED/model-ranking/internal decisions into user actions.
7. Replace Activity Log with semantic Work Timeline.
8. Add manager cockpit/work queue if manager persona remains in scope for launch.

### S1: Next Release-Level Experience Work

1. Persistent sessions/task rail.
2. Unified result and chat continuation.
3. Role-specific home screens.
4. Approval inbox as primary manager/admin surface.
5. Multi-file artifact bundle handling.
6. Context controls: pinned files, current diff, docs, logs, issue links.
7. Cost/credit drawer with task/session/run/call levels.
8. TUI simplification: prompt/diff/test/footer first, routes second.
9. Prototype/experiment mode for greenfield ideation.
10. SDLC task dossier and audit export.

### S2/S3: Important But Can Follow

1. Rename tabs and CTAs.
2. Add search/tags/archive for sessions.
3. Improve responsive behavior and accessibility state announcements.
4. Add sprint rituals: standup, sprint summary, release notes.
5. Model comparison wizard.
6. Harmonize icon/status language.
7. Improve setup/integration checklist.

## Product Shape Recommendation

### Developer CLI

Default screen:

- Current repo/branch/status.
- Prompt.
- Footer: mode, provider/model, AI credits, tokens, session, safety.
- Latest changed files/test state.

Primary commands:

- `continue`
- `plan`
- `apply`
- `diff`
- `test`
- `revert`
- `evidence`
- `pr`
- `usage`

Everything else should be discoverable but not first-screen.

### Developer Web

Default workbench:

- Left: tasks/sessions/approvals.
- Center: conversation.
- Right: files/diff/preview/tests/PR/audit/usage.
- Header: project, branch, mode, provider, AI credits, status.

### Manager Web

Default cockpit:

- Work queue.
- Approval inbox.
- Blocked/risky work.
- PR/CI status.
- AI credits and usage by project/user/model.
- Evidence/audit export.

### Admin Web

Default governance:

- Billing plans/credits.
- Provider/model policy.
- BYOK/key status.
- SSO/RBAC.
- Audit retention/export.
- Benchmarks/operator tools only when role-appropriate.

## Bottom Line

ExecuteSpec has enough backend surface to be credible. The product experience is failing because it asks users to understand the system instead of making the system disappear behind task progress, artifact handling, continuation, and audit.

The hardest but highest-leverage change is conceptual: stop optimizing screens around runs and sandboxes. Optimize around task threads.
