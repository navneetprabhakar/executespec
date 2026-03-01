package com.navneet.agentteam.recovery;

public enum StuckClassification {

    /** Last log event was an outbound LLM request with no response received within threshold. */
    LLM_TIMEOUT,

    /** Task blocked on file lock with no progress. */
    FILE_LOCK_BLOCK,

    /** Same tool called 3+ times in log window with no observable state change. */
    LLM_LOOP,

    /** Build or test failure; worker has not committed a fix within threshold period. */
    BUILD_FAILURE_STALL,

    /** Cannot determine cause from log pattern — safe default to fresh restart. */
    UNKNOWN
}
