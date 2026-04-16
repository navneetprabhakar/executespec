package com.navneet.executespec.execution.model;

public record ExecutionDecision(String action) {
    public static final String SKIP = "SKIP";
    public static final String RETRY = "RETRY";
    public static final String FIX = "FIX";
    public static final String ATTEMPT_ANYWAY = "ATTEMPT_ANYWAY";
}
