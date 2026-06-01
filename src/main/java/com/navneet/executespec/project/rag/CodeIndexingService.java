package com.navneet.executespec.project.rag;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Code-RAG indexing pipeline (RL-2026-05-052, T3.8). Clones a linked repo (reusing the same cached
 * clone dir as {@code search_repo_files}), walks its source, and chunk→embed→stores each file into
 * {@link CodeChunkStore} so project chat's {@code search_code} can retrieve semantically.
 *
 * <p><b>Incremental by per-file SHA.</b> Each file's content SHA is stored; a reindex only
 * re-embeds files whose content changed since the last pass — so repeated indexing is cheap and a
 * project re-sync only pays for what moved.
 *
 * <p><b>Trigger.</b> Lazy — {@code search_code} calls {@link #indexRepoAsync} on a miss (no index
 * for the repo yet) and answers from grep meanwhile; the index is ready for the next turn. (A
 * future enhancement hooks this into the codemap/architecture clone-walk so it is never cold.)
 *
 * <p>Cost is modest: {@code text-embedding-3-small} at ~$0.02/1M tokens, bounded by {@link
 * #MAX_FILES} + the size/binary skips, and incremental thereafter.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeIndexingService {

  private static final Set<String> SKIP_DIRS =
      Set.of(
          "node_modules",
          "target",
          "build",
          "dist",
          ".git",
          ".next",
          ".venv",
          "__pycache__",
          ".gradle",
          ".idea",
          ".vscode",
          "worktrees",
          "vendor",
          "out");

  private static final Set<String> BINARY_EXT =
      Set.of(
          ".jar", ".class", ".png", ".jpg", ".jpeg", ".gif", ".pdf", ".zip", ".gz", ".tar", ".ico",
          ".woff", ".woff2", ".ttf", ".eot", ".mp4", ".mp3", ".so", ".dylib", ".dll", ".bin",
          ".lock", ".min.js", ".map");

  private static final long MAX_FILE_BYTES = 256L * 1024; // skip large/generated files
  private static final int MAX_FILES = 4000; // per-repo cost ceiling

  private final WorkspaceInitService workspaceInitService;
  private final ChunkingService chunker;
  private final EmbeddingService embedder;
  private final CodeChunkStore codeChunkStore;

  /** Repos currently indexing — stops two chat turns double-indexing the same repo. */
  private final Set<String> inProgress = ConcurrentHashMap.newKeySet();

  /** Whether this repo has any code index yet (drives the lazy trigger in {@code search_code}). */
  public boolean isIndexed(String repoId) {
    return codeChunkStore.hasAnyForRepo(repoId);
  }

  /** Fire-and-forget index. Returns immediately; the caller answers from grep meanwhile. */
  @Async
  public void indexRepoAsync(String projectId, ProjectRepo repo, String pat) {
    try {
      indexRepo(projectId, repo, pat);
    } catch (Exception e) {
      log.warn(
          "code index failed for repo {}: {}",
          repo == null ? "?" : repo.getGithubRepoFullName(),
          e.getMessage());
    }
  }

  /**
   * Clone (cached) → walk source → per-file SHA-incremental chunk+embed+store. Synchronous core.
   */
  public void indexRepo(String projectId, ProjectRepo repo, String pat) {
    if (repo == null) return;
    String repoId = repo.getId().toString();
    if (!inProgress.add(repoId)) {
      log.debug("code index already running for repo {}", repoId);
      return;
    }
    try {
      String fullName = repo.getGithubRepoFullName();
      if (fullName == null || fullName.isBlank()) return;
      String sha = repo.getLastSyncedSha();
      String shaKey =
          sha != null && !sha.isBlank() ? sha.substring(0, Math.min(12, sha.length())) : "head";
      // Shared with search_repo_files: same dir → one clone serves both grep + embeddings.
      String cloneWsId = "projchat-" + repoId + "-" + shaKey;
      String cloneUrl = "https://github.com/" + fullName + ".git";
      try {
        workspaceInitService.cloneFromRepo(cloneWsId, cloneUrl, pat);
      } catch (Exception e) {
        log.warn("code index clone failed for {}: {}", fullName, e.getMessage());
        return;
      }
      Path root = workspaceInitService.runWorkspacePath(cloneWsId);
      if (!Files.isDirectory(root)) return;

      Map<String, String> indexed = codeChunkStore.indexedFileShas(repoId);
      int[] filesIndexed = {0};
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes a) {
              return SKIP_DIRS.contains(d.getFileName().toString())
                  ? FileVisitResult.SKIP_SUBTREE
                  : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
              if (filesIndexed[0] >= MAX_FILES) return FileVisitResult.TERMINATE;
              String name = file.getFileName().toString().toLowerCase();
              if (isBinaryName(name)) return FileVisitResult.CONTINUE;
              try {
                if (a.size() == 0 || a.size() > MAX_FILE_BYTES) return FileVisitResult.CONTINUE;
                byte[] bytes = Files.readAllBytes(file);
                if (isBinaryContent(bytes)) return FileVisitResult.CONTINUE;
                String rel = root.relativize(file).toString();
                String contentSha = sha256(bytes);
                if (contentSha.equals(indexed.get(rel))) {
                  return FileVisitResult.CONTINUE; // unchanged since last index
                }
                String text = new String(bytes, StandardCharsets.UTF_8);
                List<ChunkingService.Chunk> chunks = chunker.chunk(text);
                if (chunks.isEmpty()) return FileVisitResult.CONTINUE;
                List<float[]> embeddings =
                    embedder.embed(chunks.stream().map(ChunkingService.Chunk::content).toList());
                codeChunkStore.deleteFile(repoId, rel);
                codeChunkStore.insertFileChunks(
                    projectId, repoId, fullName, rel, contentSha, chunks, embeddings);
                filesIndexed[0]++;
              } catch (Exception e) {
                log.debug("skip {} during code index: {}", file, e.getMessage());
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
              return FileVisitResult.CONTINUE;
            }
          });
      log.info("code index done repo={} filesIndexed={} sha={}", fullName, filesIndexed[0], shaKey);
    } catch (Exception e) {
      log.warn("code index error repo {}: {}", repoId, e.getMessage());
    } finally {
      inProgress.remove(repoId);
    }
  }

  private static boolean isBinaryName(String lowerName) {
    int dot = lowerName.lastIndexOf('.');
    return dot >= 0 && BINARY_EXT.contains(lowerName.substring(dot));
  }

  private static boolean isBinaryContent(byte[] buf) {
    int n = Math.min(buf.length, 8000);
    for (int i = 0; i < n; i++) if (buf[i] == 0) return true;
    return false;
  }

  private static String sha256(byte[] bytes) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(md.digest(bytes));
    } catch (Exception e) {
      return Integer.toHexString(java.util.Arrays.hashCode(bytes));
    }
  }
}
