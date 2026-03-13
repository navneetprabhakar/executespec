package com.navneet.executespec.agent;

import lombok.Getter;

/**
 * Thrown when a suggestion plan exceeds the 6-task maximum and cannot be
 * applied in-place. Carries a compiled goal string that the user can use
 * to start a fresh run instead.
 */
@Getter
public class ApplySuggestionTooLargeException extends RuntimeException {

    private final String compiledGoal;

    public ApplySuggestionTooLargeException(String compiledGoal) {
        super("Suggestion plan exceeds 6-task limit. Please create a fresh run.");
        this.compiledGoal = compiledGoal;
    }
}
