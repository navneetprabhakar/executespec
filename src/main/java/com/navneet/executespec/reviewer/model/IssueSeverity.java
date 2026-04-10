package com.navneet.executespec.reviewer.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum IssueSeverity {
    CRITICAL,  // auto-fix attempted via worker
    MINOR;     // report only, human decides

    @JsonCreator
    public static IssueSeverity fromString(String value) {
        if (value == null) return MINOR;
        return switch (value.toUpperCase().trim()) {
            case "CRITICAL", "HIGH", "SEVERE", "ERROR", "BLOCKER" -> CRITICAL;
            case "MINOR", "LOW", "INFO", "TRIVIAL", "COSMETIC" -> MINOR;
            // IMPORTANT, MAJOR, MEDIUM, WARNING — treat as CRITICAL (worth auto-fixing)
            default -> CRITICAL;
        };
    }
}
