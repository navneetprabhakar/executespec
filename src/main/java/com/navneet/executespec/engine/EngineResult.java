package com.navneet.executespec.engine;

import java.util.List;

import lombok.Builder;

/**
 * Provider-agnostic code-gen result (RL-2026-05-052). The captured change set + the evidence the
 * engine gathered — no DB writes, no SSE, no push (the caller wires those downstream).
 *
 * @param diff the captured change set (git diff vs base, infra-excluded); empty when nothing was
 *     written
 * @param verification build/test/repro evidence the engine gathered (null fields = not measured)
 * @param transcript the message + tool-call log
 * @param cost tokens + $ (cache-aware, per-model)
 * @param outcome the terminal state of the loop
 * @param summary the agent's completion summary (null on non-COMPLETED outcomes)
 */
@Builder
public record EngineResult(
    String diff,
    EngineVerification verification,
    List<String> transcript,
    EngineCost cost,
    EngineOutcome outcome,
    String summary) {}
