package com.navneet.agentteam.recovery;

public sealed interface LlmFailure {

    /** 429 rate limit — transient, back off and retry same model */
    record RateLimited(int retryAfterSeconds, String provider) implements LlmFailure {}

    /** 429 billing / 402 — quota exhausted, escalate provider immediately */
    record BudgetExhausted(String provider, String message) implements LlmFailure {}

    /** 400 context too long — force compaction then retry */
    record ContextTooLong(int currentTokens, int modelLimit, String provider) implements LlmFailure {}

    /** 401 — key invalid or expired, needs human action */
    record AuthFailure(String provider, String message) implements LlmFailure {}

    /** 500 / 503 — provider side, retry with exponential backoff */
    record ServerError(int statusCode, String provider, String message) implements LlmFailure {}

    /** Anything else unclassified */
    record Unknown(int statusCode, String provider, String message) implements LlmFailure {}
}
