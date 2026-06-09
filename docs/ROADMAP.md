# Execution roadmap — prioritized

> Created 2026-06-01; **re-prioritized 2026-06-03; doc refresh 2026-06-06** — **founder priority: build the CLI first; only a
> critical bug pre-empts it.** SWE-bench eval **on hold**. Single ordered list across every active
> track. Detailed per-item register: [`pending.md`](pending.md) (sections referenced inline).
>
> Tags: `[severity · impact · effort]` — effort **S**(≤1d) / **M**(days) / **L**(week+). 🔒 = security gate · ⏳ = eval-gated before default-ON.
>
> **Recently cleared:** PR #199 merged [pending §A1–A3] · the 3 exposed keys rotated [pending §K1] · operator-only Terminal-Bench first-20 harness landed [pending §J3; DeepSeek tuned to 12/20]. **Quick win now unblocked:** toggle `router.failover.enabled` ON [pending §C1] — 5-min SUPER_ADMIN action, fail-safe.

## Why this order
Founder call (2026-06-03): **the CLI is the focus** — only a critical bug jumps ahead of it. The rest
keeps its prior dependency order. The `verifyBoot` keystone (#3) is no longer item #1, but it still
**leads the preview/score sub-tree** — once the CLI ships, that is where the non-CLI work converges,
because verifyBoot is preview-rebuild Phase 1 **and** the run-and-observe / verification-as-spine lever
**and** what makes `verifiedScore` deterministic instead of LLM-self-graded.

---

## P0 — Pre-empts everything: critical bugs
0. **Critical bugs jump the queue.** None currently tracked. If one surfaces (regression, data-integrity, security, tenant-leak, payment), it pre-empts the CLI until resolved.

## P1 — CURRENT FOCUS: build the CLI
1. **Unified `spec` client — chat-first CLI + VS Code, local-hands/remote-brain** `[—  · high · L]` — per ADR-0001 + [`plans/cli-vscode-unified-client-execution-2026-05-30.md`](plans/cli-vscode-unified-client-execution-2026-05-30.md). Server: `SpecChatController` real `LlmRouter` dispatch loop (replace the placeholder SSE) + `ChatService` stateless mode + per-action-tool integration tests + an `AccessGuardCoverageTest` pin for `/api/spec/`; the `RunOrchestrator` LOCAL-mode activation (register/unregister + skip-clone + skip-push). Clients: `clients/core` chat SSE consumer + local state + auto-link; `clients/cli` REPL + ANSI render (chat-first default, deterministic verbs retained); `clients/vscode` chat sidebar webview. [pending §J1]
   - **1b.** *(in-track polish)* **CLI-local embedder** (xenova/transformers) `[low · low · S]` — server-side code-chunk RAG already shipped; this is the local-only path. [pending §J4]

## P2 — The keystone (leads the preview/score sub-tree)
2. **`verifyBoot` — agent authors a run-recipe, boots + health-checks in-loop, feeds the BUILD/TESTS score** `[high · very high · L]` 🔒 — one item, three wins: (a) makes `verifiedScore` deterministic instead of 40–53% LLM-self-graded → fixes grading honesty; (b) closes the run-and-observe gap; (c) is the foundation for the whole preview rebuild. Routes through the `ShellSandboxPolicy` cleanroom (timeout + output budget + no host network). [`plans/preview-phase1-verifyboot-spec.md`]

## P3 — Latency quick wins (composes on the keystone)
3. **Latency Phase 0** `[med · high · S→M]` ⏳ — flip context-compaction flags `worker.tool-compactor` + `worker.history-compaction` ON after a no-regression eval (`scripts/v2-eval.sh`, **not** SWE-bench) → skip redundant per-task `buildProject` smokes (build only when build-relevant files changed) → per-tier budget re-tune now that context is smaller. Cuts HIGH-run wall-clock + token cost on every run. [pending §D1–D3]

## P4 — Preview rebuild + refactors (post-keystone)
4. **Preview rebuild core** `[med · high · L]` 🔒 — strangler-fig, each phase behind a flag then delete: build-once/run-same digest + authenticated previews (fix `CloudRunClient --allow-unauthenticated`) → buildpack default + **DELETE `DevDockerfileGenerator` (3,514 LOC)** + the 22 rewrites + 10 strategy classes → delete the `vm` mode + retire the `preview-engine` repo. Net LOC drops >50%. [`plans/preview-live-environment-rebuild.md`]
5. **B1 batch execution wiring** (`BatchCoordinator` + per-provider live smokes + submit-gate) `[low · med · M]` — ~50% LLM-cost lever, **but** batch APIs are async (hours), so it only applies to non-interactive paths (eval/bulk), not the live run. [pending §I2 · `plans/v2-batch-execution-plan.md`]
6. **RunOrchestrator god-class — next by-hand increment** `[med · med · M]` — independent, do locally with a compiler in the loop (automated extraction corrupted it twice; the original 6-increment split shipped + its plan doc was removed 2026-06-08). [pending §E1/I1]
7. **Mid-task re-planning + wire `TaskWaveScheduler`** (parallel exec) `[med · high · L]` ⏳🔒 — worker requests a re-plan when the plan is wrong → then the parallel-execution scheduler behind `harness.parallel-tasks.enabled` (core built + validated; the race-prone, eval-gated half). [pending §D4]
8. **Drop ProgramBench** (cleanup) `[low · med · M]` — paid-for complexity production never touches; SWE-bench covers the slot. Cleanest after the CLI surface settles (#1). [pending §J2]
9. **C3 tenant ORM read-isolation** (Hibernate `@Filter`) `[high · med · M→L]` — defense-in-depth; **C2 app-layer guards already satisfy the hard requirement**, so this is a backstop. High-risk retrofit; flag-gated entity-by-entity (5 exemption landmines). [pending §I3 · `plans/c3-tenant-orm-isolation.md`]

## P5 — Lower priority / optional / mechanical
10. **D-residual dead-code deletions** `[low · low · S each]` — per-item dynamic-ref check first (the audit's "deletable" list was unreliable). [pending §I5 · `tech-debt.md`]
11. **Mobile target** (Expo/EAS QR + `expo export --platform web` + device-cloud verification) `[low · med · L]` 🔒 — only once the preview rebuild is solid; never self-host iOS/Android simulators. [preview Phase 5]
12. **Pre-existing debt** `[low · med · M]` — frontend code-splitting + frontend unit tests (`frontend/src/lib/api/core.ts` ~2K LOC untested), `ProviderHealthTracker` circuit breaker on the V2 failover path, JAVADOC rewrite. [pending §G]
13. **DeepSeek native-client smoke** `[low · low · S]` — validation only, on `feat/benchmark-codegen-harness`. [pending §I4]
14. **Test the latest Qwen coding model via OpenRouter** `[low · low · S]` — founder request (2026-06-03), pick up at the end: add the newest verified Qwen coder slug to `OpenRouterModelMapper` + an `application.yml` matrix entry, then eval quality/latency/cost. [pending §L1]

## ⏸ Held (not scheduled)
- **SWE-bench eval-improvement track** [pending §H] — on hold (2026-06-03). Resume after the CLI lands.
- **`run-core-codegen.sh` patchBytes fix (J5)** — benchmark/eval-adjacent; held with the SWE-bench pause.
- **Rebrand** [pending §F0] — founder-gated; compiler-gated mechanical pass (~1,100 files), align with the clients' repo split.
- **`verifiedScore` weighting** [pending §F1] — #2 already de-risks this by making BUILD/TESTS deterministic; revisit the *weighting* after.
- **Claude Code / Codex head-to-head report** [pending §F2] — handled on your setup.

---

## Dependency map (the critical path)
```
P0 critical bugs (if any) ──▶ pre-empts the CLI

#1 CLI (FOCUS) ── largely independent; ships first
                  └─▶ #8 drop ProgramBench (cleanest after the CLI surface settles)
                  └─▶ production LOCAL-mode activation; the operator Terminal-Bench harness is already landed

#2 verifyBoot (KEYSTONE) ─┬─▶ #3 latency Phase 0
                          ├─▶ #4 preview rebuild ─▶ #11 mobile
                          └─▶ #7 re-planning ─▶ parallel exec
#5 batch · #6 RunOrchestrator · #9 C3 · #10 dead-code · #12 debt · #13 DeepSeek  ── independent (anytime)
```
The CLI (#1) runs first per founder priority. The non-CLI work converges on landing **#2 verifyBoot**.
