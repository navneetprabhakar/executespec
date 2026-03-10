package com.navneet.executespec.recovery;

import lombok.Getter;

@Getter
public class LlmCallException extends RuntimeException {

    private final LlmFailure failure;

    public LlmCallException(LlmFailure failure) {
        super("LLM call failed: " + failure.getClass().getSimpleName());
        this.failure = failure;
    }
}
