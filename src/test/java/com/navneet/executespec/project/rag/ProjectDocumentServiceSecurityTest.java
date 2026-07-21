package com.navneet.executespec.project.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.project.rag.DocumentExtractor.ExtractionResult;
import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.rag.entity.ProjectDocumentBlob;
import com.navneet.executespec.project.rag.repository.ProjectDocumentBlobRepository;
import com.navneet.executespec.project.rag.repository.ProjectDocumentRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.security.GeneratedSecretScanner;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

class ProjectDocumentServiceSecurityTest {

  private final ProjectDocumentRepository repo =
      org.mockito.Mockito.mock(ProjectDocumentRepository.class);
  private final ProjectDocumentBlobRepository blobRepo =
      org.mockito.Mockito.mock(ProjectDocumentBlobRepository.class);
  private final ProjectRepository projectRepo = org.mockito.Mockito.mock(ProjectRepository.class);
  private final OrgRepository orgRepo = org.mockito.Mockito.mock(OrgRepository.class);
  private final ProjectWorkspaceService workspace =
      org.mockito.Mockito.mock(ProjectWorkspaceService.class);
  private final DocumentExtractor extractor = org.mockito.Mockito.mock(DocumentExtractor.class);
  private final DocumentMalwareScanner scanner = new DeterministicDocumentMalwareScanner();
  private final GeneratedSecretScanner secretScanner = new GeneratedSecretScanner();
  private final ChunkingService chunker = new ChunkingService();
  private final EmbeddingService embedder = org.mockito.Mockito.mock(EmbeddingService.class);
  private final ChunkStore chunkStore = org.mockito.Mockito.mock(ChunkStore.class);
  private final ApplicationEventPublisher events =
      org.mockito.Mockito.mock(ApplicationEventPublisher.class);
  private final ActivityAuditService audit = org.mockito.Mockito.mock(ActivityAuditService.class);
  private final ProjectDocumentProperties properties = new ProjectDocumentProperties();
  private final AtomicReference<ProjectDocument> saved = new AtomicReference<>();
  private ProjectDocumentService service;

  @BeforeEach
  void setUp() {
    Project project = Project.builder().id("p1").orgId("o1").name("P").createdBy("u1").build();
    when(projectRepo.findById("p1")).thenReturn(Optional.of(project));
    when(projectRepo.findByIdForQuotaUpdate("p1")).thenReturn(Optional.of(project));
    when(orgRepo.findById("o1"))
        .thenReturn(Optional.of(Org.builder().id("o1").name("O").tier("ENTERPRISE").build()));
    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("bounded project notes", 1, 21, 6));
    when(repo.save(any()))
        .thenAnswer(
            invocation -> {
              ProjectDocument value = invocation.getArgument(0);
              saved.set(value);
              return value;
            });
    when(blobRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(embedder.availability("p1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.SEMANTIC,
                true,
                true,
                "openai",
                EmbeddingService.DEFAULT_MODEL,
                "AVAILABLE"));

    service =
        new ProjectDocumentService(
            repo,
            blobRepo,
            projectRepo,
            orgRepo,
            workspace,
            extractor,
            new DocumentTypeInspector(),
            scanner,
            secretScanner,
            properties,
            chunker,
            embedder,
            chunkStore,
            new NoOpTransactionManager(),
            events,
            audit);
  }

  @Test
  void storesValidatedBytesInDbUnderOpaqueIdentityAndAllowsDuplicateDisplayNames() {
    var first = service.upload("p1", textFile("../same.txt", "alpha"), "u1");
    var second = service.upload("p1", textFile("../same.txt", "beta"), "u1");

    assertThat(first.getFilename()).isEqualTo("same.txt");
    assertThat(second.getFilename()).isEqualTo("same.txt");
    assertThat(first.getPathRelative()).startsWith("docs/.uploads/").endsWith(".bin");
    assertThat(second.getPathRelative()).isNotEqualTo(first.getPathRelative());
    assertThat(first.getPathRelative()).doesNotContain("same.txt").doesNotContain("..");

    ArgumentCaptor<ProjectDocumentBlob> blobs = ArgumentCaptor.forClass(ProjectDocumentBlob.class);
    verify(blobRepo, org.mockito.Mockito.times(2)).save(blobs.capture());
    assertThat(blobs.getAllValues().get(0).getContentBytes())
        .containsExactly("alpha".getBytes(StandardCharsets.UTF_8));
    verify(events, org.mockito.Mockito.times(2))
        .publishEvent(any(ProjectDocumentProcessingEvent.class));
  }

  @Test
  void normalisesUnicodeTraversalAndControlCharactersForDisplayOnly() {
    String normalized = ProjectDocumentService.safeDisplayName("../../ｅｖｉｌ\u202E.pdf");
    assertThat(normalized).isEqualTo("evil.pdf").doesNotContain("/").doesNotContain("\\");
  }

  @Test
  void rejectsEicarBeforePersistence() {
    var eicar =
        textFile(
            "notes.txt", "X5O!P%@AP[4\\PZX54(P^)7CC)7}$EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*");

    assertThatThrownBy(() -> service.upload("p1", eicar, "u1"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(e -> ((DocumentAdmissionException) e).code())
        .isEqualTo("MALWARE_DETECTED");
    verify(repo, never()).save(any());
    verify(blobRepo, never()).save(any());
  }

  @Test
  void rejectsExtractedCredentialContentBeforeBlobPersistenceOrProcessingDispatch() {
    String credential = "sk-ant-abcdefghijklmnopqrstuvwxyz1234567890";
    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("deployment key: " + credential, 1, 56, 15));

    assertThatThrownBy(() -> service.upload("p1", textFile("architecture.md", credential), "u1"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(e -> ((DocumentAdmissionException) e).code())
        .isEqualTo("CREDENTIAL_CONTENT_DETECTED");
    verify(repo, never()).save(any());
    verify(blobRepo, never()).save(any());
    verify(events, never()).publishEvent(any(ProjectDocumentProcessingEvent.class));
  }

  @Test
  void failsClosedWhenEnterpriseScannerUnavailable() {
    DocumentMalwareScanner unavailable =
        bytes ->
            new DocumentMalwareScanner.ScanResult(
                DocumentMalwareScanner.ScanResult.Verdict.UNAVAILABLE, null);
    service =
        new ProjectDocumentService(
            repo,
            blobRepo,
            projectRepo,
            orgRepo,
            workspace,
            extractor,
            new DocumentTypeInspector(),
            unavailable,
            secretScanner,
            properties,
            chunker,
            embedder,
            chunkStore,
            new NoOpTransactionManager(),
            events,
            audit);

    assertThatThrownBy(() -> service.upload("p1", textFile("a.txt", "hello"), "u1"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(e -> ((DocumentAdmissionException) e).code())
        .isEqualTo("MALWARE_SCANNER_UNAVAILABLE");
  }

  @Test
  void rejectsFileAndProjectQuotasWithoutStoringBytes() {
    properties.setMaxFileBytes(4);
    assertCode(textFile("a.txt", "12345"), "FILE_TOO_LARGE");

    properties.setMaxFileBytes(100);
    properties.setMaxDocumentsPerProject(1);
    when(repo.countByProjectIdAndDeletedAtIsNull("p1")).thenReturn(1L);
    assertCode(textFile("a.txt", "hello"), "PROJECT_DOCUMENT_LIMIT");
    verify(blobRepo, never()).save(any());
  }

  @Test
  void enforcesPageTokenAndAggregateTextQuotas() {
    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("large", 501, 5, 2));
    assertCode(textFile("a.txt", "hello"), "PAGE_LIMIT");

    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("large", 1, 5, 501_000));
    assertCode(textFile("a.txt", "hello"), "TOKEN_LIMIT");

    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("large", 1, 20, 5));
    properties.setMaxProjectExtractedChars(25);
    when(repo.sumActiveExtractedChars("p1")).thenReturn(10L);
    assertCode(textFile("a.txt", "hello"), "PROJECT_TEXT_LIMIT");
  }

  @Test
  void enforcesEveryAggregateProjectQuota() {
    properties.setMaxProjectBytes(10);
    when(repo.sumActiveBytes("p1")).thenReturn(8L);
    assertCode(textFile("a.txt", "hello"), "PROJECT_BYTE_LIMIT");

    properties.setMaxProjectBytes(100);
    when(repo.sumActiveBytes("p1")).thenReturn(0L);
    properties.setMaxProjectPages(5);
    when(repo.sumActivePages("p1")).thenReturn(5L);
    assertCode(textFile("a.txt", "hello"), "PROJECT_PAGE_LIMIT");

    properties.setMaxProjectPages(100);
    when(repo.sumActivePages("p1")).thenReturn(0L);
    properties.setMaxProjectTokens(10);
    when(repo.sumActiveTokens("p1")).thenReturn(8L);
    assertCode(textFile("a.txt", "hello"), "PROJECT_TOKEN_LIMIT");
  }

  @Test
  void preservesTypedEncryptedAndCorruptExtractionErrors() {
    org.mockito.Mockito.doThrow(
            new DocumentAdmissionException(
                "ENCRYPTED_DOCUMENT", "Encrypted documents are not supported."))
        .when(extractor)
        .extractBounded(any(), any(), anyInt());
    assertCode(textFile("secret.pdf", "not trusted by extension"), "ENCRYPTED_DOCUMENT");

    org.mockito.Mockito.doThrow(
            new DocumentAdmissionException(
                "CORRUPT_DOCUMENT", "The document is corrupt or unsupported."))
        .when(extractor)
        .extractBounded(any(), any(), anyInt());
    assertCode(textFile("broken.docx", "not trusted by extension"), "CORRUPT_DOCUMENT");
  }

  @Test
  void deleteIsCrossProjectIsolated() {
    ProjectDocument other =
        ProjectDocument.builder()
            .id("d1")
            .projectId("other")
            .pathRelative("docs/.uploads/d1.bin")
            .status(ProjectDocument.Status.READY)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d1")).thenReturn(Optional.of(other));

    service.delete("p1", "d1");

    verify(repo, never()).save(other);
    verify(blobRepo, never()).deleteById("d1");
    verify(chunkStore, never()).deleteByDocument("d1");
  }

  @Test
  void deletePurgesRawSourceBytesAndDerivedChunksWhileKeepingTheTombstone(@TempDir Path tempDir) {
    ProjectDocument document =
        ProjectDocument.builder()
            .id("d1")
            .projectId("p1")
            .pathRelative("docs/.uploads/d1.bin")
            .status(ProjectDocument.Status.READY)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d1")).thenReturn(Optional.of(document));
    when(workspace.resolve("p1", "docs/.uploads/d1.bin")).thenReturn(tempDir.resolve("d1.bin"));

    service.delete("p1", "d1");

    verify(blobRepo).deleteById("d1");
    verify(chunkStore).deleteByDocument("d1");
    verify(repo).save(document);
    assertThat(document.getStatus()).isEqualTo(ProjectDocument.Status.CANCELLED);
    assertThat(document.getDeletedAt()).isNotNull();
    assertThat(document.isCancellationRequested()).isTrue();
  }

  @Test
  void restartReplayIsIdempotentForReadyDocument() {
    ProjectDocument ready =
        ProjectDocument.builder()
            .id("d-ready")
            .projectId("p1")
            .status(ProjectDocument.Status.READY)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-ready")).thenReturn(Optional.of(ready));

    service.processDocument("d-ready");

    verify(blobRepo, never()).findById(any());
    verify(embedder, never())
        .embedForProject(any(), any(), any(), any(), any(), nullable(String.class));
  }

  @Test
  void restartRecoveryUsesDbBytesAndThenBecomesIdempotent(@TempDir Path tempDir) {
    ProjectDocument pending =
        ProjectDocument.builder()
            .id("d-recover")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-recover.bin")
            .mimeType("text/plain")
            .uploadedBy("member-1")
            .status(ProjectDocument.Status.UPLOADED)
            .processingAttempt(0)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-recover")).thenReturn(Optional.of(pending));
    when(repo.findById("d-recover")).thenReturn(Optional.of(pending));
    when(blobRepo.findById("d-recover"))
        .thenReturn(
            Optional.of(
                ProjectDocumentBlob.builder()
                    .documentId("d-recover")
                    .contentBytes("recovery notes".getBytes(StandardCharsets.UTF_8))
                    .createdAt(OffsetDateTime.now())
                    .build()));
    when(workspace.resolve("p1", "docs/.uploads/d-recover.bin"))
        .thenReturn(tempDir.resolve("docs/.uploads/d-recover.bin"));
    when(embedder.embedForProject(any(), any(), any(), any(), any(), nullable(String.class)))
        .thenReturn(java.util.List.of(new float[] {0.1f}));

    service.processDocument("d-recover");
    service.processDocument("d-recover");

    assertThat(pending.getStatus()).isEqualTo(ProjectDocument.Status.READY);
    assertThat(pending.getProcessingAttempt()).isEqualTo(1);
    verify(blobRepo, org.mockito.Mockito.times(1)).findById("d-recover");
    verify(embedder, org.mockito.Mockito.times(1))
        .embedForProject(any(), any(), any(), any(), any(), eq("member-1"));
    verify(chunkStore, org.mockito.Mockito.times(1)).insertAll(any(), any(), any());
  }

  @Test
  void legacyStoredCredentialIsRejectedBeforeWorkspaceOrEmbeddingEgress() {
    ProjectDocument pending =
        ProjectDocument.builder()
            .id("d-secret")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-secret.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.UPLOADED)
            .processingAttempt(0)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    String credential = "sk-ant-abcdefghijklmnopqrstuvwxyz1234567890";
    when(repo.findByIdForUpdate("d-secret")).thenReturn(Optional.of(pending));
    when(blobRepo.findById("d-secret"))
        .thenReturn(
            Optional.of(
                ProjectDocumentBlob.builder()
                    .documentId("d-secret")
                    .contentBytes(credential.getBytes(StandardCharsets.UTF_8))
                    .createdAt(OffsetDateTime.now())
                    .build()));
    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("deployment key: " + credential, 1, 56, 15));

    service.processDocument("d-secret");

    assertThat(pending.getStatus()).isEqualTo(ProjectDocument.Status.FAILED);
    assertThat(pending.getErrorCode()).isEqualTo("CREDENTIAL_CONTENT_DETECTED");
    // initIfMissing is the precondition for every workspace write, so never-called still proves no
    // credential-bearing byte reached the workspace. resolve() is no longer a valid proxy for that:
    // RR-1526 makes a terminal failure purge any workspace copy, which must resolve the path.
    verify(workspace, never()).initIfMissing(any());
    // RR-1526: the stored source bytes must not survive a credential rejection on the restart path.
    verify(blobRepo, org.mockito.Mockito.times(1)).deleteById("d-secret");
    verify(embedder, never())
        .embedForProject(any(), any(), any(), any(), any(), nullable(String.class));
    verify(chunkStore, never()).insertAll(any(), any(), any());
    verify(chunkStore, never()).insertLexicalAll(any(), any());
  }

  @Test
  void deepSeekOnlyRecoveryPublishesDurableLexicalChunksWithoutEmbeddingDispatch(
      @TempDir Path tempDir) {
    ProjectDocument pending =
        ProjectDocument.builder()
            .id("d-lexical")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-lexical.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.UPLOADED)
            .processingAttempt(0)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-lexical")).thenReturn(Optional.of(pending));
    when(blobRepo.findById("d-lexical"))
        .thenReturn(
            Optional.of(
                ProjectDocumentBlob.builder()
                    .documentId("d-lexical")
                    .contentBytes("deepseek enterprise notes".getBytes(StandardCharsets.UTF_8))
                    .createdAt(OffsetDateTime.now())
                    .build()));
    when(workspace.resolve("p1", "docs/.uploads/d-lexical.bin"))
        .thenReturn(tempDir.resolve("docs/.uploads/d-lexical.bin"));
    when(embedder.availability("p1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.LEXICAL,
                false,
                true,
                "deepseek",
                null,
                "SELECTED_PROVIDER_HAS_NO_COMPATIBLE_EMBEDDING_MODEL"));

    service.processDocument("d-lexical");

    assertThat(pending.getStatus()).isEqualTo(ProjectDocument.Status.LEXICAL_READY);
    assertThat(pending.getRetrievalMode()).isEqualTo("LEXICAL");
    assertThat(pending.getEmbeddingProvider()).isNull();
    assertThat(pending.getEmbeddingModel()).isNull();
    verify(embedder, never())
        .embedForProject(any(), any(), any(), any(), any(), nullable(String.class));
    verify(chunkStore).insertLexicalAll(eq("d-lexical"), any());
    verify(chunkStore, never()).insertAll(any(), any(), any());
  }

  /** RR-1527: lexical publication is a degradation, not a permanent terminal state. */
  @Test
  void aLexicalDocumentIsUpgradedOnceTheSemanticRouteRecovers(@TempDir Path tempDir) {
    ProjectDocument lexical =
        ProjectDocument.builder()
            .id("d-upgrade")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-upgrade.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.LEXICAL_READY)
            .retrievalMode("LEXICAL")
            .processingAttempt(1)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-upgrade")).thenReturn(Optional.of(lexical));
    when(blobRepo.findById("d-upgrade"))
        .thenReturn(
            Optional.of(
                ProjectDocumentBlob.builder()
                    .documentId("d-upgrade")
                    .contentBytes("recovered route notes".getBytes(StandardCharsets.UTF_8))
                    .createdAt(OffsetDateTime.now())
                    .build()));
    when(workspace.resolve("p1", "docs/.uploads/d-upgrade.bin"))
        .thenReturn(tempDir.resolve("docs/.uploads/d-upgrade.bin"));
    when(extractor.extractBounded(any(), any(), anyInt()))
        .thenReturn(new ExtractionResult("recovered route notes", 1, 21, 6));
    when(embedder.availability("p1"))
        .thenReturn(
            new EmbeddingPolicyResolver.Availability(
                EmbeddingPolicyResolver.Mode.SEMANTIC,
                true,
                true,
                "openai",
                EmbeddingService.DEFAULT_MODEL,
                "GOVERNED_OPENAI_EMBEDDING_AVAILABLE"));
    when(embedder.model()).thenReturn(EmbeddingService.DEFAULT_MODEL);
    when(embedder.embedForProject(any(), any(), any(), any(), any(), nullable(String.class)))
        .thenReturn(java.util.List.of(new float[] {0.2f}));

    // The ordinary worker entrypoint must still treat a published document as terminal.
    service.processDocument("d-upgrade");
    assertThat(lexical.getStatus()).isEqualTo(ProjectDocument.Status.LEXICAL_READY);

    service.reindexForSemantic("d-upgrade");

    assertThat(lexical.getStatus()).isEqualTo(ProjectDocument.Status.READY);
    assertThat(lexical.getRetrievalMode()).isEqualTo("SEMANTIC");
    assertThat(lexical.getEmbeddingModel()).isEqualTo(EmbeddingService.DEFAULT_MODEL);
  }

  /** RR-1527: an exhausted upgrade must leave a usable lexical index alone, not fail it. */
  @Test
  void anExhaustedReindexLeavesTheLexicalDocumentPublished() {
    ProjectDocument lexical =
        ProjectDocument.builder()
            .id("d-exhausted")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-exhausted.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.LEXICAL_READY)
            .retrievalMode("LEXICAL")
            .processingAttempt(properties.getMaxProcessingAttempts())
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-exhausted")).thenReturn(Optional.of(lexical));

    service.reindexForSemantic("d-exhausted");

    assertThat(lexical.getStatus()).isEqualTo(ProjectDocument.Status.LEXICAL_READY);
    verify(blobRepo, never()).deleteById("d-exhausted");
  }

  /** RR-1526: retry-exhausted rows must not keep their raw source bytes. */
  @Test
  void retryExhaustedTerminalFailurePurgesTheStoredSourceBytes() {
    ProjectDocument stuck =
        ProjectDocument.builder()
            .id("d-exhausted-fail")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-exhausted-fail.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.RETRY_PENDING)
            .processingAttempt(properties.getMaxProcessingAttempts())
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-exhausted-fail")).thenReturn(Optional.of(stuck));

    service.processDocument("d-exhausted-fail");

    assertThat(stuck.getStatus()).isEqualTo(ProjectDocument.Status.FAILED);
    assertThat(stuck.getErrorCode()).isEqualTo("RETRY_EXHAUSTED");
    verify(blobRepo).deleteById("d-exhausted-fail");
  }

  @Test
  void expiredOwnerCannotPublishAfterAnotherJvmTakesOver() {
    ProjectDocument pending =
        ProjectDocument.builder()
            .id("d-fenced")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-fenced.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.UPLOADED)
            .processingAttempt(0)
            .processingLeaseToken(0L)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-fenced")).thenReturn(Optional.of(pending));

    ProjectDocumentService.ProcessingLease ownerA = service.claim("d-fenced");
    assertThat(ownerA).isNotNull();
    assertThat(ownerA.token()).isEqualTo(1L);
    pending.setProcessingLeaseExpiresAt(OffsetDateTime.now().minusSeconds(1));

    ProjectDocumentService.ProcessingLease ownerB = service.claim("d-fenced");
    assertThat(ownerB).isNotNull();
    assertThat(ownerB.token()).isEqualTo(2L);
    assertThat(ownerB.owner()).isNotEqualTo(ownerA.owner());

    var extraction = new ExtractionResult("fenced content", 1, 14, 4);
    var chunks = java.util.List.of(new ChunkingService.Chunk(0, "fenced content", 4));
    var embeddings = java.util.List.of(new float[] {0.1f});
    assertThat(service.publishReady(ownerA, extraction, chunks, embeddings)).isFalse();
    verify(chunkStore, never()).insertAll(any(), any(), any());

    assertThat(service.publishReady(ownerB, extraction, chunks, embeddings)).isTrue();
    verify(chunkStore, org.mockito.Mockito.times(1)).insertAll("d-fenced", chunks, embeddings);
    assertThat(pending.getStatus()).isEqualTo(ProjectDocument.Status.READY);
    assertThat(pending.getProcessingLeaseOwner()).isNull();
    assertThat(pending.getProcessingLeaseToken()).isEqualTo(2L);
  }

  @Test
  void retryPendingReleasesLeaseWithoutClaimingTerminalCompletion(@TempDir Path tempDir) {
    ProjectDocument pending =
        ProjectDocument.builder()
            .id("d-retry")
            .projectId("p1")
            .pathRelative("docs/.uploads/d-retry.bin")
            .mimeType("text/plain")
            .status(ProjectDocument.Status.UPLOADED)
            .processingAttempt(0)
            .processingLeaseToken(0L)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    when(repo.findByIdForUpdate("d-retry")).thenReturn(Optional.of(pending));
    when(blobRepo.findById("d-retry"))
        .thenReturn(
            Optional.of(
                ProjectDocumentBlob.builder()
                    .documentId("d-retry")
                    .contentBytes("retry notes".getBytes(StandardCharsets.UTF_8))
                    .createdAt(OffsetDateTime.now())
                    .build()));
    when(workspace.resolve("p1", "docs/.uploads/d-retry.bin"))
        .thenReturn(tempDir.resolve("docs/.uploads/d-retry.bin"));
    when(embedder.embedForProject(any(), any(), any(), any(), any(), nullable(String.class)))
        .thenThrow(new IllegalStateException("synthetic provider outage"));

    service.processDocument("d-retry");

    assertThat(pending.getStatus()).isEqualTo(ProjectDocument.Status.RETRY_PENDING);
    assertThat(pending.getErrorCode()).isEqualTo("INDEXING_RETRY_PENDING");
    assertThat(pending.getProcessingCompletedAt()).isNull();
    assertThat(pending.getProcessingLeaseOwner()).isNull();
    assertThat(pending.getProcessingLeaseExpiresAt()).isNull();
    assertThat(pending.getProcessingLeaseToken()).isEqualTo(1L);
  }

  private void assertCode(MockMultipartFile file, String expected) {
    assertThatThrownBy(() -> service.upload("p1", file, "u1"))
        .isInstanceOf(DocumentAdmissionException.class)
        .extracting(e -> ((DocumentAdmissionException) e).code())
        .isEqualTo(expected);
  }

  private static MockMultipartFile textFile(String name, String content) {
    return new MockMultipartFile(
        "file", name, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8));
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
