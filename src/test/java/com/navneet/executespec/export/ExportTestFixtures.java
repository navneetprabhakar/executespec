package com.navneet.executespec.export;

import java.time.Instant;
import java.util.UUID;

final class ExportTestFixtures {

  private ExportTestFixtures() {}

  static ProjectExportJobStore.Job job(ProjectExportJobStore.Status status) {
    Instant now = Instant.now().minusSeconds(60);
    boolean succeeded = status == ProjectExportJobStore.Status.SUCCEEDED;
    return new ProjectExportJobStore.Job(
        UUID.randomUUID(),
        "project-1",
        "org-1",
        "user-1",
        "request-key",
        status,
        "project-export.tar.gz",
        "application/gzip",
        succeeded ? "local:project-exports/a/1.tar.gz" : null,
        succeeded ? 42L : null,
        succeeded ? "a".repeat(64) : null,
        status == ProjectExportJobStore.Status.QUEUED ? 0 : 1,
        now,
        status == ProjectExportJobStore.Status.RUNNING ? "worker" : null,
        status == ProjectExportJobStore.Status.RUNNING ? 1 : 0,
        status == ProjectExportJobStore.Status.RUNNING ? now.plusSeconds(600) : null,
        null,
        now,
        status == ProjectExportJobStore.Status.RUNNING ? now : null,
        succeeded ? now : null,
        succeeded ? now.plusSeconds(3600) : null,
        null,
        null,
        0);
  }
}
