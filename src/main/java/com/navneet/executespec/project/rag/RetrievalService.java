package com.navneet.executespec.project.rag;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Governed document retrieval with an explicit, durable lexical fallback. */
@Service
@RequiredArgsConstructor
@Slf4j
public class RetrievalService {

  private static final int DEFAULT_K = 3;
  private static final int DEFAULT_TOKEN_CAP = 1500;
  private static final int CHARS_PER_TOKEN = 4;

  public record RetrievalResult(String content, String mode, String reason) {}

  private final EmbeddingService embeddingService;
  private final ChunkStore chunkStore;

  public String retrieve(String projectId, String query) {
    return retrieve(projectId, query, DEFAULT_K, DEFAULT_TOKEN_CAP);
  }

  public String retrieve(String projectId, String query, int k, int tokenCap) {
    return retrieveWithReadiness(projectId, query, k, tokenCap).content();
  }

  public RetrievalResult retrieveWithReadiness(
      String projectId, String query, int k, int tokenCap) {
    return retrieveWithReadiness(projectId, query, k, tokenCap, null);
  }

  public RetrievalResult retrieveWithReadiness(
      String projectId, String query, int k, int tokenCap, String actorUserId) {
    if (projectId == null || query == null || query.isBlank()) {
      return new RetrievalResult("", "NONE", "QUERY_OR_PROJECT_MISSING");
    }
    var availability = embeddingService.availability(projectId);
    if (availability.semanticSearchAvailable()) {
      try {
        float[] queryVector =
            embeddingService.embedQueryForProject(
                projectId,
                query,
                "ProjectDocumentRetrieval",
                "PROJECT_DOCUMENT_QUERY",
                projectId,
                actorUserId);
        List<ChunkStore.RetrievedChunk> semantic = chunkStore.nearest(projectId, queryVector, k);
        if (!semantic.isEmpty()) {
          return new RetrievalResult(
              render(semantic, tokenCap, "## REFERENCE DOCS"),
              "SEMANTIC",
              "GOVERNED_OPENAI_EMBEDDING");
        }
      } catch (RuntimeException semanticFailure) {
        log.warn(
            "Semantic document retrieval degraded to lexical project={} failureType={}",
            projectId,
            semanticFailure.getClass().getSimpleName());
      }
    }

    List<ChunkStore.RetrievedChunk> lexical = chunkStore.lexical(projectId, query, k);
    return new RetrievalResult(
        render(lexical, tokenCap, "## REFERENCE DOCS (LEXICAL FALLBACK)"),
        "LEXICAL",
        availability.semanticSearchAvailable()
            ? "SEMANTIC_RESULTS_UNAVAILABLE"
            : availability.reason());
  }

  private static String render(List<ChunkStore.RetrievedChunk> hits, int tokenCap, String heading) {
    if (hits == null || hits.isEmpty()) return "";
    StringBuilder out = new StringBuilder(heading).append('\n');
    int charBudget = Math.max(0, tokenCap) * CHARS_PER_TOKEN;
    int written = 0;
    int i = 1;
    for (var hit : hits) {
      String header = "[S" + i++ + "] " + hit.filename() + " (chunk " + hit.chunkIndex() + ")\n";
      String body = hit.content().trim() + "\n\n";
      if (written + header.length() + body.length() > charBudget) {
        int remaining = Math.max(0, charBudget - written - header.length());
        if (remaining < 200) break;
        out.append(header).append(body, 0, Math.min(remaining, body.length()));
        break;
      }
      out.append(header).append(body);
      written += header.length() + body.length();
    }
    return out.toString();
  }
}
