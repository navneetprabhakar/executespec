package com.navneet.executespec.export;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Admin-only, tenant-scoped async project-export API. Archive bytes never run on this thread. */
@RestController
@RequestMapping("/api/projects/{projectId}/export")
@PreAuthorize("hasAnyRole('SUPER_ADMIN','ORG_ADMIN')")
@RequiredArgsConstructor
@Slf4j
public class ProjectExportController {

  private final ProjectExportService exportService;
  private final ProjectExportObjectStore objectStore;
  private final ProjectAccessGuard projectAccessGuard;

  /** Queue or idempotently return the active export. */
  @PostMapping
  public ResponseEntity<ProjectExportJobDto> create(
      @PathVariable String projectId,
      @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = projectAccessGuard.assertCanWrite(projectId, userDetails);
    try {
      var job = exportService.request(project, userDetails.getUser().getId(), idempotencyKey);
      return ResponseEntity.accepted()
          .header(HttpHeaders.LOCATION, statusPath(projectId, job.id()))
          .body(ProjectExportJobDto.from(job));
    } catch (ProjectExportService.ExportQuotaExceededException exceeded) {
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "project export daily limit reached");
    } catch (ProjectExportService.InvalidIdempotencyKeyException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid Idempotency-Key");
    }
  }

  @GetMapping
  public List<ProjectExportJobDto> list(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = projectAccessGuard.assertCanRead(projectId, userDetails);
    return exportService.list(project).stream().map(ProjectExportJobDto::from).toList();
  }

  @GetMapping("/{jobId}")
  public ProjectExportJobDto status(
      @PathVariable String projectId,
      @PathVariable String jobId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = projectAccessGuard.assertCanRead(projectId, userDetails);
    return ProjectExportJobDto.from(exportService.get(project, parseJobId(jobId)));
  }

  @PostMapping("/{jobId}/cancel")
  public ResponseEntity<ProjectExportJobDto> cancel(
      @PathVariable String projectId,
      @PathVariable String jobId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = projectAccessGuard.assertCanWrite(projectId, userDetails);
    var job = exportService.cancel(project, parseJobId(jobId), userDetails.getUser().getId());
    HttpStatus status =
        job.status() == ProjectExportJobStore.Status.CANCEL_REQUESTED
            ? HttpStatus.ACCEPTED
            : HttpStatus.OK;
    return ResponseEntity.status(status).body(ProjectExportJobDto.from(job));
  }

  @GetMapping("/{jobId}/audit")
  public List<ProjectExportAuditDto> audit(
      @PathVariable String projectId,
      @PathVariable String jobId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = projectAccessGuard.assertCanRead(projectId, userDetails);
    return exportService.audit(project, parseJobId(jobId)).stream()
        .map(ProjectExportAuditDto::from)
        .toList();
  }

  @GetMapping("/{jobId}/download")
  public ResponseEntity<?> download(
      @PathVariable String projectId,
      @PathVariable String jobId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    Project project = projectAccessGuard.assertCanRead(projectId, userDetails);
    UUID id = parseJobId(jobId);
    ProjectExportJobStore.Job job = exportService.get(project, id);
    if (job.status() == ProjectExportJobStore.Status.EXPIRED
        || job.status() == ProjectExportJobStore.Status.EXPIRING
        || (job.expiresAt() != null && !job.expiresAt().isAfter(Instant.now()))) {
      throw new ResponseStatusException(HttpStatus.GONE, "project export expired");
    }
    if (job.status() != ProjectExportJobStore.Status.SUCCEEDED || job.objectKey() == null) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "project export is not ready");
    }
    if (!objectStore.exists(job.objectKey())) {
      log.error("project export artifact missing job={} project={}", job.id(), projectId);
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "project export artifact unavailable");
    }
    if (!exportService.recordDownload(project, id, userDetails.getUser().getId())) {
      throw new ResponseStatusException(HttpStatus.GONE, "project export expired");
    }

    StreamingResponseBody body = output -> objectStore.copyTo(job.objectKey(), output);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(job.fileName()).build().toString())
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .contentType(MediaType.parseMediaType(job.contentType()))
        .contentLength(job.sizeBytes() == null ? 0 : job.sizeBytes())
        .body(body);
  }

  private static UUID parseJobId(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid export job id");
    }
  }

  private static String statusPath(String projectId, UUID jobId) {
    return "/api/projects/" + projectId + "/export/" + jobId;
  }

  public record ProjectExportJobDto(
      UUID id,
      String status,
      String fileName,
      Long sizeBytes,
      String sha256,
      int attemptCount,
      String errorCode,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt,
      Instant expiresAt,
      Instant cancelRequestedAt,
      Instant lastDownloadedAt,
      long downloadCount,
      boolean cancellable,
      boolean downloadable) {
    static ProjectExportJobDto from(ProjectExportJobStore.Job job) {
      boolean cancellable =
          job.status() == ProjectExportJobStore.Status.QUEUED
              || job.status() == ProjectExportJobStore.Status.RUNNING
              || job.status() == ProjectExportJobStore.Status.CANCEL_REQUESTED;
      boolean downloadable =
          job.status() == ProjectExportJobStore.Status.SUCCEEDED
              && job.expiresAt() != null
              && job.expiresAt().isAfter(Instant.now());
      return new ProjectExportJobDto(
          job.id(),
          job.status().name(),
          job.fileName(),
          job.sizeBytes(),
          job.sha256(),
          job.attemptCount(),
          job.errorCode(),
          job.createdAt(),
          job.startedAt(),
          job.completedAt(),
          job.expiresAt(),
          job.cancelRequestedAt(),
          job.lastDownloadedAt(),
          job.downloadCount(),
          cancellable,
          downloadable);
    }
  }

  public record ProjectExportAuditDto(
      UUID id,
      String eventType,
      String outcome,
      String actorUserId,
      String details,
      Instant occurredAt) {
    static ProjectExportAuditDto from(ProjectExportJobStore.AuditEvent event) {
      return new ProjectExportAuditDto(
          event.id(),
          event.eventType(),
          event.outcome(),
          event.actorUserId(),
          event.details(),
          event.occurredAt());
    }
  }
}
