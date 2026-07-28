package com.navneet.executespec.codeintel;

import java.time.Instant;
import java.util.UUID;

final class ProjectArchitectureJobFixture {
  private ProjectArchitectureJobFixture() {}

  static ProjectArchitectureJobStore.Job job(
      ProjectArchitectureJobStore.Status status, String phase) {
    return job(status, phase, null);
  }

  static ProjectArchitectureJobStore.Job job(
      ProjectArchitectureJobStore.Status status, String phase, UUID resultId) {
    Instant now = Instant.now();
    boolean running = status == ProjectArchitectureJobStore.Status.RUNNING;
    boolean prepared = !"PREPARING".equals(phase);
    return new ProjectArchitectureJobStore.Job(
        UUID.randomUUID(),
        "project-1",
        "org-1",
        "actor-1",
        null,
        prepared ? "{\"schemaVersion\":1,\"projectId\":\"project-1\",\"repositories\":[]}" : null,
        prepared ? "a".repeat(64) : null,
        "GEMINI",
        "gemini-architecture",
        "gemini-architecture",
        prepared ? "ctx-repo" : null,
        prepared ? "ctx-project" : null,
        status,
        phase,
        running ? 1 : 0,
        now,
        now.plusSeconds(7200),
        running ? "worker-1" : null,
        running ? 1 : 0,
        running ? now.plusSeconds(900) : null,
        null,
        null,
        null,
        null,
        null,
        resultId,
        resultId == null ? null : 7,
        resultId == null ? null : "a".repeat(64),
        null,
        null,
        now,
        running ? now : null,
        status.terminal() ? now : null,
        resultId == null ? null : now);
  }
}
