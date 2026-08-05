package com.navneet.executespec.codeintel;

import java.time.Instant;
import java.util.UUID;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * REST surface for {@link ProjectDocGeneratorService}. Two doc types (EXPLANATION, SETUP), durable
 * generation/job reads, explicit uncertain-outcome retry confirmation, display, and download.
 */
@RestController
@RequiredArgsConstructor
public class ProjectDocGeneratorController {

  private final ProjectDocGeneratorService service;
  private final ProjectGeneratedDocJobService jobs;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;

  public record GeneratedDocDto(
      UUID id,
      String docType,
      Integer version,
      String content,
      String generatedBy,
      Instant generatedAt,
      String modelUsed,
      Integer inputTokens,
      Integer outputTokens) {
    static GeneratedDocDto from(ProjectGeneratedDoc d) {
      return new GeneratedDocDto(
          d.getId(),
          d.getDocType() == null ? null : d.getDocType().name(),
          d.getVersion(),
          d.getContent(),
          d.getGeneratedBy(),
          d.getGeneratedAt(),
          d.getModelUsed(),
          d.getInputTokens(),
          d.getOutputTokens());
    }
  }

  public record GeneratedDocJobDto(
      UUID id,
      String projectId,
      String docType,
      String status,
      boolean terminal,
      String provider,
      String model,
      UUID resultDocId,
      Integer resultDocVersion,
      String errorCode,
      String errorMessage,
      int attemptCount,
      Instant deadlineAt,
      Instant providerStartedAt,
      Instant createdAt,
      Instant startedAt,
      Instant completedAt) {
    static GeneratedDocJobDto from(ProjectGeneratedDocJobStore.Job job) {
      return new GeneratedDocJobDto(
          job.id(),
          job.projectId(),
          job.docType(),
          job.status().name(),
          job.status().terminal(),
          job.requestedProvider(),
          job.requestedModel(),
          job.resultDocId(),
          job.resultDocVersion(),
          job.errorCode(),
          truthfulLegacyErrorMessage(job.errorMessage()),
          job.attemptCount(),
          job.deadlineAt(),
          job.providerStartedAt(),
          job.createdAt(),
          job.startedAt(),
          job.completedAt());
    }

    private static String truthfulLegacyErrorMessage(String message) {
      return message == null
          ? null
          : message.replace(
              "provider execution began",
              "provider dispatch began; delivery outcome may be uncertain");
    }
  }

  @PostMapping("/api/projects/{projectId}/docs/{docType}/generate")
  public ResponseEntity<GeneratedDocJobDto> generate(
      @PathVariable String projectId,
      @PathVariable String docType,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    DocType type = parseType(docType);
    if (userDetails == null || userDetails.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    ProjectGeneratedDocJobStore.Job job =
        jobs.request(projectId, type, userDetails.getUser().getId());
    return ResponseEntity.accepted()
        .location(
            java.net.URI.create(
                "/api/projects/" + projectId + "/docs/" + type + "/jobs/" + job.id()))
        .body(GeneratedDocJobDto.from(job));
  }

  @GetMapping("/api/projects/{projectId}/docs/{docType}/jobs/{jobId}")
  public ResponseEntity<GeneratedDocJobDto> job(
      @PathVariable String projectId,
      @PathVariable String docType,
      @PathVariable UUID jobId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    DocType type = parseType(docType);
    ProjectGeneratedDocJobStore.Job job = jobs.get(projectId, jobId);
    if (!job.docType().equals(type.name())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document generation job not found");
    }
    return ResponseEntity.ok(GeneratedDocJobDto.from(job));
  }

  @GetMapping("/api/projects/{projectId}/docs/{docType}/jobs/latest")
  public ResponseEntity<GeneratedDocJobDto> latestJob(
      @PathVariable String projectId,
      @PathVariable String docType,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    DocType type = parseType(docType);
    return jobs.latest(projectId, type)
        .map(job -> ResponseEntity.ok(GeneratedDocJobDto.from(job)))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @PostMapping("/api/projects/{projectId}/docs/{docType}/jobs/{jobId}/confirm-retry")
  public ResponseEntity<GeneratedDocJobDto> confirmRetry(
      @PathVariable String projectId,
      @PathVariable String docType,
      @PathVariable UUID jobId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    DocType type = parseType(docType);
    if (userDetails == null || userDetails.getUser() == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }
    ProjectGeneratedDocJobStore.Job job =
        jobs.confirmRetry(projectId, type, jobId, userDetails.getUser().getId());
    return ResponseEntity.accepted().body(GeneratedDocJobDto.from(job));
  }

  @GetMapping("/api/projects/{projectId}/docs/{docType}")
  public ResponseEntity<GeneratedDocDto> latest(
      @PathVariable String projectId,
      @PathVariable String docType,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    DocType type = parseType(docType);
    return service
        .latest(projectId, type)
        .map(d -> ResponseEntity.ok(safeDto(d)))
        .orElseGet(() -> ResponseEntity.noContent().build());
  }

  @GetMapping("/api/projects/{projectId}/docs/{docType}/download")
  public ResponseEntity<String> download(
      @PathVariable String projectId,
      @PathVariable String docType,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    projectAccessGuard.assertCanRead(projectId, userDetails);
    DocType type = parseType(docType);
    ProjectGeneratedDoc doc =
        service
            .latest(projectId, type)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        docType + " doc has not been generated for this project yet"));
    String content = doc.getContent() == null ? "" : doc.getContent();
    try {
      service.assertSafeForCustomer(content);
    } catch (ProjectDocGeneratorService.UnsafeGeneratedDocumentException unsafe) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Generated document contains credential-like material and cannot be downloaded");
    }
    String filename = type.name().toLowerCase() + ".md";
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .contentType(MediaType.parseMediaType("text/markdown; charset=utf-8"))
        .body(content);
  }

  private static DocType parseType(String raw) {
    try {
      return DocType.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "unknown doc type '" + raw + "' (expected EXPLANATION or SETUP)");
    }
  }

  private GeneratedDocDto safeDto(ProjectGeneratedDoc doc) {
    String content = doc == null || doc.getContent() == null ? "" : doc.getContent();
    try {
      service.assertSafeForCustomer(content);
    } catch (ProjectDocGeneratorService.UnsafeGeneratedDocumentException unsafe) {
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Generated document failed the publication safety check and cannot be displayed");
    }
    return GeneratedDocDto.from(doc);
  }
}
