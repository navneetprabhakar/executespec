package com.navneet.executespec.project.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * JDBC bridge for {@code project_code_chunk} (V223) — the SOURCE-code corpus for project-chat
 * semantic search ({@code search_code}). Separate from {@link ChunkStore} (uploaded-doc corpus) so
 * the two never pollute each other's nearest-neighbour results.
 *
 * <p>Inserts + cosine-distance queries go through plain SQL because Hibernate has no first-class
 * support for the pgvector {@code vector} column. Per-file SHA is tracked so {@code
 * CodeIndexingService} can reindex incrementally — only files whose content changed get
 * re-embedded.
 */
@Component
@RequiredArgsConstructor
public class CodeChunkStore {

  public record RetrievedCodeChunk(
      String repoId,
      String repoFullName,
      String filePath,
      int chunkIndex,
      String content,
      int tokenCount,
      double distance) {}

  private final JdbcTemplate jdbc;

  /** Insert all chunks for ONE source file at a given content SHA. */
  public void insertFileChunks(
      String projectId,
      String repoId,
      String repoFullName,
      String filePath,
      String fileSha,
      List<ChunkingService.Chunk> chunks,
      List<float[]> embeddings) {
    if (chunks.size() != embeddings.size()) {
      throw new IllegalArgumentException("chunks/embeddings size mismatch");
    }
    for (int i = 0; i < chunks.size(); i++) {
      ChunkingService.Chunk c = chunks.get(i);
      jdbc.update(
          "INSERT INTO project_code_chunk(id, project_id, repo_id, repo_full_name, file_path,"
              + " file_sha, chunk_index, content, token_count, embedding)"
              + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::vector)",
          UUID.randomUUID().toString(),
          projectId,
          repoId,
          repoFullName,
          filePath,
          fileSha,
          c.index(),
          c.content(),
          c.tokenCount(),
          toVectorLiteral(embeddings.get(i)));
    }
  }

  /** Nearest code chunks across the project, optionally narrowed to one repo. */
  public List<RetrievedCodeChunk> nearest(String projectId, String repoId, float[] query, int k) {
    StringBuilder sql =
        new StringBuilder(
            "SELECT repo_id, repo_full_name, file_path, chunk_index, content, token_count,"
                + " embedding <=> ?::vector AS distance FROM project_code_chunk WHERE project_id ="
                + " ?");
    List<Object> params = new ArrayList<>();
    params.add(toVectorLiteral(query));
    params.add(projectId);
    if (repoId != null && !repoId.isBlank()) {
      sql.append(" AND repo_id = ?");
      params.add(repoId);
    }
    sql.append(" ORDER BY distance ASC LIMIT ?");
    params.add(k);
    return jdbc.query(
        sql.toString(),
        (rs, rowNum) ->
            new RetrievedCodeChunk(
                rs.getString("repo_id"),
                rs.getString("repo_full_name"),
                rs.getString("file_path"),
                rs.getInt("chunk_index"),
                rs.getString("content"),
                rs.getInt("token_count"),
                rs.getDouble("distance")),
        params.toArray());
  }

  /** Map of {@code file_path → file_sha} already indexed for a repo (for incremental reindex). */
  public Map<String, String> indexedFileShas(String repoId) {
    Map<String, String> out = new HashMap<>();
    jdbc.query(
        "SELECT DISTINCT file_path, file_sha FROM project_code_chunk WHERE repo_id = ?",
        (java.sql.ResultSet rs) -> {
          out.put(rs.getString("file_path"), rs.getString("file_sha"));
        },
        repoId);
    return out;
  }

  public void deleteFile(String repoId, String filePath) {
    jdbc.update(
        "DELETE FROM project_code_chunk WHERE repo_id = ? AND file_path = ?", repoId, filePath);
  }

  public void deleteRepo(String repoId) {
    jdbc.update("DELETE FROM project_code_chunk WHERE repo_id = ?", repoId);
  }

  public boolean hasAnyForRepo(String repoId) {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM project_code_chunk WHERE repo_id = ?", Integer.class, repoId);
    return n != null && n > 0;
  }

  private static String toVectorLiteral(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
      if (i > 0) sb.append(',');
      sb.append(v[i]);
    }
    return sb.append(']').toString();
  }
}
