package com.navneet.executespec.deployment.preview;

/** Result of a {@code docker compose exec} command run inside a preview container. */
public record ExecResult(String stdout, String stderr, int exitCode) {}
