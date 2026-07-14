# Adversarial follow-up — project scale, routing, and cleanup

Captured 2026-07-15 against the current `codex/executespec-2.0` worktree. This
is an engineering-risk assessment, not a release approval.

## Release-relevant findings

| Priority | Finding | Evidence | Required response |
| --- | --- | --- | --- |
| P0/P1 | Gemini project-chat quality is not at parity | Six-provider smoke completed HTTP 200 but used 31,298 input tokens and ended at the tool-call limit; Anthropic/OpenAI used 3,918 input tokens and returned clean summaries | Keep Gemini out of preferred project-chat routing until a repeated held-out cohort passes |
| P1 | Architecture/doc generation remains synchronous at the HTTP boundary | `ProjectArchitectureController` and `ProjectDocGeneratorController` call long-running generation directly; the doc service is `@Transactional` while making provider calls | Move generation to a durable async job/status contract and keep DB transactions around persistence only |
| P1 | Large-repository source bundle has a hard global evidence ceiling | `ProjectDocGeneratorService.capSourceBundle` now cuts at a complete line and emits an explicit omission marker (covered by `ProjectDocGeneratorServiceCacheHitTest`); it still gives no per-repository fairness, continuation handle, or completeness metadata | Replace the global cap with per-repository/token budgets plus completeness metadata and continuation handles; do not describe the current implementation as mid-record truncation |
| P1 | Project chat always routes through PLANNING | `ProjectChatController` and `ProjectChatService` resolve `AgentTier.PLANNING` for every turn | Route simple read-only turns to a lightweight tier with escalation on ambiguity/complexity; preserve explicit provider/model pins |
| P1 | Canonical turn protocol is duplicated | `ProjectChatService`, `SpecChatService`, and project/action adapters each maintain manual tool-loop/state semantics | Consolidate onto the agent-session turn executor; keep legacy endpoints as compatibility shims with deprecation telemetry |
| P1 | Maintenance surface remains oversized | `SequentialExecutor` 5,222 LOC; `RunOrchestrator` 4,086; `WorkerAgent` 3,330; frontend API core 1,735; several IDE screens exceed 1,700 LOC | Extract seam-owned collaborators and add module-level tests before further feature flags |
| P1 | Native visual evidence is still blocked | Deterministic TUI QA passes, but direct native capture hangs with child OpenTUI processes; browser evidence is fixture-level | Close process cleanup/capture reliability and obtain human visual sign-off before a world-class UX claim |

## What is already proven

- Backend `mvn verify` passes on the current worktree.
- Flyway local schema is contiguous through V272 with successful latest rows;
  no repair or destructive migration was required.
- Authenticated six-provider project-chat transport works for Anthropic, OpenAI,
  Gemini, DeepSeek, OpenRouter, and Fireworks.
- CLI/TUI provider/project contract tests pass 248/248; targeted Web/backend
  provider/session tests pass 39/39 and 44/44 respectively.

## Release interpretation

The system is mechanically healthier than its UX and quality evidence imply.
Transport success, deterministic tests, and a clean `verifiedScore` must not be
translated into provider parity, large-repository quality, or world-class UX.
The remaining work is primarily outcome evidence plus simplification of the
canonical turn and project-generation paths.
