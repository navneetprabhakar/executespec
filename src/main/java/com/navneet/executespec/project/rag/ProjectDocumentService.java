package com.navneet.executespec.project.rag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.auth.entity.ActivityAudit;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.project.rag.DocumentExtractor.ExtractionResult;
import com.navneet.executespec.project.rag.DocumentMalwareScanner.ScanResult;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.rag.entity.ProjectDocumentBlob;
import com.navneet.executespec.project.rag.repository.ProjectDocumentBlobRepository;
import com.navneet.executespec.project.rag.repository.ProjectDocumentRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDocumentService {

  private static final int IO_BUFFER = 8192;

  private final ProjectDocumentRepository repo;
  private final ProjectDocumentBlobRepository blobRepo;
  private final ProjectRepository projectRepo;
  private final OrgRepository orgRepo;
  private final ProjectWorkspaceService workspace;
  private final DocumentExtractor extractor;
  private final DocumentTypeInspector typeInspector;
  private final DocumentMalwareScanner malwareScanner;
  private final GeneratedSecretScanner secretScanner;
  private final ProjectDocumentProperties properties;
  private final ChunkingService chunker;
  private final EmbeddingService embedder;
  private final EmbeddingOperationRoot operationRoot;
  private final ChunkStore chunkStore;
  private final PlatformTransactionManager transactionManager;
  private final ApplicationEventPublisher events;
  private final ActivityAuditService auditService;

  /**
   * Fully admits hostile bytes before they become durable: bounded read, content-only type
   * inspection, malware scan, bounded extraction, then an atomic project-quota transaction.
   */
  public ProjectDocument upload(String projectId, MultipartFile file, String userId) {
    Project project =
        projectRepo
            .findById(projectId)
            .orElseThrow(
                () ->
                    new DocumentAdmissionException(
                        "PROJECT_NOT_FOUND", "Project not found.", HttpStatus.NOT_FOUND));
    String displayName = safeDisplayName(file == null ? null : file.getOriginalFilename());
    try {
      byte[] bytes = readBounded(file);
      var inspected = typeInspector.inspect(bytes, properties);
      enforceMalwarePolicy(project, bytes);
      ExtractionResult extraction = preflightExtraction(bytes, inspected.mimeType());
      enforcePerFileExtractionLimits(extraction);
      enforceCredentialContentPolicy(extraction);
      String documentId = UUID.randomUUID().toString();
      String relativePath = "docs/.uploads/" + documentId + ".bin";
      OffsetDateTime now = OffsetDateTime.now();
      ProjectDocument document =
          ProjectDocument.builder()
              .id(documentId)
              .projectId(projectId)
              .pathRelative(relativePath)
              .source("USER_UPLOAD")
              .filename(displayName)
              .mimeType(inspected.mimeType())
              .sizeBytes((long) bytes.length)
              .sha256(sha256(bytes))
              .uploadedBy(userId)
              .status(ProjectDocument.Status.UPLOADED)
              .retrievalMode("PENDING")
              .pageCount(extraction.pageCount())
              .extractedChars(extraction.characterCount())
              .estimatedTokens(extraction.tokenCount())
              .processingAttempt(0)
              .createdAt(now)
              .updatedAt(now)
              .build();

      ProjectDocument persisted =
          new TransactionTemplate(transactionManager)
              .execute(
                  status -> {
                    Project locked =
                        projectRepo
                            .findByIdForQuotaUpdate(projectId)
                            .orElseThrow(
                                () ->
                                    new DocumentAdmissionException(
                                        "PROJECT_NOT_FOUND",
                                        "Project not found.",
                                        HttpStatus.NOT_FOUND));
                    if (locked.getDeletedAt() != null) {
                      throw new DocumentAdmissionException(
                          "PROJECT_NOT_FOUND", "Project not found.", HttpStatus.NOT_FOUND);
                    }
                    enforceProjectQuotas(projectId, bytes.length, extraction);
                    ProjectDocument saved = repo.save(document);
                    blobRepo.save(
                        ProjectDocumentBlob.builder()
                            .documentId(documentId)
                            .contentBytes(bytes.clone())
                            .createdAt(now)
                            .build());
                    return saved;
                  });
      if (persisted == null) {
        throw new IllegalStateException("document admission transaction did not complete");
      }
      audit(project, userId, documentId, "PROJECT_DOCUMENT_ACCEPTED", "ACCEPTED");
      events.publishEvent(new ProjectDocumentProcessingEvent(documentId));
      return persisted;
    } catch (DocumentAdmissionException e) {
      audit(project, userId, projectId, "PROJECT_DOCUMENT_REJECTED", e.code());
      throw e;
    } catch (IOException e) {
      audit(project, userId, projectId, "PROJECT_DOCUMENT_REJECTED", "READ_ERROR");
      throw new DocumentAdmissionException("READ_ERROR", "The upload could not be read.");
    }
  }

  /** Idempotent worker entrypoint used by both post-commit async dispatch and restart recovery. */
  public void processDocument(String documentId) {
    processDocument(documentId, false);
  }

  /**
   * Upgrade path for a document that was published lexically while the semantic route was
   * unavailable. Uses the same fence/lease protocol, so a concurrent delete or an in-flight worker
   * still wins; an exhausted or no-longer-eligible document simply stays lexical.
   */
  public void reindexForSemantic(String documentId) {
    processDocument(documentId, true);
  }

  private void processDocument(String documentId, boolean semanticReindex) {
    ProcessingLease lease = claim(documentId, semanticReindex);
    if (lease == null) return;
    ProjectDocument claimed = lease.document();
    EmbeddingOperationRoot.Active operation = null;
    try {
      byte[] bytes =
          blobRepo
              .findById(documentId)
              .map(ProjectDocumentBlob::getContentBytes)
              .map(storedBytes -> storedBytes.clone())
              .orElseThrow(
                  () ->
                      new DocumentAdmissionException(
                          "SOURCE_BYTES_MISSING", "Stored document content is unavailable."));
      if (!heartbeat(lease)) return;
      ExtractionResult extraction = preflightExtraction(bytes, claimed.getMimeType());
      enforcePerFileExtractionLimits(extraction);
      enforceCredentialContentPolicy(extraction);
      persistOpaqueWorkspaceCopy(claimed, bytes);
      // RR-812 (2026-07-22) — delete() purges the workspace copy, then a still-running indexer that
      // cloned the source bytes before deletion writes the copy back above and (before this fence)
      // never rechecked ownership, resurrecting a supposedly purged file on disk until unrelated
      // cleanup. Re-fence immediately after the write: heartbeat proves we still hold a live lease
      // (a delete invalidates it, a takeover bumps the fence token); if we have lost it, remove the
      // copy we just recreated and stop rather than proceed against a deleted/taken-over document.
      if (!heartbeat(lease)) {
        deleteWorkspaceCopy(claimed.getProjectId(), claimed.getPathRelative(), documentId);
        return;
      }
      List<ChunkingService.Chunk> chunks = chunker.chunk(extraction.text());
      if (chunks.isEmpty()) {
        failTerminal(lease, "NO_EXTRACTABLE_TEXT", "No readable text was found.");
        return;
      }
      var embedding = embedder.availability(claimed.getProjectId());
      if (embedding.semanticSearchAvailable()) {
        if (!updateLeasedStatus(lease, ProjectDocument.Status.EMBEDDING, null, null, false)) return;
        EmbeddingOperationRoot.Request operationRequest = documentOperation(claimed);
        try {
          operation =
              operationRoot
                  .claim(operationRequest, lease.owner(), processingLeaseDuration())
                  .orElse(null);
          if (operation == null) {
            failTerminal(
                lease,
                "EMBEDDING_OPERATION_NOT_CLAIMABLE",
                "A prior embedding attempt has an uncertain or terminal outcome.");
            return;
          }
          EmbeddingOperationRoot.Active activeOperation = operation;
          if (!heartbeatEmbedding(lease)
              || !operationRoot.heartbeat(activeOperation, processingLeaseDuration())) {
            terminateOperation(
                activeOperation,
                "DOCUMENT_INDEX_LEASE_LOST",
                "Document-index lease was lost before provider dispatch");
            operation = null;
            return;
          }
          List<float[]> embeddings =
              embedder.embedForProject(
                  claimed.getProjectId(),
                  chunks.stream().map(ChunkingService.Chunk::content).toList(),
                  "ProjectDocumentEmbedding",
                  "PROJECT_DOCUMENT",
                  claimed.getId(),
                  claimed.getUploadedBy(),
                  // RR-676 — renew the fixed 15-minute lease between embedding batches (a document
                  // may carry up to 500k tokens across many batches) and cancel the embedding the
                  // moment ownership is lost, so a slow multi-batch call cannot expire its own
                  // lease
                  // and then be unable to publish.
                  (completed, total) ->
                      heartbeatEmbedding(lease)
                          && operationRoot.heartbeat(activeOperation, processingLeaseDuration()),
                  activeOperation.authority());
          if (publishReady(lease, extraction, chunks, embeddings)) {
            operationRoot.complete(activeOperation);
            operation = null;
            log.info(
                "Semantically indexed project document id {} with {} chunks",
                documentId,
                chunks.size());
          } else {
            terminateOperation(
                activeOperation,
                "DOCUMENT_INDEX_LEASE_LOST",
                "Document-index publication lease was lost");
            operation = null;
          }
          return;
        } catch (EmbeddingOwnershipLostException ownershipLost) {
          terminateOperation(
              operation, "DOCUMENT_INDEX_LEASE_LOST", "Document-index lease was lost");
          operation = null;
          // A takeover/delete now owns this document; stop without downgrading or retrying.
          log.info(
              "Project document {} embedding abandoned — lease lost mid-call: {}",
              documentId,
              ownershipLost.getMessage());
          return;
        } catch (EmbeddingUnavailableException policyChanged) {
          terminateOperation(
              operation, "DOCUMENT_EMBEDDING_UNAVAILABLE", "Document embedding became unavailable");
          operation = null;
          log.info(
              "Project document {} changed to lexical indexing because embedding policy is {}",
              documentId,
              policyChanged.reason());
        }
      }
      if (!updateLeasedStatus(lease, ProjectDocument.Status.INDEXING, null, null, false)) return;
      if (publishLexicalReady(lease, extraction, chunks)) {
        log.info(
            "Lexically indexed project document id {} with {} chunks", documentId, chunks.size());
      }
    } catch (DocumentAdmissionException e) {
      terminateOperation(operation, e.code(), "Document indexing was rejected");
      failTerminal(lease, e.code(), e.getMessage());
    } catch (Exception e) {
      if (operation != null) {
        terminateOperation(
            operation, "DOCUMENT_INDEX_FAILED", "Document indexing did not complete");
        // Provider transport may have succeeded without returning. Do not automatically replay an
        // uncertain embedding operation under a new processing attempt.
        failTerminal(
            lease,
            "EMBEDDING_OUTCOME_UNCERTAIN",
            "Embedding outcome is uncertain; re-upload or explicitly rebuild the document.");
      } else {
        scheduleRetryOrFail(lease);
      }
      log.warn("Project document indexing failed for id {}", documentId);
    }
  }

  public List<ProjectDocument> list(String projectId) {
    return repo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId);
  }

  public EmbeddingPolicyResolver.Availability embeddingAvailability(String projectId) {
    return embedder.availability(projectId);
  }

  public void delete(String projectId, String documentId) {
    final String[] storedPath = new String[1];
    AtomicBoolean deleted = new AtomicBoolean(false);
    AtomicReference<EmbeddingOperationRoot.Request> operationCancellation = new AtomicReference<>();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              ProjectDocument doc = repo.findByIdForUpdate(documentId).orElse(null);
              if (doc == null || !projectId.equals(doc.getProjectId())) return;
              storedPath[0] = doc.getPathRelative();
              if (doc.getStatus() == ProjectDocument.Status.EMBEDDING) {
                operationCancellation.set(documentOperation(doc));
              }
              doc.setCancellationRequested(true);
              doc.setStatus(ProjectDocument.Status.CANCELLED);
              doc.setDeletedAt(OffsetDateTime.now());
              doc.setProcessingCompletedAt(OffsetDateTime.now());
              doc.setUpdatedAt(OffsetDateTime.now());
              doc.setErrorCode(null);
              doc.setFailureReason(null);
              invalidateLease(doc);
              repo.save(doc);
              // Soft-delete keeps the document tombstone for audit/recovery fencing, so the
              // database FK cannot purge the separately stored source bytes for us.
              blobRepo.deleteById(documentId);
              deleted.set(true);
            });
    if (!deleted.get()) return;
    if (operationCancellation.get() != null) {
      operationRoot.requestCancellation(operationCancellation.get());
    }
    chunkStore.deleteByDocument(documentId);
    if (storedPath[0] != null) {
      try {
        Files.deleteIfExists(workspace.resolve(projectId, storedPath[0]));
      } catch (IOException e) {
        log.warn("Project document workspace cleanup failed for id {}", documentId);
      }
    }
  }

  record ProcessingLease(ProjectDocument document, String owner, long token) {}

  ProcessingLease claim(String documentId) {
    return claim(documentId, false);
  }

  /**
   * @param semanticReindex when true an already-published LEXICAL_READY document may be re-claimed
   *     so a route that has since recovered can upgrade it; lexical degradation is otherwise
   *     one-way and irreversible without delete-and-reupload.
   */
  ProcessingLease claim(String documentId, boolean semanticReindex) {
    String owner = UUID.randomUUID().toString();
    final String[] exhaustedPath = new String[1];
    ProcessingLease lease =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  ProjectDocument doc = repo.findByIdForUpdate(documentId).orElse(null);
                  if (doc == null
                      || doc.getDeletedAt() != null
                      || doc.getStatus() == ProjectDocument.Status.FAILED
                      || doc.getStatus() == ProjectDocument.Status.CANCELLED) return null;
                  boolean lexicalReindex =
                      semanticReindex && doc.getStatus() == ProjectDocument.Status.LEXICAL_READY;
                  if (!lexicalReindex
                      && (doc.getStatus() == ProjectDocument.Status.READY
                          || doc.getStatus() == ProjectDocument.Status.LEXICAL_READY)) return null;
                  return claimLocked(doc, owner, lexicalReindex, exhaustedPath);
                });
    if (exhaustedPath[0] != null) {
      deleteWorkspaceCopy(projectIdOf(documentId), exhaustedPath[0], documentId);
    }
    return lease;
  }

  private ProcessingLease claimLocked(
      ProjectDocument doc, String owner, boolean lexicalReindex, String[] exhaustedPath) {
    if (doc.isCancellationRequested()) {
      doc.setStatus(ProjectDocument.Status.CANCELLED);
      doc.setProcessingCompletedAt(OffsetDateTime.now());
      doc.setUpdatedAt(OffsetDateTime.now());
      invalidateLease(doc);
      repo.save(doc);
      return null;
    }
    OffsetDateTime now = OffsetDateTime.now();
    if ((doc.getStatus() == ProjectDocument.Status.EXTRACTING
            || doc.getStatus() == ProjectDocument.Status.EMBEDDING
            || doc.getStatus() == ProjectDocument.Status.INDEXING)
        && doc.getProcessingLeaseOwner() != null
        && doc.getProcessingLeaseExpiresAt() != null
        && doc.getProcessingLeaseExpiresAt().isAfter(now)) return null;
    int attempts = doc.getProcessingAttempt() == null ? 0 : doc.getProcessingAttempt();
    if (attempts >= properties.getMaxProcessingAttempts()) {
      // A published lexical index is usable evidence; an exhausted upgrade attempt must leave it
      // alone rather than downgrade it to FAILED.
      if (lexicalReindex) return null;
      doc.setStatus(ProjectDocument.Status.FAILED);
      doc.setErrorCode("RETRY_EXHAUSTED");
      doc.setFailureReason("Indexing attempts exhausted.");
      doc.setProcessingCompletedAt(OffsetDateTime.now());
      doc.setUpdatedAt(OffsetDateTime.now());
      invalidateLease(doc);
      repo.save(doc);
      // Terminal: the stored source bytes can never be reprocessed from here.
      blobRepo.deleteById(doc.getId());
      exhaustedPath[0] = doc.getPathRelative();
      return null;
    }
    long token = doc.getProcessingLeaseToken() == null ? 0L : doc.getProcessingLeaseToken();
    doc.setProcessingAttempt(attempts + 1);
    doc.setStatus(ProjectDocument.Status.EXTRACTING);
    doc.setErrorCode(null);
    doc.setFailureReason(null);
    doc.setProcessingStartedAt(now);
    doc.setUpdatedAt(now);
    doc.setProcessingLeaseOwner(owner);
    doc.setProcessingLeaseToken(token + 1);
    doc.setProcessingLeaseExpiresAt(leaseExpiry(now));
    ProjectDocument saved = repo.save(doc);
    return new ProcessingLease(saved, owner, token + 1);
  }

  private String projectIdOf(String documentId) {
    return repo.findById(documentId).map(ProjectDocument::getProjectId).orElse(null);
  }

  private boolean heartbeat(ProcessingLease lease) {
    return updateLeasedStatus(lease, ProjectDocument.Status.EXTRACTING, null, null, false);
  }

  /**
   * RR-676 — lease renewal during embedding. Keeps the status at EMBEDDING (unlike {@link
   * #heartbeat}, which reasserts EXTRACTING) while extending the lease expiry, and returns whether
   * we still own a live lease so the embedder can stop on takeover/delete.
   */
  private boolean heartbeatEmbedding(ProcessingLease lease) {
    return updateLeasedStatus(lease, ProjectDocument.Status.EMBEDDING, null, null, false);
  }

  boolean publishReady(
      ProcessingLease lease,
      ExtractionResult extraction,
      List<ChunkingService.Chunk> chunks,
      List<float[]> embeddings) {
    return publish(lease, extraction, chunks, embeddings, "SEMANTIC");
  }

  boolean publishLexicalReady(
      ProcessingLease lease, ExtractionResult extraction, List<ChunkingService.Chunk> chunks) {
    return publish(lease, extraction, chunks, null, "LEXICAL");
  }

  private boolean publish(
      ProcessingLease lease,
      ExtractionResult extraction,
      List<ChunkingService.Chunk> chunks,
      List<float[]> embeddings,
      String retrievalMode) {
    Boolean published =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  ProjectDocument doc =
                      repo.findByIdForUpdate(lease.document().getId()).orElse(null);
                  if (!ownsLiveLease(doc, lease)) return false;
                  // Chunk mutation and READY publication share the same row lock + transaction. A
                  // takeover cannot increment the fence until this transaction commits; an expired
                  // or
                  // invalidated stale owner cannot enter it.
                  chunkStore.deleteByDocument(doc.getId());
                  if ("SEMANTIC".equals(retrievalMode)) {
                    chunkStore.insertAll(doc.getId(), chunks, embeddings);
                    doc.setStatus(ProjectDocument.Status.READY);
                    doc.setEmbeddingProvider(EmbeddingService.PROVIDER);
                    doc.setEmbeddingModel(embedder.model());
                  } else {
                    chunkStore.insertLexicalAll(doc.getId(), chunks);
                    doc.setStatus(ProjectDocument.Status.LEXICAL_READY);
                    doc.setEmbeddingProvider(null);
                    doc.setEmbeddingModel(null);
                  }
                  doc.setRetrievalMode(retrievalMode);
                  doc.setPageCount(extraction.pageCount());
                  doc.setExtractedChars(extraction.characterCount());
                  doc.setEstimatedTokens(extraction.tokenCount());
                  doc.setErrorCode(null);
                  doc.setFailureReason(null);
                  doc.setProcessingCompletedAt(OffsetDateTime.now());
                  doc.setUpdatedAt(OffsetDateTime.now());
                  clearLease(doc);
                  repo.save(doc);
                  return true;
                });
    return Boolean.TRUE.equals(published);
  }

  private boolean updateLeasedStatus(
      ProcessingLease lease,
      ProjectDocument.Status statusValue,
      String errorCode,
      String reason,
      boolean releaseLease) {
    boolean terminalFailure = statusValue == ProjectDocument.Status.FAILED;
    final String[] storedPath = new String[1];
    Boolean updated =
        new TransactionTemplate(transactionManager)
            .execute(
                tx -> {
                  ProjectDocument doc =
                      repo.findByIdForUpdate(lease.document().getId()).orElse(null);
                  if (!ownsLiveLease(doc, lease)) return false;
                  doc.setStatus(statusValue);
                  doc.setErrorCode(errorCode);
                  doc.setFailureReason(reason);
                  if (releaseLease && terminalFailure) {
                    doc.setProcessingCompletedAt(OffsetDateTime.now());
                  }
                  doc.setUpdatedAt(OffsetDateTime.now());
                  if (releaseLease) clearLease(doc);
                  else doc.setProcessingLeaseExpiresAt(leaseExpiry(OffsetDateTime.now()));
                  repo.save(doc);
                  if (terminalFailure) {
                    // A terminally failed document is never reprocessed, so retaining the raw
                    // source bytes has no recovery value — and a document rejected for containing
                    // credentials would otherwise keep that material in the primary database.
                    storedPath[0] = doc.getPathRelative();
                    blobRepo.deleteById(doc.getId());
                  }
                  return true;
                });
    boolean applied = Boolean.TRUE.equals(updated);
    if (applied && terminalFailure) {
      deleteWorkspaceCopy(lease.document().getProjectId(), storedPath[0], lease.document().getId());
    }
    return applied;
  }

  private void failTerminal(ProcessingLease lease, String code, String clientSafeReason) {
    updateLeasedStatus(lease, ProjectDocument.Status.FAILED, code, clientSafeReason, true);
  }

  private void deleteWorkspaceCopy(String projectId, String relativePath, String documentId) {
    if (projectId == null || relativePath == null) return;
    try {
      Files.deleteIfExists(workspace.resolve(projectId, relativePath));
    } catch (IOException | RuntimeException e) {
      log.warn("Project document workspace cleanup failed for id {}", documentId);
    }
  }

  private void scheduleRetryOrFail(ProcessingLease lease) {
    ProjectDocument doc = lease.document();
    int attempts = doc.getProcessingAttempt() == null ? 0 : doc.getProcessingAttempt();
    if (attempts >= properties.getMaxProcessingAttempts()) {
      failTerminal(lease, "RETRY_EXHAUSTED", "Indexing attempts exhausted.");
      return;
    }
    updateLeasedStatus(
        lease,
        ProjectDocument.Status.RETRY_PENDING,
        "INDEXING_RETRY_PENDING",
        "Indexing will be retried.",
        true);
  }

  private boolean ownsLiveLease(ProjectDocument doc, ProcessingLease lease) {
    return doc != null
        && doc.getDeletedAt() == null
        && !doc.isCancellationRequested()
        && lease.owner().equals(doc.getProcessingLeaseOwner())
        && doc.getProcessingLeaseToken() != null
        && doc.getProcessingLeaseToken() == lease.token()
        && doc.getProcessingLeaseExpiresAt() != null
        && doc.getProcessingLeaseExpiresAt().isAfter(OffsetDateTime.now());
  }

  private OffsetDateTime leaseExpiry(OffsetDateTime from) {
    return from.plusNanos(properties.getStaleProcessingMillis() * 1_000_000);
  }

  private Duration processingLeaseDuration() {
    return Duration.ofMillis(properties.getStaleProcessingMillis());
  }

  private static EmbeddingOperationRoot.Request documentOperation(ProjectDocument document) {
    String attemptIdentity =
        EmbeddingOperationIdentity.document(
            document.getId(), document.getSha256(), document.getProcessingAttempt());
    return new EmbeddingOperationRoot.Request(
        document.getProjectId(),
        "ProjectDocumentEmbedding",
        "PROJECT_DOCUMENT",
        attemptIdentity,
        document.getUploadedBy(),
        "project-document-index:" + document.getId() + ":" + document.getProcessingAttempt(),
        "PROJECT_DOCUMENT_INDEX",
        document.getProjectId()
            + "|"
            + document.getId()
            + "|"
            + document.getSha256()
            + "|"
            + document.getProcessingAttempt());
  }

  private void terminateOperation(
      EmbeddingOperationRoot.Active operation, String code, String message) {
    if (operation == null) return;
    try {
      operationRoot.terminate(operation, code, message);
    } catch (RuntimeException terminalFailure) {
      log.warn(
          "document-index logical-operation cleanup failed document={} type={}",
          operation.reference().contextId(),
          terminalFailure.getClass().getSimpleName());
    }
  }

  private static void clearLease(ProjectDocument doc) {
    doc.setProcessingLeaseOwner(null);
    doc.setProcessingLeaseExpiresAt(null);
  }

  private static void invalidateLease(ProjectDocument doc) {
    long current = doc.getProcessingLeaseToken() == null ? 0L : doc.getProcessingLeaseToken();
    doc.setProcessingLeaseToken(current + 1);
    clearLease(doc);
  }

  private byte[] readBounded(MultipartFile file) throws IOException {
    if (file == null || file.isEmpty()) {
      throw new DocumentAdmissionException("EMPTY_FILE", "The uploaded file is empty.");
    }
    long max = properties.getMaxFileBytes();
    if (file.getSize() > max) {
      throw new DocumentAdmissionException("FILE_TOO_LARGE", "The file exceeds the upload limit.");
    }
    try (InputStream input = file.getInputStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[IO_BUFFER];
      long total = 0;
      int read;
      while ((read = input.read(buffer)) != -1) {
        total += read;
        if (total > max) {
          throw new DocumentAdmissionException(
              "FILE_TOO_LARGE", "The file exceeds the upload limit.");
        }
        output.write(buffer, 0, read);
      }
      return output.toByteArray();
    }
  }

  private void enforceMalwarePolicy(Project project, byte[] bytes) {
    Org org = orgRepo.findById(project.getOrgId()).orElse(null);
    boolean enterprise = org != null && "ENTERPRISE".equalsIgnoreCase(org.getTier());
    if (!enterprise && !properties.isMalwareScanRequired()) return;
    ScanResult result;
    try {
      result = malwareScanner.scan(bytes);
    } catch (RuntimeException e) {
      result = new ScanResult(ScanResult.Verdict.ERROR, null);
    }
    if (result == null
        || result.verdict() == ScanResult.Verdict.UNAVAILABLE
        || result.verdict() == ScanResult.Verdict.ERROR) {
      throw new DocumentAdmissionException(
          "MALWARE_SCANNER_UNAVAILABLE",
          "The security scanner is unavailable; the upload was not stored.",
          HttpStatus.SERVICE_UNAVAILABLE);
    }
    if (result.verdict() == ScanResult.Verdict.MALICIOUS) {
      throw new DocumentAdmissionException(
          "MALWARE_DETECTED", "The upload was rejected by the security scanner.");
    }
  }

  private ExtractionResult preflightExtraction(byte[] bytes, String mimeType) throws IOException {
    Path temp = Files.createTempFile("project-document-admission-", ".bin");
    try {
      Files.write(temp, bytes);
      ExtractionResult result =
          extractor.extractBounded(temp, mimeType, properties.getMaxExtractedCharsPerFile());
      if (result.text() == null || result.text().isBlank()) {
        throw new DocumentAdmissionException(
            "NO_EXTRACTABLE_TEXT", "No readable text was found in the document.");
      }
      return result;
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  private Path persistOpaqueWorkspaceCopy(ProjectDocument document, byte[] bytes)
      throws IOException {
    workspace.initIfMissing(document.getProjectId());
    Path target = workspace.resolve(document.getProjectId(), document.getPathRelative());
    Files.createDirectories(target.getParent());
    Path temp = Files.createTempFile(target.getParent(), document.getId() + "-", ".tmp");
    try (InputStream input = new ByteArrayInputStream(bytes)) {
      Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
      try {
        Files.move(
            temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temp);
    }
    return target;
  }

  private void enforcePerFileExtractionLimits(ExtractionResult extraction) {
    if (extraction.pageCount() != null
        && extraction.pageCount() > properties.getMaxPagesPerFile()) {
      throw new DocumentAdmissionException("PAGE_LIMIT", "The document exceeds the page limit.");
    }
    if (extraction.characterCount() > properties.getMaxExtractedCharsPerFile()) {
      throw new DocumentAdmissionException(
          "EXTRACTED_TEXT_LIMIT", "The document text exceeds the extraction limit.");
    }
    if (extraction.tokenCount() > properties.getMaxTokensPerFile()) {
      throw new DocumentAdmissionException("TOKEN_LIMIT", "The document exceeds the token limit.");
    }
  }

  private void enforceCredentialContentPolicy(ExtractionResult extraction) {
    if (extraction == null) return;
    secretScanner
        .scan(extraction.text())
        .ifPresent(
            finding -> {
              throw new DocumentAdmissionException(
                  "CREDENTIAL_CONTENT_DETECTED",
                  "The document contains credential material and was rejected.",
                  HttpStatus.UNPROCESSABLE_ENTITY);
            });
  }

  private void enforceProjectQuotas(
      String projectId, int incomingBytes, ExtractionResult extraction) {
    if (repo.countByProjectIdAndDeletedAtIsNull(projectId)
        >= properties.getMaxDocumentsPerProject()) {
      throw quota("PROJECT_DOCUMENT_LIMIT", "The project document limit has been reached.");
    }
    if (repo.sumActiveBytes(projectId) + incomingBytes > properties.getMaxProjectBytes()) {
      throw quota("PROJECT_BYTE_LIMIT", "The project document storage limit has been reached.");
    }
    long pages = extraction.pageCount() == null ? 0 : extraction.pageCount();
    if (repo.sumActivePages(projectId) + pages > properties.getMaxProjectPages()) {
      throw quota("PROJECT_PAGE_LIMIT", "The project document page limit has been reached.");
    }
    if (repo.sumActiveExtractedChars(projectId) + extraction.characterCount()
        > properties.getMaxProjectExtractedChars()) {
      throw quota("PROJECT_TEXT_LIMIT", "The project document text limit has been reached.");
    }
    if (repo.sumActiveTokens(projectId) + extraction.tokenCount()
        > properties.getMaxProjectTokens()) {
      throw quota("PROJECT_TOKEN_LIMIT", "The project document token limit has been reached.");
    }
  }

  private static DocumentAdmissionException quota(String code, String message) {
    return new DocumentAdmissionException(code, message, HttpStatus.PAYLOAD_TOO_LARGE);
  }

  static String safeDisplayName(String raw) {
    if (raw == null || raw.isBlank()) return "document";
    String normalized = Normalizer.normalize(raw, Normalizer.Form.NFKC);
    normalized = normalized.replace('\\', '/');
    normalized = normalized.substring(normalized.lastIndexOf('/') + 1);
    normalized =
        normalized
            .replaceAll("[\\p{Cc}\\p{Cf}]", "")
            .replaceAll("[^\\p{L}\\p{N} ._()\\-]", "-")
            .replaceAll("\\s+", " ")
            .replaceAll("-+", "-")
            .trim();
    if (normalized.equals(".") || normalized.equals("..") || normalized.isBlank()) {
      normalized = "document";
    }
    int max = 160;
    if (normalized.codePointCount(0, normalized.length()) > max) {
      normalized = normalized.substring(0, normalized.offsetByCodePoints(0, max)).stripTrailing();
    }
    return normalized;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private void audit(
      Project project, String userId, String entityId, String eventType, String outcomeCode) {
    try {
      auditService.record(
          ActivityAudit.builder()
              .eventType(eventType)
              .entityType("PROJECT_DOCUMENT")
              .entityId(entityId)
              .orgId(project == null ? null : project.getOrgId())
              .actorId(userId)
              .metadata("{\"outcome\":\"" + outcomeCode.toUpperCase(Locale.ROOT) + "\"}")
              .build());
    } catch (RuntimeException e) {
      log.warn("Project document audit write failed for event {}", eventType);
    }
  }
}
