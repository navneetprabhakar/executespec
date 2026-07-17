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
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

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
  private final ProjectDocumentProperties properties;
  private final ChunkingService chunker;
  private final EmbeddingService embedder;
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
    ProcessingLease lease = claim(documentId);
    if (lease == null) return;
    ProjectDocument claimed = lease.document();
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
      Path rawPath = persistOpaqueWorkspaceCopy(claimed, bytes);
      ExtractionResult extraction =
          extractor.extractBounded(
              rawPath, claimed.getMimeType(), properties.getMaxExtractedCharsPerFile());
      enforcePerFileExtractionLimits(extraction);
      List<ChunkingService.Chunk> chunks = chunker.chunk(extraction.text());
      if (chunks.isEmpty()) {
        failTerminal(lease, "NO_EXTRACTABLE_TEXT", "No readable text was found.");
        return;
      }
      var embedding = embedder.availability(claimed.getProjectId());
      if (embedding.semanticSearchAvailable()) {
        if (!updateLeasedStatus(lease, ProjectDocument.Status.EMBEDDING, null, null, false)) return;
        try {
          List<float[]> embeddings =
              embedder.embedForProject(
                  claimed.getProjectId(),
                  chunks.stream().map(ChunkingService.Chunk::content).toList(),
                  "ProjectDocumentEmbedding",
                  "PROJECT_DOCUMENT",
                  claimed.getId(),
                  claimed.getUploadedBy());
          if (publishReady(lease, extraction, chunks, embeddings)) {
            log.info(
                "Semantically indexed project document id {} with {} chunks",
                documentId,
                chunks.size());
          }
          return;
        } catch (EmbeddingUnavailableException policyChanged) {
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
      failTerminal(lease, e.code(), e.getMessage());
    } catch (Exception e) {
      scheduleRetryOrFail(lease);
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
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              ProjectDocument doc = repo.findByIdForUpdate(documentId).orElse(null);
              if (doc == null || !projectId.equals(doc.getProjectId())) return;
              storedPath[0] = doc.getPathRelative();
              doc.setCancellationRequested(true);
              doc.setStatus(ProjectDocument.Status.CANCELLED);
              doc.setDeletedAt(OffsetDateTime.now());
              doc.setProcessingCompletedAt(OffsetDateTime.now());
              doc.setUpdatedAt(OffsetDateTime.now());
              doc.setErrorCode(null);
              doc.setFailureReason(null);
              invalidateLease(doc);
              repo.save(doc);
              deleted.set(true);
            });
    if (!deleted.get()) return;
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
    String owner = UUID.randomUUID().toString();
    return new TransactionTemplate(transactionManager)
        .execute(
            status -> {
              ProjectDocument doc = repo.findByIdForUpdate(documentId).orElse(null);
              if (doc == null
                  || doc.getDeletedAt() != null
                  || doc.getStatus() == ProjectDocument.Status.READY
                  || doc.getStatus() == ProjectDocument.Status.LEXICAL_READY
                  || doc.getStatus() == ProjectDocument.Status.FAILED
                  || doc.getStatus() == ProjectDocument.Status.CANCELLED) return null;
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
                doc.setStatus(ProjectDocument.Status.FAILED);
                doc.setErrorCode("RETRY_EXHAUSTED");
                doc.setFailureReason("Indexing attempts exhausted.");
                doc.setProcessingCompletedAt(OffsetDateTime.now());
                doc.setUpdatedAt(OffsetDateTime.now());
                invalidateLease(doc);
                repo.save(doc);
                return null;
              }
              long token =
                  doc.getProcessingLeaseToken() == null ? 0L : doc.getProcessingLeaseToken();
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
            });
  }

  private boolean heartbeat(ProcessingLease lease) {
    return updateLeasedStatus(lease, ProjectDocument.Status.EXTRACTING, null, null, false);
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
                  if (releaseLease && statusValue == ProjectDocument.Status.FAILED) {
                    doc.setProcessingCompletedAt(OffsetDateTime.now());
                  }
                  doc.setUpdatedAt(OffsetDateTime.now());
                  if (releaseLease) clearLease(doc);
                  else doc.setProcessingLeaseExpiresAt(leaseExpiry(OffsetDateTime.now()));
                  repo.save(doc);
                  return true;
                });
    return Boolean.TRUE.equals(updated);
  }

  private void failTerminal(ProcessingLease lease, String code, String clientSafeReason) {
    updateLeasedStatus(lease, ProjectDocument.Status.FAILED, code, clientSafeReason, true);
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
