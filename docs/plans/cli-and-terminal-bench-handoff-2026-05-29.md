# CLI + Terminal-Bench — Session Handoff (2026-05-29)

> **Purpose:** capture the strategic direction + pending work after the deploy-readiness sprint so the next session can pick up cleanly.
> **Author session:** 2026-05-28 → 2026-05-29 — landed native DeepSeek + 4 quality-lever flags + CLI/Plugin Phase 1 steps 1-2 + V218–V221 + deploy bindings + deploy-test unblock.

---

## 1. Operational state (as of 2026-05-29)

| Surface | State |
|---|---|
| `feat/benchmark-codegen-harness` | Merged to `dev` via PR #187 (`32f7d5a6`) |
| `fix/deploy-test-unblock` | PR #188 open against `dev` — unblocks the deploy that failed on assertion errors (DEEPSEEK enum-size + 4 pre-existing iter-counter `@Disabled`s + new `mvn test` CI gate on PRs). Awaiting CI green + merge |
| Initial deploy run | Run [26614013176](https://github.com/navneetprabhakar/executespec/actions/runs/26614013176) — FAILED at `mvn test` step. Fixed by PR #188 |
| Redeploy after #188 | Will auto-fire on merge to `dev`. Tracked separately |
| Migrations applied | V218–V221 (additive). Head: **V221** |
| API keys on dev GCS | `OPENROUTER_API_KEY` + `DEEPSEEK_API_KEY` added to `gs://executespec-dev-deploy/config/api-keys`; `GOOGLE_API_KEY` rotated. **Pending: rotate the leaked keys** that the user pasted in chat (see [§4 Security debt](#4-security-debt)). |
| Working tree (this branch) | Clean. (The Terraform working-tree edits from `cost-optimization-2026-05-29` session — `infra/environments/dev.tfvars` + `infra/modules/storage/main.tf` — are sitting on `feat/benchmark-codegen-harness`, not promoted here.) |

## 2. Pending tasks — categorized

### A. User-blocked (no code work; waits on operator action)

- **#92** Rotate Gemini API key on dev server — actually done via the GCS api-keys file (see §1) but the task row is still `pending`. Close on next session.
- **Security rotation:** OpenRouter / DeepSeek / Gemini keys that landed in the chat transcript on 2026-05-29 are compromised. Revoke + reissue + re-update the GCS api-keys file.

### B. Strategic — committed direction (this doc's main payload)

- **CLI/Plugin Phase 1 step 3** (task #106) — wire `ProjectChatActionTools.bind(...)` into `SpecChatController`, add `ChatService` stateless-mode flag, replace the placeholder SSE-emitter with a real `LlmRouter` dispatch loop. See [§5](#5-cli-plan).
- **CLI/Plugin Phases 2–4** — REPL render + local state + auto-link + VS Code chat sidebar. See [§5](#5-cli-plan).
- **Drop ProgramBench** — codebase cleanup; not ready for it yet. See [§6](#6-programbench-removal).
- **Terminal-Bench via CLI** — eval framework for the CLI's local-execution capability. See [§7](#7-terminal-bench-plan-depends-on-cli).

### C. Engineering improvements that DIDN'T ship this session (Tranche 3 carryover)

- **T3.8 — Better file retrieval / embeddings.** Server-side RAG extension to cover code chunks (~1d) + local-only embedder for CLI/Plugin via `xenova/transformers` (~1d). User signed off on both Options A (server) and B (CLI-local) but exec was deferred.

### D. Eval — the four flags shipped this session need to be measured

Default-OFF flags awaiting a controlled eval to justify flipping defaults to ON:

| Flag | Migration | Expected lift |
|---|---|---|
| `worker.build-tdd.enabled` | V217 | +8-12pp on BUILD-mode SWE-bench-like sets |
| `worker.provider-prompt-tuning.enabled` | V219 | +3-8pp |
| `worker.cross-provider-fallback.enabled` | V220 | +5-10pp (matplotlib-23563 + django-11742 are the proof-cases) |
| `worker.retry-context-retention.enabled` | V221 | +3-5pp |

**To run:** flip via `PUT /api/admin/feature-flags/<key>`, re-run mixed10 on Anthropic + OpenAI, compare to baseline in `~/swebench-bench/mixed10-5way-comparison-2026-05-28.md`. State doc at `~/swebench-bench/STATE-2026-05-28.md` has the exact commands.

### E. Known follow-ups

- **`c72fa557` iter-counter root-cause** — the 4 `@Disabled` tests need real fixing, not just disable-tags. The off-by-one was acknowledged in the commit message but never addressed; PR #188 just unblocks the deploy. Re-enable after the iter increment is debugged + corrected.
- **`run-core-codegen.sh` patchBytes race-condition fix** (T4.15 / #91) — local-only fix to the bench script, never committed to the repo because the script lives in `~/swebench-bench/`. If we ever publish the harness, this needs to be in the published copy.

## 3. Why drop ProgramBench

Operational reality: ProgramBench was integrated into the V2 worker stack but never relied on for customer-facing work. SWE-bench (with the BUILD-mode wrapper in `benchmark/`) covers the same shape — multi-language container task + verification — without the ProgramBench-specific machinery.

Architecturally, **Terminal-Bench is going to cover the "shell agent" eval slot** that ProgramBench was tangentially testing — and it'll do it through the CLI's real local-hands path, not a server-side container adapter. That's strictly better because it validates the actual product story (code stays local) instead of testing infrastructure that production runs never touch.

Keeping ProgramBench is paid-for complexity without a customer-visible benefit. Drop it.

## 4. Security debt

Three keys exposed in the chat transcript on 2026-05-29 — the user pasted them while updating the GCS api-keys file. Per `~/.claude/memory/feedback_no_guessing.md` standing rule analogue, treat as compromised:

- OpenRouter
- DeepSeek
- Google AI (Gemini)

**Action:** revoke at the provider consoles, reissue, re-run the GCS api-keys update with the new values, redeploy.

This is the single highest-priority follow-up.

## 5. CLI plan

Anchored to the design doc `docs/design/cli-plugin-spec-shape-b-2026-05-28.md` (5 locked decisions: binary=`spec`, LOCAL-only chat persistence, LOCAL default mode, auto-link Option B, VSIX sideload). Implementation status snapshot:

| Phase | What | State |
|---|---|---|
| 1 | Backend chat endpoint + action tools | **Step 1 done** (cdd1788a — controller skeleton + V218 audit). **Step 2 done** (b311cd71 — `ProjectChatActionTools` with 6 write tools). **Step 3 pending** (wire-up + dispatch loop + tests, task #106) |
| 2 | CLI chat REPL + local state + auto-link | Not started |
| 3 | Shape B activation wiring (`PipelineRun.executionLocality` + RunOrchestrator skip-clone + skip-push + dispatcher register/unregister) | Not started |
| 4 | VS Code chat sidebar webview | Not started |
| 5 | Privacy hardening (BYOK-only LOCAL + audit-only-no-content server logging) | Step-2 audit already audit-only; full hardening pending |
| 6 | Polish + marketplace publish | Future |

### Phase 1 step 3 — the critical next piece (~10h focused)

1. `SpecChatController` — inject `ProjectChatActionTools` + read-only `ProjectChatTools` (existing), build the per-turn `ToolContext`.
2. Replace the placeholder ack/token/done with a real LLM dispatch loop:
   - Translate the incoming `conversation: List<Map<String, Object>>` into Spring AI `Message` list.
   - Bind tools via `LlmRouter.LlmRequest.toolsOverride(...)`.
   - Stream tokens via `SseEmitter.event("token").data({delta: ...})` on each chunk.
   - On `tool_call` from the model, emit `event("tool_call").data({tool, args, id})`, dispatch via the bound `ToolCallback`, append the result, continue the loop.
   - On terminal: `event("done").data({usage, cost_usd, turn_count})`.
3. `ChatService` stateless-mode flag — extend the existing `chat/ChatService` constructor with a `boolean statelessMode` (or a separate `@Service` like `SpecChatService` that doesn't persist messages — cleaner). The `spec_chat_audit` row is the only persistence; conversation content stays in the client's `~/.executespec/chats/<project_id>-<session_uuid>.jsonl`.
4. Per-action-tool integration tests:
   - `submit_run` against `TestRestTemplate` + mocked `RunOrchestrator`
   - `approve_plan` against mocked `GateStore` + real `RunAccessGuard`
   - `list_recent_runs` against in-memory `PipelineRunRepository`
   - `get_run_report` against mocked `ValidationReportService`
5. `AccessGuardCoverageTest` — current pin set doesn't include `/api/spec/`. Add an allowlist entry OR a coverage rule for `/api/spec/chat/{`.

### Phases 2–4 — the CLI client itself (3-5d)

Per CLAUDE.md, the CLI + VS Code clients already exist (auth + run submit/watch/list/view/approve/reject/cancel/report + experimental `run local`). The new work for the chat-REPL surface:

- `clients/core/src/chat.ts` — SSE consumer for `/api/spec/chat`, conversation manager
- `clients/core/src/localState.ts` — read/write `~/.executespec/{auth.json,state.json,sessions/*,chats/*}`
- `clients/core/src/autoLink.ts` — auto-create-project from CWD basename per decision #4 (with system-dir safeguards)
- `clients/cli/src/repl.ts` — readline-based interactive chat
- `clients/cli/src/render.ts` — ANSI rendering of tool calls + assistant turns
- `clients/cli/src/index.ts` — drop into REPL by default
- `clients/vscode/src/chatPanel.ts` — webview chat sidebar
- `clients/vscode/src/chatPanel.html` — chat UI (mirrors CLI render)

## 6. ProgramBench removal

**Scope of the cleanup:**

| Layer | Files |
|---|---|
| Package | `programbench/{Bootstrap,ContainerStore,Controller,Properties,TaskService}.java` |
| Tools (drop the `programBenchContainers != null` branch) | `tool/ShellTool.java`, `tool/BuildProjectTool.java`, `tool/RunTestsTool.java`, `tool/ShellSandboxPolicy.java` |
| Orchestration | `integration/RunOrchestrator.java`, `harness/SequentialExecutor.java`, `smoketest/WorkspaceTestRunner.java` |
| Worker / classifier | `engine/WorkerAgent.java`, `agent/SmartClassifierAgent.java` |
| State | `state/RunMode.java`, `state/entity/PipelineRun.java` (drop any PROGRAMBENCH enum values + any `source LIKE 'programbench%'` paths) |
| SWE-bench imports | `swebench/{SwebenchProperties,Bootstrap,ContainerStore,TaskService}.java` (likely just import cleanup, not behavior) |
| Tests | `arch/AsyncAllowlistTest.java`, `tool/BuildProjectToolWrapTest.java`, `tool/ShellSandboxPolicyTest.java` |
| Migration | V129 created `program_trial` — add a follow-up V222+ that `DROP TABLE program_trial` if we want it gone from prod schema (or leave the dead table; harmless) |

**Effort:** 1-2 focused days, one PR against `dev`. Cleanest after CLI Phase 1 step 3 lands — that way the action-tool surface area doesn't churn under the cleanup.

## 7. Terminal-Bench plan (depends on CLI)

The point of running Terminal-Bench through the CLI rather than as a server-side container adapter: **it validates the local-hands story.** Building it server-side would test "the worker can run shell commands in a docker container we control" — which is the same thing SWE-bench already proves. Building it through the CLI tests "the worker can run shell commands on the **customer's** machine via the CLI proxy" — which is the actual enterprise pitch.

### Architecture

```
┌─ Customer machine ───────────────────────────────────┐
│  spec CLI                                            │
│  ├─ bench/terminal-bench/                            │
│  │  ├─ tasks/<task-id>/                              │
│  │  │  ├─ task.yaml  (description + container image) │
│  │  │  └─ tests/     (verification script)           │
│  │  └─ runner.ts                                     │
│  ├─ Docker (runs task container)                     │
│  └─ ToolRuntime (proxies server tool calls)          │
└───────────────────┬──────────────────────────────────┘
                    │ HTTPS + SSE
┌───────────────────▼──────────────────────────────────┐
│  ExecuteSpec backend                                 │
│  ├─ SpecChatController (Phase 1 must be done first)  │
│  ├─ Worker / Planner / Reviewer pipeline             │
│  └─ Verification dispatcher                          │
└──────────────────────────────────────────────────────┘
```

### Implementation order

1. **Phase 1 step 3 + Phases 2-3 of CLI must be done first** — there's no Terminal-Bench without the local-hands proxy.
2. **`clients/cli/src/bench/terminalbench/` package** — task loader (parses Terminal-Bench's `task.yaml`), container lifecycle (`docker run`/`docker stop`), verification dispatcher (`docker exec ... tests/run.sh` on completion).
3. **`spec bench terminal-bench`** CLI subcommand:
   - `spec bench terminal-bench --case <id>` — single task smoke
   - `spec bench terminal-bench --set <name>` — run a named slice (e.g. `2.1-representative-20`)
   - `spec bench terminal-bench --all` — full 2.1 set
4. **Server-side adapter** — minimal `terminalbench/` package mirroring `benchmark/` (BUILD-mode wrapper) — Bootstrap + Controller + RunService. Most plumbing reused.
5. **Scoring** — categorized RESOLVED% (system-admin / scientific / data-eng / security / dev-tooling), latency-per-task, cost-per-task. Match the SWE-bench score JSON shape so the existing `score-core-codegen.sh`-style tooling can consume it.

### Effort estimate

- ~3d adapter + CLI subcommand (assuming CLI chat surface is already working)
- ~2d category-stratified scoring + report generation
- ~1d evaluation driver script + first publishable run on 2.1's representative-20 slice
- Total: ~1 focused week, separate branch off `dev`

### What it tells us

**Two independent dimensions of agent capability** — code-edit (SWE-bench) + shell-driving (Terminal-Bench) — give a much richer picture than either alone, and Terminal-Bench specifically validates the CLI's privacy story rather than just our server's container plumbing. SOTA today is ~52% (Claude Sonnet 4.6); if we ship the CLI cleanly and hit 40%+ on Terminal-Bench 2.1, that's a real signal.

## 8. Quick-start for next session

```bash
# 1. Get the deploy story straight
gh pr view 188                                       # is the deploy unblock merged?
gh run list --workflow=deploy.yml --branch=dev -L 3  # what's the latest deploy?

# 2. Confirm the new 5 providers are wired on dev
curl -s https://dev.executespec.ai/api/models/available | jq '.providers[] | {id, label}'

# 3. Pick up where the strategy starts
# CLI Phase 1 step 3 (task #106) — see §5 above
# Or ProgramBench removal — see §6

# 4. Eval-the-flags is the other valuable thing — see §2.D
# State doc: ~/swebench-bench/STATE-2026-05-28.md
```

## 9. Open questions for the operator

1. **Eval-vs-CLI ordering** — flip the 4 levers and measure lift on the existing branch first, OR start CLI Phase 1 step 3 immediately and run the eval in parallel? Author's recommendation: eval first (~3-4h compute, then we know what we shipped), then CLI.
2. **ProgramBench removal timing** — before or after CLI Phase 1 step 3? Author's recommendation: after step 3 lands; the cleanup is a satisfying contained-PR break between step 3 and Phases 2-4.
3. **Terminal-Bench task subset** — start with the 2.1 representative-20 slice (publishable but small) or jump straight to the full 100-task set? Author's recommendation: representative-20 for the first measurable score, then full on the next eval cycle once we know the harness is reliable.
