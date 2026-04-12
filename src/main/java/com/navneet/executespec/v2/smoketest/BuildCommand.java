package com.navneet.executespec.v2.smoketest;

import lombok.Builder;

/**
 * A resolved build command used by V2's smoke-test layer.
 *
 * @param command          the shell command to execute (single string, run via {@code /bin/sh -c})
 * @param timeoutSeconds   how long to wait before treating the build as failed
 * @param sourceDescription human-readable origin (e.g. {@code "planner-declared"},
 *                         {@code "inferred:npm"}) for logs and validation reports
 */
@Builder
public record BuildCommand(String command, int timeoutSeconds, String sourceDescription) {

    public BuildCommand {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("command required");
        if (timeoutSeconds <= 0) timeoutSeconds = 120;
        if (sourceDescription == null || sourceDescription.isBlank()) sourceDescription = "unknown";
    }
}
