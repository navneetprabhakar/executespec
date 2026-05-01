package com.navneet.executespec.api.dto;

public record FeasibilityRequest(
    String goal,
    /** Optional — if null/absent the report still runs but verdict is "ESTIMATED". */
    Double budgetUsd,
    /** Provider for the workers (e.g. "anthropic"). */
    String provider,
    /** Specific model name (e.g. "claude-sonnet-4-5"). */
    String model) {}
