package com.navneet.executespec.engine;

import java.nio.file.Path;

import lombok.Builder;

/**
 * Provider-agnostic code-gen request — the engine seam (RL-2026-05-052, {@code
 * docs/plans/codegen-engine-extraction.md} §2).
 *
 * @param taskSpec the unit of work (issue / task description / failing-test spec)
 * @param workspace the <b>prepared</b> working tree (the engine does NOT clone/checkout)
 * @param provider LLM provider id ({@code anthropic} / {@code openai} / {@code gemini} / {@code
 *     openrouter})
 * @param model provider model id (per-tier / per-org overrides are resolved by the caller)
 * @param budget iter / wall-clock / $ caps (null fields = engine/tier default)
 * @param verifyMode {@link VerifyMode#NONE} | {@link VerifyMode#SOFT_NUDGE} | {@link
 *     VerifyMode#HARD_GATE} (the caller-selected verify lever)
 * @param runId correlation id for cost tracking / observability — NOT a product dependency
 * @param taskId correlation id within a run
 */
@Builder
public record EngineRequest(
    String taskSpec,
    Path workspace,
    String provider,
    String model,
    EngineBudget budget,
    VerifyMode verifyMode,
    String runId,
    String taskId) {}
