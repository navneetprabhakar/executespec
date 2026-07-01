# ExecuteSpec Web UI Persona Audit - 2026-06-29

## Scope

- Surface: ExecuteSpec web Console and agent UI.
- Target: deployed dev backend at `https://dev.executespec.ai`, with local Vite frontend at `http://127.0.0.1:5173` using the real deployed API.
- Evidence: 12 screenshots captured in this run under `screenshots/`.
- Comparison set: OpenAI Codex Web, Claude Code, Cursor Agent/Bugbot, Devin, Replit Agent.

## Step Health

1. `01-dev-login.png` - Login and positioning: healthy first impression, but marketing strength outpaces visible product proof.
2. `02-agent-existing-chat.png` - Agent entry: credible chat-first shell, but starts in an old transcript and does not frame the local coding loop.
3. `03-agent-settings-popover.png` - Settings/usage: useful but internally worded; not enough for permission or local-hands confidence.
4. `04-agent-chats-drawer.png` - Chat drawer: useful continuity; weak session triage and status scanning.
5. `05-compose-start.png` - Start work: functional run composition, but the coding-agent loop is not visible as prompt -> permissions -> diff -> verify -> evidence -> revert.
6. `06-work-runs-list.png` - Work list: good operational density; filtering and proof status compete with small text.
7. `07-run-detail-local-execution.png` - Run detail: strongest surface; local execution, provider, cost, tabs, and actions are visible, but diff/verify/revert are not primary.
8. `08-projects-list.png` - Projects list: usable, sparse, and honest; weak for deep repo context confidence.
9. `09-setup-surface.png` - Setup: strong governance grouping; good for teams, providers, billing, and project setup.
10. `10-command-palette.png` - Command palette: useful keyboard path; strong fit with an operator console.
11. `11-mobile-agent.png` - Mobile agent: usable but cramped; announcement banner and dense transcript reduce task focus.
12. `12-mobile-run-detail.png` - Mobile run detail: information exists, but core proof/actions are too far below the fold.

## Persona Verdicts

- Solo coding agent user replacing Claude Code/Codex: not ready from web UI alone. Entry and run detail are credible, but the permission/diff/verify/revert loop is fragmented.
- Senior engineer working on an existing repo: partially ready. Run evidence and project setup exist; source-level proof is not as immediately inspectable as competitor IDE/terminal flows.
- Engineering manager evaluating governance: stronger than most competitors. Provider, cost, setup, and run history are visible.
- Security/platform owner: directionally good because local execution and cost/provider details are visible, but permissions and local command approval are not clear enough in the web UI.

## Competitor Assessment

- OpenAI Codex Web: likely stronger at simple cloud task delegation and PR/code-review framing; ExecuteSpec is stronger on cost/governance surfaces.
- Claude Code: stronger at terminal-native permissions, tool transparency, and local coding-loop trust; ExecuteSpec web is weaker for hands-on replacement.
- Cursor Agent/Bugbot: stronger where coding happens in the IDE and background agents tie directly to code context; ExecuteSpec web is better for centralized run history/governance.
- Devin: stronger autonomous-engineer narrative with a browsable workspace; ExecuteSpec needs sharper live work/diff/evidence/revert surfaces to compete.
- Replit Agent: stronger web-native build/deploy immediacy; ExecuteSpec has stronger enterprise controls but less immediate artifact feedback.

## Priority Findings

- P0: Make local-hands replacement flow explicit on the web agent and compose surfaces.
- P0: Promote diff, verification, evidence, and revert to primary post-run actions on local-execution runs.
- P1: Collapse or scope the announcement banner on active work/mobile screens.
- P1: Add a run cockpit state that shows latest tool, changed files, pending permission, verification result, cost/tokens, and attach/cancel.
- P1: Improve first-run agent empty state and session selection; old transcripts are a bad default for a replacement product.
- P2: Improve mobile run detail hierarchy; key status and actions need to be visible above the fold.
- P2: Keep command palette, but add local-hands specific commands.

## Accessibility Limits

- Screenshots show labeled top-bar controls and structured navigation, but this audit did not perform full keyboard traversal, screen-reader testing, contrast measurement, or zoom/reflow testing.
- Visible risks are mobile density, small muted text, banner height, and proof/action controls landing below the fold.

## Source Links Used

- OpenAI Codex Web: https://developers.openai.com/codex/cloud
- Claude Code overview: https://code.claude.com/docs/en/overview
- Claude Code GitHub Actions: https://code.claude.com/docs/en/github-actions
- Cursor Agent overview: https://cursor.com/docs/agent/overview
- Cursor Bugbot: https://cursor.com/docs/bugbot
- Devin overview: https://docs.devin.ai/get-started/devin-intro
- Replit Agent: https://docs.replit.com/replitai/agent
