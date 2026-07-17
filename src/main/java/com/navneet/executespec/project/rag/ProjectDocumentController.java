package com.navneet.executespec.project.rag;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/projects/{projectId}/documents")
@RequiredArgsConstructor
public class ProjectDocumentController {

  /** API readiness is route-aware; a historical vector never masquerades as currently usable. */
  public record DocumentView(
      String id,
      String filename,
      String mimeType,
      Long sizeBytes,
      String status,
      String errorCode,
      Integer pageCount,
      Integer extractedChars,
      Integer estimatedTokens,
      String retrievalMode,
      boolean semanticSearchAvailable,
      boolean lexicalSearchAvailable,
      String readinessReason,
      String embeddingProvider,
      String embeddingModel,
      OffsetDateTime createdAt) {}

  private final ProjectDocumentService service;
  private final ProjectAccessGuard projectAccessGuard;

  @PostMapping
  public ResponseEntity<DocumentView> upload(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @RequestPart("file") MultipartFile file) {
    // GA-readiness P4.1-followup — RAG document upload writes to project state.
    // RL-2026-05-040: write guard (was assertCanRead → cross-org viewer could upload).
    projectAccessGuard.assertCanWrite(projectId, principal);
    String userId = principal != null ? principal.getUser().getId() : null;
    ProjectDocument doc = service.upload(projectId, file, userId);
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .body(view(doc, service.embeddingAvailability(projectId)));
  }

  @GetMapping
  public ResponseEntity<List<DocumentView>> list(
      @AuthenticationPrincipal AppUserDetails principal, @PathVariable String projectId) {
    // GA-readiness P4.1-followup — document list exposes filenames + sizes
    // tied to the project; tenant-bound.
    projectAccessGuard.assertCanRead(projectId, principal);
    var availability = service.embeddingAvailability(projectId);
    return ResponseEntity.ok(
        service.list(projectId).stream().map(doc -> view(doc, availability)).toList());
  }

  @DeleteMapping("/{documentId}")
  public ResponseEntity<Map<String, String>> delete(
      @AuthenticationPrincipal AppUserDetails principal,
      @PathVariable String projectId,
      @PathVariable String documentId) {
    // GA-readiness P4.1-followup — destructive op; verify tenant before delete.
    // RL-2026-05-040: write guard (was assertCanRead).
    projectAccessGuard.assertCanWrite(projectId, principal);
    service.delete(projectId, documentId);
    return ResponseEntity.ok(Map.of("status", "deleted"));
  }

  /** Stable, client-safe rejection body; parser exceptions and workspace paths never cross HTTP. */
  @ExceptionHandler(DocumentAdmissionException.class)
  public ResponseEntity<ProblemDetail> handleAdmission(DocumentAdmissionException rejection) {
    ProblemDetail problem =
        ProblemDetail.forStatusAndDetail(rejection.status(), rejection.getMessage());
    problem.setTitle("Document upload rejected");
    problem.setProperty("code", rejection.code());
    return ResponseEntity.status(rejection.status()).body(problem);
  }

  private DocumentView view(
      ProjectDocument document, EmbeddingPolicyResolver.Availability availability) {
    boolean persistedSemantic =
        document.getStatus() == ProjectDocument.Status.READY
            && "SEMANTIC".equals(document.getRetrievalMode())
            && document.getEmbeddingProvider() != null
            && document.getEmbeddingModel() != null;
    boolean semantic = persistedSemantic && availability.semanticSearchAvailable();
    boolean lexical =
        document.getStatus() == ProjectDocument.Status.READY
            || document.getStatus() == ProjectDocument.Status.LEXICAL_READY;
    String status = document.getStatus().name();
    String mode = document.getRetrievalMode();
    String reason = document.getErrorCode();
    if (persistedSemantic && !semantic) {
      status = "LEXICAL_READY";
      mode = "LEXICAL";
      reason = availability.reason();
    } else if (semantic) {
      reason = "GOVERNED_OPENAI_EMBEDDING_READY";
    } else if (document.getStatus() == ProjectDocument.Status.LEXICAL_READY) {
      reason = availability.reason();
    }
    return new DocumentView(
        document.getId(),
        document.getFilename(),
        document.getMimeType(),
        document.getSizeBytes(),
        status,
        document.getErrorCode(),
        document.getPageCount(),
        document.getExtractedChars(),
        document.getEstimatedTokens(),
        mode,
        semantic,
        lexical,
        reason,
        semantic ? document.getEmbeddingProvider() : null,
        semantic ? document.getEmbeddingModel() : null,
        document.getCreatedAt());
  }
}
