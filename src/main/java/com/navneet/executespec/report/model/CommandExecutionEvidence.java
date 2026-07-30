package com.navneet.executespec.report.model;

/** Reloadable deterministic command evidence bound to a validation workspace generation. */
public record CommandExecutionEvidence(
    String command, String workingDirectory, Integer exitCode, String boundedOutput) {}
