package com.navneet.executespec.export;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.navneet.executespec.auth.entity.Project;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.ResponseStatus;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

/** Tenant-scoped command/query facade for the V289 durable export protocol. */
@Service
@RequiredArgsConstructor
public class ProjectExportService {

  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9._:-]{8,128}");

  private final ProjectExportJobStore store;
  private final ProjectExportProperties properties;
  private final MeterRegistry meterRegistry;

  public ProjectExportJobStore.Job request(
      Project project, String userId, String requestedIdempotencyKey) {
    if (project == null || project.getId() == null || project.getOrgId() == null) {
      throw new IllegalArgumentException("project required");
    }
    if (userId == null || userId.isBlank()) throw new IllegalArgumentException("user required");
    String key = normalizeIdempotencyKey(requestedIdempotencyKey);

    var replay = store.findByIdempotency(project.getId(), userId, key);
    if (replay.isPresent()) return replay.get();
    var active = store.findActive(project.getId());
    if (active.isPresent()) return active.get();

    long recent = store.countCreatedSince(project.getId(), Instant.now().minus(1, ChronoUnit.DAYS));
    if (recent >= properties.getMaxJobsPerProjectPerDay()) {
      Counter.builder("executespec.project.export.rejected")
          .tag("reason", "DAILY_LIMIT")
          .register(meterRegistry)
          .increment();
      throw new ExportQuotaExceededException();
    }

    UUID id = UUID.randomUUID();
    String projectLabel =
        project.getId().length() <= 80 ? project.getId() : project.getId().substring(0, 80);
    String fileName = "project-" + projectLabel + "-export-" + id + ".tar.gz";
    ProjectExportJobStore.Job job =
        store.createOrGet(
            id, project.getId(), project.getOrgId(), userId, key, boundedFileName(fileName));
    Counter.builder("executespec.project.export.requested").register(meterRegistry).increment();
    return job;
  }

  public List<ProjectExportJobStore.Job> list(Project project) {
    return store.listProject(
        project.getId(), project.getOrgId(), Math.max(1, Math.min(properties.getListLimit(), 100)));
  }

  public ProjectExportJobStore.Job get(Project project, UUID jobId) {
    return store
        .getScoped(jobId, project.getId(), project.getOrgId())
        .orElseThrow(ProjectExportNotFoundException::new);
  }

  public ProjectExportJobStore.Job cancel(Project project, UUID jobId, String actorUserId) {
    return store
        .requestCancellation(jobId, project.getId(), project.getOrgId(), actorUserId)
        .orElseThrow(ProjectExportNotFoundException::new);
  }

  public boolean recordDownload(Project project, UUID jobId, String actorUserId) {
    return store.recordDownload(jobId, project.getId(), project.getOrgId(), actorUserId);
  }

  public List<ProjectExportJobStore.AuditEvent> audit(Project project, UUID jobId) {
    get(project, jobId);
    return store.listAudit(jobId, project.getId(), project.getOrgId());
  }

  static String normalizeIdempotencyKey(String requested) {
    String key =
        requested == null || requested.isBlank() ? UUID.randomUUID().toString() : requested.trim();
    if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
      throw new InvalidIdempotencyKeyException();
    }
    return key;
  }

  private static String boundedFileName(String fileName) {
    String safe = fileName.replaceAll("[^A-Za-z0-9._-]", "-");
    return safe.length() <= 320 ? safe : safe.substring(0, 320);
  }

  public static class ExportQuotaExceededException extends RuntimeException {}

  @ResponseStatus(value = HttpStatus.NOT_FOUND, reason = "project export not found")
  public static class ProjectExportNotFoundException extends RuntimeException {}

  public static class InvalidIdempotencyKeyException extends RuntimeException {}
}
