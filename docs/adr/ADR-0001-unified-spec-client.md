# ADR-0001 — Unified `spec` Client (CLI + VS Code)

> **Status:** Proposed (supersedes the UX half of the Shape B design lock; preserves its execution-model + privacy locks)
> **Date:** 2026-05-30
> **Owner:** RL-2026-05-052 (client track)
> **Deciders:** founder (re-confirmation required on the three revised Shape B decisions — see §6)
> **Amends:** [`docs/design/cli-plugin-spec-shape-b-2026-05-28.md`](../design/cli-plugin-spec-shape-b-2026-05-28.md)
> **Implemented by:** [`docs/design/cli-vscode-unified-client-2026-05-30.md`](../design/cli-vscode-unified-client-2026-05-30.md) (design); execution tracked in [`docs/pending.md`](../pending.md) §J1 (the standalone execution plan was removed 2026-06-09 with the `executespec-cli` split)

> **ADR convention (first entry):** This is the repo's first Architecture Decision Record. ADRs live under `docs/adr/`, numbered `ADR-NNNN-slug.md`, and capture a single significant, hard-to-reverse decision: its context, the decision, and its consequences (good and bad). They are immutable once **Accepted** — a later ADR supersedes an earlier one rather than editing it. Design docs (`docs/design/`) and execution plans (`docs/plans/`) carry the detail; the ADR carries the *why*.

---

## 1. Context

ExecuteSpec is building first-party clients — a CLI and a VS Code extension — on a **local-hands / remote-brain** model: the proprietary agent loop (classifier → planner → worker → reviewer, prompts, model routing, scoring) stays server-side; tool execution is proxied to the user's machine so **code and artifacts stay local and nothing is published by default**. This decision was taken by the founder on 2026-05-26 (RL-2026-05-043).

Two designs now exist in the tree, and they are **in tension**:

- **Gen-1 (built + merged to `origin/dev`/`origin/main`).** A deterministic subcommand client: `executespec`/`es` with `auth login/status/logout` and `run submit/watch/list/view/approve/reject/cancel/report/local`; a native VS Code MVP (Runs TreeView + OutputChannel stream + a modal plan gate). Both sit on a shared TypeScript core (`clients/core`: typed REST client, SSE consumer, PAT token store, local `ToolRuntime`). The backend tool-proxy (`RemoteToolDispatcher` + `RemoteToolController` + the `isRemote`-guarded `ToolRegistry` branch) is landed but **dormant** — `register()` has zero callers, so the default server pipeline is byte-identical.

- **Gen-2 / "Shape B" (founder-locked 2026-05-28, only the Phase-1 backend exists).** A **chat-first** redesign: a single `spec` binary that drops into a chat REPL where an LLM interprets intent and routes to *action tools*; VS Code becomes a chat sidebar webview; execution defaults to LOCAL; conversation is stored locally-only with a stateless server. Backend Phase-1 steps 1–2 have landed (`SpecChatController` placeholder at `POST /api/spec/chat`, `ProjectChatActionTools` with 6 write tools, `spec_chat_audit` / V218). Step 3 (the real LLM dispatch loop) and Phases 2–4 (the client REPL/sidebar) are not started.

The Shape B design framed the choice as **chat-first REPL vs. "subcommand maze."** Reviewing the code and the competitive analysis (`docs/competitive-analysis/`), we judge that framing to be a **false binary**. The reference products do not choose one:

- **Claude Code** is simultaneously a rich streaming chat REPL, a slash-command surface, *and* headless-scriptable (`claude -p …`).
- **opencode** is a headless engine + many thin clients over one OpenAPI SDK; the same SDK runs in-process or remote. Its lesson is *engine-as-service + thin clients*, not *one UI shape wins*.

Separately, the competitive analysis identifies **prompt-cache discipline** as the single largest cost/efficiency lever for the single-loop incumbents — which is in direct tension with Shape B's locked "server is stateless for chat; client re-sends full history every turn."

We therefore need a decision that (a) reconciles the two versions, (b) honors the local-hands privacy moat, (c) does not forfeit the cost-undercut pitch, and (d) satisfies a hard product constraint introduced for this work: **the CLI and the VS Code extension must ship the same feature set.**

## 2. Decision

Build **one unified client product, `spec`**, as a **layered** client over a single shared core, with **feature parity between the CLI and the VS Code extension as an invariant**.

1. **One core, two renderers.** All capability lives in `clients/core` (REST, SSE, PAT auth, local `ToolRuntime`, chat session, local state, project linking, run orchestration helpers). The CLI and the VS Code extension are **thin renderers** over that core. A feature is "done" only when it is reachable from **both** surfaces. No capability may live in only one surface.

2. **Two layers of interaction, not two products:**
   - **Conversational layer (default, interactive):** the chat REPL (CLI) and chat sidebar (VS Code). An LLM interprets intent and may call action tools. This is the Shape B vision, kept.
   - **Deterministic layer (retained, first-class):** the gen-1 verbs (`spec run submit/watch/list/view/approve/reject/cancel/report`, `spec auth …`), with `--json` everywhere, for scripting, CI, non-TTY, and automation. In VS Code these same operations are reachable via the command palette, tree context actions, and buttons. **The deterministic layer is not deprecated.**

3. **Governance stays deterministic.** The plan gate is a human veto checkpoint. Approve/reject MUST be reachable as a deterministic, audited affordance — a `[y/N]` prompt in the REPL, a modal/button in VS Code — and MUST NOT be reachable *only* via LLM intent. The model may *surface* a pending gate; a human (or an explicit `--yes`/`approve <runId>`) confirms it.

4. **Execution locality is explicit, never silent.** A run is REMOTE (server clones) or LOCAL (local-hands) by an explicit flag/setting with a remembered per-project default. The first LOCAL run on a machine requires a one-time opt-in. The single wire mechanism is `PipelineRun.executionLocality` (the Shape B lock); the client-side `RunRequest.localExecution` field and gen-1 `run local`'s use of it are retired in favor of it.

5. **Privacy moat is preserved, economics are measured.** Code stays local in LOCAL mode; nothing is pushed/published without explicit opt-in; chat *content* is never durably stored server-side (only an audit row). **However**, the stateless-vs-cache cost tension (decision #2 of Shape B) is treated as an open economic risk to be **measured before the chat surface scales** (Phase 2 gate), with a fallback to a short-lived, encrypted, TTL'd server-side *prompt-prefix cache* (not durable content) if cache-miss cost proves to undercut the cost pitch. See the design doc §"Statelessness vs. prompt cache."

6. **Project linking is explicit.** Auto-create-from-CWD is replaced by an explicit, confirmed first-run link step.

## 3. Decision detail — the feature-parity invariant

Because parity is the hard constraint, we encode it structurally:

- Every user-facing feature is specified once in the design doc's **Feature Matrix**, with a CLI affordance column and a VS Code affordance column. A feature row is incomplete until both cells are non-empty and implemented.
- A CI/parity check (lint rule + a checklist test) asserts that every command/action registered in the CLI has a corresponding VS Code contribution and vice-versa, keyed by a shared capability id exported from `core`.
- New capabilities land in `core` first; the two renderers are added in the **same PR** (or the PR is not mergeable). This prevents drift at the source.

## 4. Consequences — positive

- **No forfeited use case.** Interactive users get the agentic chat; automation/CI keeps deterministic, parseable verbs. We match Claude Code's actual shape rather than a strawman of it.
- **Lower build cost than a rewrite.** Shape B becomes *additive* over gen-1 (the existing `core`, REST/SSE, `ToolRuntime`, VS Code TreeView/modal are all reused). We are not throwing gen-1 away.
- **Governance is trustworthy.** A deterministic, audited gate is defensible to enterprise buyers in a way an LLM-interpreted approval is not.
- **Privacy moat intact.** Local-hands is orthogonal to UX shape and is preserved unchanged.
- **Parity is structural, not aspirational.** The shared-core + parity-check design makes "same feature set on both" enforceable instead of a promise.
- **Cost pitch protected.** Surfacing the cache tension now (with a concrete fallback) prevents discovering a cost regression after launch.

## 5. Consequences — negative / costs

- **More surface to maintain than chat-only.** Keeping the deterministic verbs is ongoing cost (help text, `--json` contracts, tests). Mitigation: verbs are thin wrappers over the same `core` calls the action tools use; marginal cost is low.
- **The parity invariant is a tax on every feature.** Each capability must ship to two renderers before it counts as done, which slows individual features. This is the *intended* trade — parity is the product requirement.
- **The cache fallback adds complexity if triggered.** A TTL'd prompt-prefix cache reintroduces a sliver of server-side state we were trying to avoid; it must be carefully scoped (prefix only, encrypted, short TTL, no content durability) to not erode the privacy claim.
- **Re-opening founder-locked decisions has process cost.** Three Shape B decisions are revised here (see §6) and need re-confirmation, which is a decision-latency cost.
- **Two interaction layers can confuse positioning** ("is it a chat tool or a CLI?"). Mitigation: docs + onboarding frame chat as the default and verbs as "for scripts."

## 6. Relationship to the Shape B locked decisions

Shape B locked five decisions (founder, 2026-05-28). This ADR's stance on each:

| # | Shape B locked decision | This ADR |
| --- | --- | --- |
| 1 | Binary `spec` (alias `executespec`; drop `es`) | **Keep.** `spec` primary, `executespec` alias. Dropping `es` is cosmetic and fine. |
| 2 | Chat persistence LOCAL-ONLY; server stateless | **Keep the privacy line; revise the absolutism.** Content stays off the server. But "zero server state" is treated as a measured economic risk, with a scoped TTL'd prompt-prefix cache as a pre-approved fallback if cache-miss cost is material. **Re-confirm.** |
| 3 | Default `submit_run` = LOCAL in a linked project | **Revise to explicit.** LOCAL is a first-class mode and the *eventual* default, but it requires a one-time opt-in per machine and is never silent. **Re-confirm.** |
| 4 | CWD-not-in-project → auto-create project | **Revise.** Replace silent auto-create + after-the-fact notice with an explicit, confirmed first-run link. **Re-confirm.** |
| 5 | VS Code sideload VSIX first, Marketplace at polish | **Keep.** |

**Net new constraint not in Shape B:** CLI and VS Code must have the **same feature set** (the deciding constraint of this ADR).

## 7. Alternatives considered

- **A. Chat-only (pure Shape B; delete the verbs).** Rejected: forfeits CI/scripting/non-TTY; routes deterministic governance through an LLM; diverges from Claude Code's actual (layered) shape. Cheaper short-term, worse product.
- **B. Verbs-only (cancel the chat pivot; ship gen-1).** Rejected: loses the agentic authoring UX that the market expects; under-differentiates against Claude Code/Cursor; founder has already endorsed chat-first as the default.
- **C. Two independent codebases (a CLI team and a VS Code team).** Rejected outright: guarantees feature drift, the exact failure the parity constraint exists to prevent; doubles cost.
- **D. Full-fat client with a local agent loop (move the brain to the client).** Rejected: surrenders the moat (prompts/planner/scoring), kills server-side observability/billing/governance, and is not what local-hands/remote-brain means.
- **E. Generate the client SDK from an OpenAPI spec (opencode's approach).** *Deferred, not rejected.* Attractive for drift-prevention but ExecuteSpec has no OpenAPI surface today; adopting it is a separate, larger investment. The hand-maintained typed `core` is the interim; an OpenAPI-generated `core` is a future ADR.

## 8. How we'll know it's right

- Parity check is green: 100% of capabilities reachable from both surfaces.
- A scripted CI user can drive a run end-to-end with verbs + `--json`; an interactive user can do the same via chat — both validated in the test plan.
- LOCAL-mode run: code never leaves the machine (verified by network egress inspection in the test plan); nothing pushed without opt-in.
- Plan-gate approvals are 100% deterministic + audited (no LLM-only approval path exists).
- Phase-2 cost gate: measured per-turn chat cost on a representative session is within the cost-pitch envelope, or the prefix-cache fallback is engaged.

## 9. References

- [`docs/design/cli-vscode-unified-client-2026-05-30.md`](../design/cli-vscode-unified-client-2026-05-30.md) — full technical design + Feature Matrix.
- [`docs/design/cli-plugin-spec-shape-b-2026-05-28.md`](../design/cli-plugin-spec-shape-b-2026-05-28.md) — the amended Shape B lock.
- [`docs/pending.md`](../pending.md) §J — open CLI/VS Code + Terminal-Bench work (folded in from the prior CLI/Terminal-Bench handoff, deleted 2026-06-03).
- [`docs/competitive-analysis/COMPARISON.md`](../competitive-analysis/COMPARISON.md) — Claude Code / opencode shape analysis.
- RL-2026-05-043 (CLI/VS Code + PAT), RL-2026-05-052 (Shape B) in [`docs/reason-log/2026-05.md`](../reason-log/2026-05.md).
