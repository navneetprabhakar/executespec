# `spec` Unified Client — Execution Plan (CLI + VS Code)

> **Date:** 2026-05-30 · **Status:** Proposed · **Owner:** RL-2026-05-052 (client track)
> **Decision:** [`docs/adr/ADR-0001-unified-spec-client.md`](../adr/ADR-0001-unified-spec-client.md) · **Design:** [`docs/design/cli-vscode-unified-client-2026-05-30.md`](../design/cli-vscode-unified-client-2026-05-30.md)
> **Hard constraint:** every capability ships to **both** the CLI and VS Code (feature parity is the definition of done).

This plan sequences delivery of the unified client, enumerates the full feature set, and covers positive and negative scenarios exhaustively. Phases are strangler-fig: each is independently shippable, flag-gated, and leaves the default server pipeline byte-identical until LOCAL mode is explicitly activated.

---

## 1. Phase overview

| Phase | Title | Lands (Feature Matrix) | Flag | Default | Est. |
| --- | --- | --- | --- | --- | --- |
| **0** | Foundation hardening | (none — build + CI + defect fixes) | — | — | 1–2 d |
| **1** | Backend chat loop | F5 (server half) | `spec.chat.enabled` | OFF | 3–4 d |
| **2** | CLI: chat REPL + verbs + link (REMOTE) | F1–F14, F17–F25 (CLI) | `spec.chat.enabled` | OFF | 4–5 d |
| **3** | LOCAL-mode activation | F15, F16 (both) | `spec.local.enabled` | OFF | 2–3 d |
| **4** | VS Code: sidebar + tree/modal parity | F1–F25 (VS Code) | `spec.chat.enabled` | OFF | 4–5 d |
| **5** | Privacy hardening + cost fallback | (hardening; prefix-cache if needed) | `spec.prefixcache.enabled` | OFF | 2–3 d |
| **6** | Polish + packaging + docs | (publish) | — | — | 2–3 d |
| **7** | *Deferred:* Terminal-Bench via CLI; editor-mode | — | — | — | sep. |

**Total V1: ~3 focused weeks** (Phases 0–6), separate branch off `dev`, one PR per phase (or per the single-PR-per-session rule — slices on one branch).

Sequencing note: Phase 1 (backend) and the **scaffolding** of Phase 2 (CLI verbs, which already exist) can overlap; the chat REPL in Phase 2 depends on Phase 1's loop. Phase 4 (VS Code) depends on the shared `render.ts` + `chat.ts` produced in Phase 2. Phase 3 (LOCAL) can land before or after Phase 4 but the permission-gate UX must ship to both surfaces in the same phase to preserve parity.

---

## 2. Phase detail

### Phase 0 — Foundation hardening (prereq for everything)
**Why first:** the entire TS surface is reasoned-not-compiled; the landed backend has defects. Nothing else is trustworthy until this passes.
**Tasks**
- `cd clients && npm install` (commit `package-lock.json`); `npm run typecheck` + `npm run build`; fix all nits.
- Add a CI job (`clients` workspace): typecheck + build + lint (extend `.github/workflows/`).
- Fix landed backend defects (design §10): write-guard on approve/reject/cancel; `executionMode` value (stop passing `"REMOTE"`); converge the three `localExecution` notions on `executionLocality` (field + dead client field + `run local`); `SpecChatController` → 4xx mapping; add `/api/spec` to `AccessGuardCoverageTest`.
- Scaffold `core/capabilities.ts` (the parity registry) + the parity-check test (initially asserting the gen-1 verbs ↔ VS Code commands).
**Exit criteria:** `npm run build` green; `mvn verify` green incl. the new guard fixes + coverage pin; CI runs the clients job; parity check present (may be trivially green).
**Defends against:** shipping uncompiled code; the security drift (write-as-read guard); the incoherent local-flag wire contract.

### Phase 1 — Backend chat loop (`/api/spec/chat` step 3)
**Lands:** F5 server half. **Flag:** `spec.chat.enabled` (OFF).
**Tasks**
- Replace the placeholder emitter with the real loop (design §6.3): `conversation` → Spring AI messages; bind `ProjectChatActionTools` + read-only `ProjectChatTools` via `LlmRequest.toolsOverride`; stream `token`/`tool_call`/`tool_result`/`done`; route via `LlmRouter` LIGHTWEIGHT.
- Stateless: persist only `spec_chat_audit`; never store bodies.
- Per-action-tool integration tests (auth-gate + happy path): `submit_run`, `approve_plan` (mocked `GateStore` + real `RunAccessGuard`), `cancel_run`, `list_recent_runs`, `get_run_report`.
- Client compaction contract: define "keep last N verbatim, summarize older" so the client knows what to send.
**Exit criteria:** an integration test drives a 3-turn conversation (submit → approve → report) through the endpoint with mocked LLM; audit row increments; no content persisted; guards enforced.
**Defends against:** the model approving without authz (guard test); content leakage (audit-only assertion).

### Phase 2 — CLI: chat REPL + verbs + link (REMOTE mode)
**Lands:** F1–F14, F17–F25 (CLI side). **Flag:** `spec.chat.enabled`.
**Tasks**
- `core/chat.ts` (SSE consumer for `/api/spec/chat` + conversation manager + compaction), `core/localState.ts` (`StateBackend` abstraction; `FileBackend`), `core/link.ts` (explicit link/unlink + project.json), `core/render.ts` (surface-agnostic render model: turn/token/tool-call/result/gate → render events).
- `cli/repl.ts` (readline REPL; `/provider`, `/local`, `/runs`, `/help` slash-commands; Ctrl-C cancels current turn, not the process), `cli/render.ts` (ANSI renderer over `core/render.ts`).
- `cli/index.ts`: `spec` on TTY → REPL; non-TTY/piped/`--json`/explicit verb → deterministic path. Binary `spec` + alias `executespec` (drop `es`).
- Wire the deterministic verbs (mostly exist) to the same `core` calls the action tools use; `--json` on all reads.
- **Cost gate (design §6.4):** measure $/turn + cache-hit on a representative session. If outside envelope → escalate the prefix-cache fallback into Phase 5.
**Exit criteria:** end-to-end REMOTE run via REPL (submit → gate `[y/N]` → complete → PR URL) **and** via verbs + `--json | jq`; resume last session; explicit link works; cost-gate measurement recorded.
**Defends against:** scripting regressions (`--json` snapshots); REPL hijacking the process on Ctrl-C; cost surprise (gate).

### Phase 3 — LOCAL-mode activation (both surfaces)
**Lands:** F15, F16. **Flag:** `spec.local.enabled` (OFF).
**Tasks**
- Backend: add `PipelineRun.executionLocality` (`REMOTE|LOCAL`, migration `V223+`); in `RunOrchestrator` for LOCAL — `remoteToolDispatcher.register(runId)` at start, `unregister(runId)` at every terminal (success/fail/cancel), **skip server clone**, **skip `ScmPushService` auto-push**.
- Client: `LocalExecutionSession` already consumes `TOOL_CALL` + posts results; add the permission-gate UX (F16) to **both** surfaces (CLI `[allow/deny]`; VS Code quick-pick + "always allow").
- Explicit one-time per-machine LOCAL opt-in; remember per-project default in `project.json`.
**Exit criteria:** a LOCAL run executes tools against the local tree, posts results, completes; **network-egress test proves no source upload**; nothing pushed without explicit opt-in; permission gate denies a destructive command on both surfaces.
**Defends against:** silent code upload; unbounded local shell; orphaned sessions (unregister on every terminal path, incl. crash/cancel).

### Phase 4 — VS Code: sidebar chat + tree/modal parity
**Lands:** F1–F25 (VS Code side). **Flag:** `spec.chat.enabled`.
**Tasks**
- `vscode/chatPanel.ts` + `chatPanel.html` (webview chat over `core/chat.ts` + `core/render.ts`; render parity with the CLI).
- Keep the Runs TreeView + native plan-gate modal (deterministic governance); add detail webview (F9/F13/F14), retry action (F24), link/unlink commands (F17/F18), provider quick-pick (F19), diagnostics (F25).
- `VsCodeBackend` for `core/localState.ts`; `SecretStorage` PAT (exists).
- **Parity check turns mandatory:** the registry diff must be green (every F-row reachable in VS Code).
**Exit criteria:** every Feature-Matrix row reachable in VS Code; parity check green; the same 3-turn flow as the CLI works in the sidebar; LOCAL mode works from VS Code (needs an open workspace folder).
**Defends against:** feature drift (parity gate); no-workspace LOCAL attempts (F15 disabled + explained when no folder open).

### Phase 5 — Privacy hardening + cost fallback
**Tasks**
- BYOK-only LOCAL option (org policy): LOCAL runs may be restricted to the org's own keys.
- Re-verify audit-only-no-content end to end; add the egress assertion to CI where feasible.
- **If the Phase-2 cost gate failed:** implement the prefix-cache fallback (design §6.4) behind `spec.prefixcache.enabled`: encrypted, TTL'd, prefix-only, surfaced in audit. **If the gate passed, this is a no-op.**
**Exit criteria:** "your code → your provider only" is demonstrable; cost within envelope (natively or via prefix cache).

### Phase 6 — Polish + packaging + docs
**Tasks**
- `npm publish @executespec/cli` (+ `core`); VSIX build (esbuild, exists) → sideload, then Marketplace.
- `spec doctor` (F25) hardened; first-run onboarding copy.
- Docs: README for `spec`; plugin README; update root + per-package `CLAUDE.md`, `CODEMAP.md`, `document_map.md`; reason-log entry.
**Exit criteria:** installable from npm + Marketplace (or sideload); docs in lockstep; demo script passes on a clean machine.

### Phase 7 — Deferred
- **Terminal-Bench via CLI** (validates local-hands; depends on Phases 1–3) — see [`pending.md`](../pending.md) §J3.
- **Editor-mode** (inline completions / gutter actions / watcher) — separate effort.

---

## 3. Full feature set — positive scenarios (happy paths)

Each must pass on **both** surfaces (CLI affordance + VS Code affordance).

| # | Scenario | Expected |
| --- | --- | --- |
| P1 | First-run auth | Paste PAT → resolves to user → stored (0600 / SecretStorage); `status` shows email + tier. |
| P2 | Submit REMOTE run + watch | submit → live status/task/cost events stream → plan gate opens. |
| P3 | Approve at gate (deterministic) | `[y/N]`/modal → approve → worker resumes → COMPLETE → PR URL shown. |
| P4 | Reject at gate | reject → run CANCELLED → no PR. |
| P5 | Chat multi-turn intent | "build X" → model calls `submit_run`; "approve it" → model surfaces gate, human confirms; "show the report" → `get_run_report` renders score. |
| P6 | Scripted/CI use | `spec run submit … --json \| jq -r .runId`; `spec run view <id> --json`; exit codes correct (0 ok, 1 failed/cancelled, 2 usage). |
| P7 | LOCAL run end-to-end | `--local` → tools run on local tree → permission prompts → COMPLETE; **no source uploaded**; nothing pushed. |
| P8 | Provider switch | `/provider deepseek` or `--provider` → subsequent run uses it; persisted per session/project. |
| P9 | Resume session | reopen `spec`/sidebar → last conversation restored from local history. |
| P10 | SSE reconnect | network blip mid-watch → reconnect with `Last-Event-ID` → no duplicate/lost terminal event. |
| P11 | Explicit link | `spec link --create` (or pick existing) → `.executespec/project.json` written after confirmation. |
| P12 | Cost visibility | live cost during run; `run cost` + report show $ + tokens. |
| P13 | Retry failed task | `run retry <id> <taskId>` / tree action → task re-runs. |
| P14 | Multi-repo REMOTE run | run touches N linked repos → per-repo PRs + cross-link surfaced. |
| P15 | Parity | every Feature-Matrix row demonstrably reachable from both surfaces (the parity check encodes this). |
| P16 | Offline-then-online verbs | `run list` offline → clear "can't reach server" → succeeds when back. |
| P17 | Doctor | `spec doctor` reports host reachable, token valid, docker present/absent. |

## 4. Full feature set — negative / edge scenarios (must be designed for)

Grouped by failure domain. Each row states the trigger and the required behavior.

**Auth / credential**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N1 | No token | Read verbs/chat → clear "sign in" message; exit 1; no stack trace. |
| N2 | PAT expired/revoked mid-session | 401 → prompt re-auth; don't loop; don't crash the REPL. |
| N3 | Env token vs file token | `EXECUTESPEC_TOKEN` wins (documented); `status` shows which source. |
| N4 | PAT lacks write scope (future scoping) | mutating action → "forbidden (read-only token)" envelope, not a 500. |
| N5 | Token in shell history / logs | never logged; redacted in `doctor`/diagnostics output. |

**Network / transport**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N6 | Server down / DNS fail | bounded retry (2/4/8/16s + jitter) then `ApiError(0,"can't reach server")`. |
| N7 | 429 rate limit | honor `Retry-After`; backoff; surface "rate limited, retrying". |
| N8 | SSE idle timeout (~5 min) | reconnect with new scoped token + `Last-Event-ID`; invisible to user. |
| N9 | SSE drop after terminal | consumer breaks on terminal event; no infinite reconnect. |
| N10 | Malformed SSE frame | tolerate (treat as LOG); don't abort the stream. |
| N11 | Clock skew vs PAT expiry | rely on server 401, not local clock, for validity. |

**Chat / LLM loop**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N12 | Large conversation (cost) | client compaction (last N verbatim + summary); Phase-2 gate; prefix-cache fallback. |
| N13 | Model hallucinates a tool / bad args | `ProjectChatActionTools` returns `{ok:false,error}`; loop continues; no 500. |
| N14 | Model tries to approve without human | governance is deterministic — even if the model calls `approve_plan`, the design requires a human-confirmed path; (policy: action-tool approve should require an explicit human-confirm token in the loop, or be surfaced-not-executed). |
| N15 | Model loops / runaway turns | server caps tool rounds; client shows turn count + a stop affordance (Ctrl-C / Stop button). |
| N16 | Provider outage mid-chat | `LlmRouter` failover per YAML chain; surface the switch; don't lose the turn. |
| N17 | Stateless replay impossible | acceptable — local history is the record; audit has cost/timing. |

**Local-hands execution**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N18 | Tool call exceeds 120s | dispatcher returns timeout envelope; agent sees a tool error, not a hang. |
| N19 | Path escapes workspace | `safeResolve` → `FORBIDDEN`; no read/write outside root. |
| N20 | Destructive shell (`rm -rf`, etc.) | permission gate prompts; deny → `FORBIDDEN` envelope; `autoApprove=false` honored. |
| N21 | No Docker for build/test in LOCAL | file/shell tools work; build/test detection warns; run can still proceed/degrade. |
| N22 | Client disconnects mid-LOCAL-run | `unregister` on terminal; pending tool futures complete with "session closed"; run fails cleanly, not orphaned. |
| N23 | Which repo is "local" in multi-repo LOCAL? | V1: LOCAL supports single-repo/workspace-root only; multi-repo LOCAL is explicitly out of scope + errors clearly. |
| N24 | Binary/huge file in search/read | size + binary guards (existing in runtime.ts); truncate; skip. |
| N25 | Concurrent edits (user edits while agent edits) | last-write semantics; surface a warning; no merge attempt in V1. |

**Governance / run lifecycle**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N26 | Approve a run not owned by caller | `requireRunWriteAccess` → 403; not silently allowed (defect-fix from Phase 0). |
| N27 | Approve/cancel an already-terminal run | idempotent no-op with clear message. |
| N28 | Two clients approve same gate | `GateStore.tryClaimPlanGate` idempotency; second is a no-op. |
| N29 | Cancel races completion | cancel after terminal → no-op; before → interrupts + closes PRs. |
| N30 | Credits insufficient at approve | server rejects; client surfaces the credit shortfall, not a generic error. |

**Project / linking**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N31 | `spec` in `/`, `~`, `/tmp` | no auto-create; explicit link required; system-dir guard. |
| N32 | `spec` in unlinked dir | offer to link (confirm), or run project-less REMOTE; never silent-create. |
| N33 | Linked project deleted server-side | clear "project not found — relink"; don't crash. |
| N34 | `.executespec/project.json` corrupted | detect + offer relink; don't hard-fail. |

**VS Code specifics**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N35 | No workspace folder open | LOCAL (F15) disabled with explanation; REMOTE + read features still work. |
| N36 | Multi-root workspace | prompt which root is the run's working tree. |
| N37 | Webview reload / VS Code restart | restore conversation from storage; reconnect stream. |
| N38 | SecretStorage unavailable (rare) | fall back to prompting per session; never write PAT to disk in plaintext. |

**Config / environment**
| # | Trigger | Required behavior |
| --- | --- | --- |
| N39 | Wrong `--host` / dev vs prod | `doctor` surfaces the mismatch; clear connect error. |
| N40 | Old backend without `/api/spec/chat` | feature-detect; fall back to verbs; clear "chat needs server ≥ X". |
| N41 | Trailing slash / scheme in host | normalized (`config.ts` strips trailing `/`). |
| N42 | Non-UTF8 / huge spec input | bounded; reject with clear message. |

## 5. Test plan

- **Unit (core):** SSE parser (framing, resume, terminal, malformed); `http.ts` retry/backoff/429; `auth.ts` token precedence; `tools/runtime.ts` per-tool (incl. path-escape, permission-deny, truncation); `pathSafety` traversal; compaction.
- **Contract:** client tool arg-shapes ↔ server `tool/*` schemas; `--json` snapshot per read verb; envelope byte-compat (`{ok,content}` / `{ok,kind,message}`).
- **Backend integration:** `/api/spec/chat` 3-turn loop (mocked LLM); each action tool (auth-gate + happy + forbidden); audit-only-no-content assertion; `/api/spec` guard-coverage pin.
- **Parity:** registry diff (CLI commands ↔ VS Code contributions) green.
- **E2E (manual + scripted, per `VERIFY.md` discipline):** REMOTE run both surfaces; LOCAL run + **egress capture proving no source upload**; reconnect; gate approve/reject; provider switch.
- **Security:** PAT never logged (grep the transcript); LOCAL egress; write-guard 403 on others' runs; side-effect gate deny.
- **Negative harness:** drive N1–N42 where automatable; checklist the rest.

## 6. Risk register

| Risk | Likelihood | Impact | Mitigation |
| --- | --- | --- | --- |
| Stateless chat blows prompt-cache cost | Med | High | Phase-2 cost gate + pre-approved prefix-cache fallback (design §6.4). |
| Feature drift between surfaces | High (without guard) | Med | Capability registry + CI parity check; both renderers in one PR. |
| TS rot (never compiled) | High | Med | Phase 0 compile + CI gate before any feature work. |
| LOCAL mode leaks source | Low | Critical | Egress test in CI/E2E; clone/push skipped; tool-results are the only channel. |
| Governance via LLM weakens audit | Med | High | Deterministic + audited approve/reject; model surfaces, human confirms. |
| Auto-link footgun (if Shape B #4 kept) | Med | Med | Revised to explicit link (ADR §6); re-confirm with founder. |
| Orphaned remote sessions | Med | Med | `unregister` on every terminal path incl. cancel/crash; startup reconciler. |
| Founder re-confirm latency on 3 revised decisions | Med | Med | Ship Phases 0–2 (REMOTE) which don't depend on #3/#4; gate #2 at Phase 2. |
| VS Code webview effort underestimated | Med | Med | Render model shared with CLI; tree+modal already exist as fallback surface. |
| Multi-repo LOCAL ambiguity | Low | Med | Out of scope V1; clear error. |

## 7. Rollout

- Branch off `dev`; slices on one branch per the single-PR-per-session rule.
- Flags default OFF: `spec.chat.enabled`, `spec.local.enabled`, `spec.prefixcache.enabled`. The default server pipeline stays byte-identical until LOCAL is explicitly activated (the tool-proxy branch only fires once a LOCAL run registers a session).
- Dogfood internally (founder + team) on `dev` before npm/Marketplace publish.
- Publish gating: Phase 6 only after Phases 0–5 exit criteria pass + docs in lockstep.

## 8. Definition of done (V1)

- All Feature-Matrix rows reachable on both surfaces; parity check green.
- `npm run build` + `mvn verify` green; clients in CI.
- The 6 landed defects fixed (design §10).
- LOCAL egress test proves no source upload; nothing pushed without opt-in.
- Plan-gate approvals 100% deterministic + audited.
- Phase-2 cost within envelope (natively or via prefix cache).
- Docs (ADR/design/this plan + README/CLAUDE.md/CODEMAP/document_map/reason-log) in lockstep.

## 9. Dependencies + cross-references

- Backend tool-proxy (dormant) — `RemoteToolDispatcher`/`RemoteToolController`/`ToolRegistry` branch; activation = Phase 3.
- PAT auth (V215) — done.
- `spec_chat_audit` (V218) + `SpecChatController` skeleton + `ProjectChatActionTools` — done; loop = Phase 1.
- Web control-plane reframe ([`web-control-plane-reframe.md`](web-control-plane-reframe.md)) — parallel; web = mission control, this = authoring client.
- Terminal-Bench ([`pending.md`](../pending.md) §J3) — depends on Phases 1–3.
- Open questions (design §11) — founder re-confirm on ADR §6 items 2/3/4.
