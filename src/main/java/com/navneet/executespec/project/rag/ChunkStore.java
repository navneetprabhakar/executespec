package com.navneet.executespec.project.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * JDBC bridge for {@code project_document_chunk}. Hibernate has no first-class support for the
 * {@code vector} column, so chunk inserts + nearest-neighbour queries go through plain SQL.
 */
@Component
@RequiredArgsConstructor
public class ChunkStore {

  public record RetrievedChunk(
      String documentId,
      String filename,
      int chunkIndex,
      String content,
      int tokenCount,
      double distance) {}

  private final JdbcTemplate jdbc;

  public void insertAll(
      String documentId, List<ChunkingService.Chunk> chunks, List<float[]> embeddings) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException("chunks/embeddings size mismatch");
    }
    for (int i = 0; i < chunks.size(); i++) {
      ChunkingService.Chunk c = chunks.get(i);
      jdbc.update(
          "INSERT INTO project_document_chunk(id, document_id, chunk_index, content, token_count,"
              + " embedding) VALUES (?, ?, ?, ?, ?, ?::vector)",
          UUID.randomUUID().toString(),
          documentId,
          c.index(),
          c.content(),
          c.tokenCount(),
          toVectorLiteral(embeddings.get(i)));
    }
  }

  /** Stores durable text chunks without fabricating vectors for a lexical-only provider route. */
  public void insertLexicalAll(String documentId, List<ChunkingService.Chunk> chunks) {
    for (ChunkingService.Chunk chunk : chunks) {
      jdbc.update(
          "INSERT INTO project_document_chunk(id, document_id, chunk_index, content, token_count,"
              + " embedding) VALUES (?, ?, ?, ?, ?, NULL)",
          UUID.randomUUID().toString(),
          documentId,
          chunk.index(),
          chunk.content(),
          chunk.tokenCount());
    }
  }

  public List<RetrievedChunk> nearest(String projectId, float[] queryEmbedding, int k) {
    return jdbc.query(
        "SELECT dc.document_id, COALESCE(d.filename, d.path_relative) AS filename, "
            + "dc.chunk_index, dc.content, dc.token_count, "
            + "dc.embedding <=> ?::vector AS distance "
            + "FROM project_document_chunk dc "
            + "JOIN project_document d ON d.id = dc.document_id "
            + "WHERE d.project_id = ? AND d.status = 'READY' AND d.retrieval_mode = 'SEMANTIC' "
            + "AND dc.embedding IS NOT NULL AND d.deleted_at IS NULL "
            + "ORDER BY distance ASC LIMIT ?",
        (rs, rowNum) ->
            new RetrievedChunk(
                rs.getString("document_id"),
                rs.getString("filename"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getInt("token_count"),
                rs.getDouble("distance")),
        toVectorLiteral(queryEmbedding),
        projectId,
        k);
  }

  /** Durable PostgreSQL full-text fallback across both semantic and lexical-ready documents. */
  public List<RetrievedChunk> lexical(String projectId, String query, int k) {
    if (query == null || query.isBlank()) return List.of();
    return jdbc.query(
        "SELECT dc.document_id, COALESCE(d.filename, d.path_relative) AS filename, "
            + "dc.chunk_index, dc.content, dc.token_count, "
            + "ts_rank_cd(to_tsvector('simple', dc.content), plainto_tsquery('simple', ?)) AS rank "
            + "FROM project_document_chunk dc JOIN project_document d ON d.id=dc.document_id "
            + "WHERE d.project_id=? AND d.status IN ('READY','LEXICAL_READY') "
            + "AND d.deleted_at IS NULL "
            + "AND to_tsvector('simple', dc.content) @@ plainto_tsquery('simple', ?) "
            + "ORDER BY rank DESC, d.created_at DESC, dc.chunk_index ASC LIMIT ?",
        (rs, rowNum) ->
            new RetrievedChunk(
                rs.getString("document_id"),
                rs.getString("filename"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getInt("token_count"),
                1.0 - Math.min(1.0, rs.getDouble("rank"))),
        query,
        projectId,
        query,
        Math.max(1, k));
  }

  public void deleteByDocument(String documentId) {
    jdbc.update("DELETE FROM project_document_chunk WHERE document_id = ?", documentId);
  }

  private static String toVectorLiteral(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }

  public List<float[]> emptyVectorList() {
    return new ArrayList<>();
  }
}
