package com.navneet.executespec.project.rag;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.navneet.executespec.project.rag.entity.ProjectDocument;
import com.navneet.executespec.project.rag.repository.ProjectDocumentRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectDocumentService {

  private static final long MAX_BYTES = 10L * 1024 * 1024;

  private final ProjectDocumentRepository repo;
  private final ProjectWorkspaceService workspace;
  private final DocumentExtractor extractor;
  private final ChunkingService chunker;
  private final EmbeddingService embedder;
  private final ChunkStore chunkStore;

  public ProjectDocument upload(String projectId, MultipartFile file, String userId) {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("empty file");
    if (file.getSize() > MAX_BYTES) {
      throw new IllegalArgumentException("file exceeds " + MAX_BYTES + " bytes");
    }
    workspace.initIfMissing(projectId);
    String safeName = sanitise(file.getOriginalFilename());
    String relative = "docs/" + safeName;
    try {
      String content = new String(file.getBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
      workspace.writeAndCommit(projectId, relative, content, "doc upload: " + safeName);
    } catch (IOException e) {
      throw new RuntimeException("upload write failed", e);
    }
    ProjectDocument doc =
        ProjectDocument.builder()
            .id(UUID.randomUUID().toString())
            .projectId(projectId)
            .pathRelative(relative)
            .source("USER_UPLOAD")
            .filename(safeName)
            .mimeType(file.getContentType())
            .sizeBytes(file.getSize())
            .sha256(sha256(file))
            .uploadedBy(userId)
            .status(ProjectDocument.Status.UPLOADED)
            .createdAt(OffsetDateTime.now())
            .updatedAt(OffsetDateTime.now())
            .build();
    repo.save(doc);
    indexAsync(doc.getId());
    return doc;
  }

  @Async
  public void indexAsync(String docId) {
    ProjectDocument doc = repo.findById(docId).orElse(null);
    if (doc == null) return;
    try {
      doc.setStatus(ProjectDocument.Status.EXTRACTING);
      doc.setUpdatedAt(OffsetDateTime.now());
      repo.save(doc);
      Path filePath = workspace.resolve(doc.getProjectId(), doc.getPathRelative());
      String text = extractor.extract(filePath, doc.getMimeType());
      if (text == null || text.isBlank()) {
        fail(doc, "Tika returned no extractable text");
        return;
      }
      // Sibling .md for human inspection (best effort).
      try {
        String mdRel = doc.getPathRelative() + ".md";
        workspace.writeAndCommit(
            doc.getProjectId(), mdRel, text, "doc extract: " + doc.getFilename());
      } catch (Exception ignore) {
      }

      doc.setStatus(ProjectDocument.Status.EMBEDDING);
      doc.setUpdatedAt(OffsetDateTime.now());
      repo.save(doc);

      List<ChunkingService.Chunk> chunks = chunker.chunk(text);
      if (chunks.isEmpty()) {
        fail(doc, "no chunks produced");
        return;
      }
      List<float[]> embeddings =
          embedder.embed(chunks.stream().map(ChunkingService.Chunk::content).toList());
      chunkStore.deleteByDocument(doc.getId());
      chunkStore.insertAll(doc.getId(), chunks, embeddings);

      doc.setStatus(ProjectDocument.Status.READY);
      doc.setUpdatedAt(OffsetDateTime.now());
      repo.save(doc);
      log.info("Indexed doc {} ({} chunks)", doc.getId(), chunks.size());
    } catch (Exception e) {
      log.warn("Indexing failed for doc {}: {}", docId, e.getMessage());
      fail(doc, e.getMessage());
    }
  }

  public List<ProjectDocument> list(String projectId) {
    return repo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(projectId);
  }

  public void delete(String projectId, String documentId) {
    ProjectDocument doc = repo.findById(documentId).orElse(null);
    if (doc == null || !projectId.equals(doc.getProjectId())) return;
    doc.setDeletedAt(OffsetDateTime.now());
    doc.setUpdatedAt(OffsetDateTime.now());
    repo.save(doc);
    chunkStore.deleteByDocument(doc.getId());
  }

  private void fail(ProjectDocument doc, String reason) {
    if (doc == null) return;
    doc.setStatus(ProjectDocument.Status.FAILED);
    doc.setFailureReason(reason);
    doc.setUpdatedAt(OffsetDateTime.now());
    repo.save(doc);
  }

  private static String sanitise(String name) {
    if (name == null || name.isBlank()) return "doc-" + System.currentTimeMillis();
    String base = name.replaceAll("[^A-Za-z0-9._-]", "-");
    if (base.length() > 200) base = base.substring(0, 200);
    return base;
  }

  private static String sha256(MultipartFile file) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(file.getBytes()));
    } catch (Exception e) {
      return null;
    }
  }
}
