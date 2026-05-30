# Audit-remediation deferred items — handoff index (2026-05-30)

> Companion to [`docs/audit/REMEDIATION-2026-05-29.md`](../audit/REMEDIATION-2026-05-29.md).
> Every item the remediation passes **did not** complete on `fix/audit-remediation-2026-05-29`,
> with its handoff location. None is a silent gap — each is here with a concrete next step.

| Item | Why deferred | Handoff |
| --- | --- | --- |
| **RunOrchestrator god-class split** | 5.3k-LOC stateful file; auto-extraction corrupted it twice (caught at compile). | [runorchestrator-god-class-split.md](runorchestrator-god-class-split.md) — concrete 6-increment by-hand plan. |
| **B1 — coordinator wiring (P4) + live smokes** | All 3 provider clients now shipped + unit-tested (Anthropic/OpenAI/Gemini); only the `BatchCoordinator` wiring into `RunOrchestrator` + per-provider live smokes remain (Gemini also needs batch-envelope shape verification). | [v2-batch-execution-plan.md](v2-batch-execution-plan.md) — P4 wiring + human gate. |
| **C3 — tenant ORM read-isolation** | High-risk `@Filter` retrofit; C2 (app-layer guards) is the finding's hard requirement and is closed. | [c3-tenant-orm-isolation.md](c3-tenant-orm-isolation.md) — approach, the 5 exemption landmines, flag-gated entity-by-entity rollout. |
| **B3 — DeepSeek native-client smoke** | Client lives on another branch; needs a live key + real call. | §"B3" below (short — one smoke run on `feat/benchmark-codegen-harness`). |
| **D-residual — dead-code deletions** | Each needs per-item dynamic-ref verification; the audit's "deletable" list proved unreliable. | §"D-residual" below + [`docs/tech-debt.md`](../tech-debt.md) + REMEDIATION §3. |

---

## B3 — DeepSeek native-client smoke (short handoff)

The native DeepSeek client is **already written** on `feat/benchmark-codegen-harness` (not on the
remediation branch): `LlmProvider.DEEPSEEK` + `LlmRouter.callDeepSeek` (reuses
`OpenAiNativeToolClient` plumbing with a per-call `api.deepseek.com` base-URL) + the
`spring.ai.deepseek.{base-url,api-key}` block + the `deepseek-v4-pro-native` matrix entry
(provider=deepseek, native pricing $0.27/$0.027/$1.10). It is **reasoned-not-smoked** — the backend
awaited a local key.

**To close it (on `feat/benchmark-codegen-harness`):**
1. `export DEEPSEEK_API_KEY=…` (and/or `spring.ai.deepseek.api-key` in `application-local.yml`).
2. Run a single-shot through the real pipeline with `aiProvider=deepseek`,
   `modelName=deepseek-v4-pro-native` (a tiny PATCH/BUILD spec, or `scripts/tier-eval-v2.sh
   --providers deepseek`).
3. **Assert:** the call returns native (no OpenRouter hop), `RunCostTracker` records it at the
   native rate, and round-trip latency beats the OpenRouter path (the motivating ~3–4× routing
   overhead the branch note cites). No code change expected — this is validation.

> Not a remediation-branch change. Porting the client onto `fix/audit-remediation-*` just to smoke
> it would be the wrong seam — run it where it lives, then merge that branch on its own track.

## D-residual — dead-code deletions (short handoff)

Deferred because the audit's "deletable" list was **unreliable** (verified during remediation —
see REMEDIATION §3):
- `planner.v1-v14.txt` are **NOT** deletable — loaded by filename pattern by
  `PromptVersioningService` (a literal-string grep shows 0 refs but deleting them breaks rollback).
- `validator/` is **boot-active** ("shells ~5× per boot") — not inert.

Genuinely-deletable candidates, each needing a per-item dynamic-ref check first, tracked in
[`docs/tech-debt.md`](../tech-debt.md):
- `signal/pages/ExtraPages.tsx` (2,782 LOC, 14 unrelated pages) → split per-page (frontend §52).
- Dead `@Deprecated` reference methods (`ContextAgent.commitToMain`,
  `PromptOptimiserAgent.commitToMain`, `PatchAgent.buildSystemPromptInlineDeprecated`) (§33).
- Three dual-variant panels still shipping dead `'signal'` branches (`PrReviewPanel`,
  `CodeReviewPanel`, `PrCommentInbox`) — collapse the prop (§58).
- The superseded swebench **oracle** adapter (PR #88) — its numbers were discarded; the blind
  `benchmark/` harness supersedes it.

**Method for the cleanup session:** for each, grep + check for *dynamic* references (reflection,
filename-pattern loads, string-built routes) before deleting; delete in small commits with a full
build per item. Do **not** batch-delete from the list blind.
