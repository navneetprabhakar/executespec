package com.navneet.agentteam.recovery;

import lombok.Getter;

@Getter
public class LlmCallException extends RuntimeException {

    private final LlmFailure failure;

    public LlmCallException(LlmFailure failure) {
        super("LLM call failed: " + failure.getClass().getSimpleName());
        this.failure = failure;
    }
}
