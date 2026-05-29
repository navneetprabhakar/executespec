# Audit: `assessment/` — pre-plan feature assessment

Status: **DONE** · Updated 2026-05-29 · 2 files / 292 lines · all read in full

## Files
- `FeatureAssessmentService.java` (274) — Pilot R4 heuristic (+ optional LLM) feature assessment ✓
- `AssessmentResult.java` (18) — record persisted to `pipeline_run.assessment_result` (V85) ✓

## What it does
Live: `RunOrchestrator:1106` calls `assessAsJson(projectId, userRequest)` during the ASSESSING phase. Surveys the project's linked repos, complexity-classifies the request, infers risks (auth/payment/migration/destructive keyword hits) + cross-repo deps, and emits an `AssessmentResult` persisted to `pipeline_run.assessment_result` (JSONB) + injected into the planner prompt + shown pre-approval. Default path is deterministic (no LLM); an opt-in LLM path (flag `assessment.llm.enabled`) calls an Anthropic model for a richer JSON assessment.

## Findings

### Cost-accounting + convention (the priority + CLAUDE.md MUST-NOTs)
- 🟡 **The LLM path calls the provider directly — bypassing `LlmRouter` AND `RunCostTracker`.** `assessViaLlm` does `anthropicModel.call(prompt)` straight on the injected `AnthropicChatModel` bean ([line 139](../../../src/main/java/com/navneet/executespec/assessment/FeatureAssessmentService.java)). CLAUDE.md lists "Direct HTTP calls to LLM providers" as a **MUST-NOT** and "LLM calls go through `LlmRouter` (or `FallbackLlmRouter`)". Consequence beyond style: these tokens are **invisible to `RunCostTracker`** (no `record()`), so when the flag is on the assessment spend escapes the per-run cost accounting + the credit estimate. It's also **Anthropic-only** regardless of the project's chosen provider. **Latent** today — `assessment.llm.enabled` has no seed migration (defaults off; the heuristic is the live path) — but it's a real cost-accuracy hole the moment it's flipped. Route it through `FallbackLlmRouter.callWithFallbackTracked` like the sibling `AccuracyAssessmentService` / `ValidationReportGenerator`.
- 🟡 **Field injection** — `@Autowired(required = false) private AnthropicChatModel anthropicModel` ([line 51-52](../../../src/main/java/com/navneet/executespec/assessment/FeatureAssessmentService.java)) violates CLAUDE.md "MUST NOT use field injection". The `required=false` optional-bean shape is the likely excuse (awkward via Lombok `@RequiredArgsConstructor`), but `ObjectProvider<AnthropicChatModel>` constructor-injected is the convention-compliant equivalent.

### Accuracy / Efficiency
- 🟢 **Deterministic default is the right posture for a pre-plan gate** — zero cost/latency, and any LLM-path failure quietly falls back to the heuristic so the user never blocks on assessment. Risk inference (auth/payment/migration/destructive) is a sensible cheap signal.
- 🔵 The heuristic is coarse — complexity purely by request length (`<200⇒S, <800⇒M, else L`), risks by substring match. It's explicitly a pilot with a documented LLM upgrade path. Low stakes (advisory; feeds planner context + a pre-approval summary, doesn't gate scoring), so coarseness is acceptable until signal says otherwise. The bigger lever is wiring the LLM path through the router (above) before enabling it.

## Cross-refs
Consumer → `integration/RunOrchestrator` ASSESSING phase (Tier 3) + planner prompt injection (`agent/OutlinePlannerAgent`, [agent.md](agent.md)). Persisted on `state/entity/PipelineRun.assessment_result` (V85, Tier 3). Sibling LLM-assessment done right (router + cost-tracked) → [reviewer.md](reviewer.md) (`AccuracyAssessmentService`). Arch/codemap inputs → `codeintel/` (Tier 4).
