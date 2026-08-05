package com.navneet.executespec.project.rag;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionContextException;
import com.navneet.executespec.harness.WorkspaceInitService;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.rag.CodeChunkStore.LeaseLostException;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Completion;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Lease;
import com.navneet.executespec.project.rag.CodeIndexStateStore.State;
import com.navneet.executespec.project.rag.CodeIndexStateStore.Status;
import com.navneet.executespec.project.service.WorkspaceHealthService;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.security.SensitiveWorkspacePathPolicy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Enterprise-safe, incremental source-code semantic indexer.
 *
 * <p>V286 replaces JVM-local "any chunk means ready" truth with a source-SHA-bound durable state
 * and a cross-JVM lease/fencing token. Indexing remains a chunk embedding search aid — it does not
 * claim a language-aware symbol/call relationship graph. Exact identifiers and every semantic
 * unavailable/partial state retain the separate ripgrep-backed {@code search_repo_files} path.
 */
@Service
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

  private static final long MAX_FILE_BYTES = 256L * 1024;
  private static final int DEFAULT_MAX_REFRESHED_FILES = 4000;
  private static final int MAX_INVENTORY_FILES = 200_000;
  private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
  private static final String INSTANCE_ID = UUID.randomUUID().toString();

  public record Readiness(
      String projectId,
      String repoId,
      String currentSourceSha,
      String indexedSourceSha,
      Status status,
      String reason,
      int discoveredFiles,
      int eligibleFiles,
      int indexedFiles,
      int refreshedFiles,
      int unchangedFiles,
      int skippedFiles,
      int deletedFiles,
      int failedFiles,
      int indexedChunks,
      double coveragePercent,
      boolean inventoryTruncated,
      boolean semanticSearchAvailable,
      boolean lexicalFallbackAvailable,
      boolean relationshipGraphAvailable,
      String retrievalMode,
      String embeddingProvider,
      String embeddingModel) {}

  public enum Outcome {
    READY,
    PARTIAL,
    ALREADY_READY,
    ALREADY_RUNNING,
    FAILED,
    LEASE_LOST,
    UNSUPPORTED_SOURCE
  }

  public record IndexResult(Outcome outcome, Readiness readiness) {}

  private record Candidate(Path path, String relativePath, long size) {}

  private record Inventory(List<Candidate> files, int discoveredFiles, boolean truncated) {}

  private static final Comparator<Candidate> CANDIDATE_PRIORITY =
      Comparator.comparingInt((Candidate c) -> priority(c.relativePath()))
          .thenComparing(Candidate::relativePath);

  /** Bounded top-priority reservoir while the complete tree is counted. */
  private static final class InventoryCollector {
    private final PriorityQueue<Candidate> selected =
        new PriorityQueue<>(MAX_INVENTORY_FILES, CANDIDATE_PRIORITY.reversed());
    private int discovered;

    void accept(Candidate candidate) {
      if (discovered < Integer.MAX_VALUE) discovered++;
      if (selected.size() < MAX_INVENTORY_FILES) {
        selected.add(candidate);
      } else if (CANDIDATE_PRIORITY.compare(candidate, selected.peek()) < 0) {
        selected.poll();
        selected.add(candidate);
      }
    }

    Inventory finish() {
      List<Candidate> files = new ArrayList<>(selected);
      files.sort(CANDIDATE_PRIORITY);
      return new Inventory(List.copyOf(files), discovered, discovered > MAX_INVENTORY_FILES);
    }
  }

  private final WorkspaceInitService workspaceInitService;
  private final ChunkingService chunker;
  private final EmbeddingService embedder;
  private final CodeChunkStore codeChunkStore;
  private final CodeIndexStateStore stateStore;
  private final GeneratedSecretScanner secretScanner;
  private final EmbeddingOperationRoot operationRoot;
  private final WorkspaceHealthService workspaceHealthService;
  private final int maxRefreshedFiles;

  @Autowired
  public CodeIndexingService(
      WorkspaceInitService workspaceInitService,
      ChunkingService chunker,
      EmbeddingService embedder,
      CodeChunkStore codeChunkStore,
      CodeIndexStateStore stateStore,
      GeneratedSecretScanner secretScanner,
      EmbeddingOperationRoot operationRoot,
      WorkspaceHealthService workspaceHealthService) {
    this(
        workspaceInitService,
        chunker,
        embedder,
        codeChunkStore,
        stateStore,
        secretScanner,
        operationRoot,
        workspaceHealthService,
        DEFAULT_MAX_REFRESHED_FILES);
  }

  /** Package-visible cap override makes partial/resume behavior testable without 4,001 calls. */
  CodeIndexingService(
      WorkspaceInitService workspaceInitService,
      ChunkingService chunker,
      EmbeddingService embedder,
      CodeChunkStore codeChunkStore,
      CodeIndexStateStore stateStore,
      int maxRefreshedFiles) {
    this(
        workspaceInitService,
        chunker,
        embedder,
        codeChunkStore,
        stateStore,
        new GeneratedSecretScanner(),
        null,
        null,
        maxRefreshedFiles);
  }

  CodeIndexingService(
      WorkspaceInitService workspaceInitService,
      ChunkingService chunker,
      EmbeddingService embedder,
      CodeChunkStore codeChunkStore,
      CodeIndexStateStore stateStore,
      GeneratedSecretScanner secretScanner,
      int maxRefreshedFiles) {
    this(
        workspaceInitService,
        chunker,
        embedder,
        codeChunkStore,
        stateStore,
        secretScanner,
        null,
        null,
        maxRefreshedFiles);
  }

  CodeIndexingService(
      WorkspaceInitService workspaceInitService,
      ChunkingService chunker,
      EmbeddingService embedder,
      CodeChunkStore codeChunkStore,
      CodeIndexStateStore stateStore,
      GeneratedSecretScanner secretScanner,
      EmbeddingOperationRoot operationRoot,
      int maxRefreshedFiles) {
    this(
        workspaceInitService,
        chunker,
        embedder,
        codeChunkStore,
        stateStore,
        secretScanner,
        operationRoot,
        null,
        maxRefreshedFiles);
  }

  CodeIndexingService(
      WorkspaceInitService workspaceInitService,
      ChunkingService chunker,
      EmbeddingService embedder,
      CodeChunkStore codeChunkStore,
      CodeIndexStateStore stateStore,
      GeneratedSecretScanner secretScanner,
      EmbeddingOperationRoot operationRoot,
      WorkspaceHealthService workspaceHealthService,
      int maxRefreshedFiles) {
    this.workspaceInitService = workspaceInitService;
    this.chunker = chunker;
    this.embedder = embedder;
    this.codeChunkStore = codeChunkStore;
    this.stateStore = stateStore;
    this.secretScanner = secretScanner;
    this.operationRoot = operationRoot;
    this.workspaceHealthService = workspaceHealthService;
    this.maxRefreshedFiles = Math.max(1, maxRefreshedFiles);
  }

  /** Ready means a completed 100%-covered state for this exact project/repo/source revision. */
  @Deprecated(forRemoval = true)
  public boolean isIndexed(String projectId, ProjectRepo repo) {
    return readiness(projectId, repo).semanticSearchAvailable();
  }

  /** Prefer the actor-aware overload; retained for package tests and legacy internal callers. */
  @Deprecated(forRemoval = true)
  public Readiness readiness(String projectId, ProjectRepo repo) {
    return readiness(projectId, repo, repo == null ? null : repo.getOwnerUserId());
  }

  public Readiness readiness(String projectId, ProjectRepo repo, String callerUserId) {
    if (repo == null || repo.getId() == null) {
      return emptyReadiness(projectId, null, "UNKNOWN", "REPOSITORY_NOT_FOUND", Status.FAILED);
    }
    String sourceSha = sourceRevision(repo, callerUserId);
    if ("UNKNOWN".equals(sourceSha)) {
      return stateStore
          .get(projectId, repo.getId())
          .map(state -> unavailableSourceReadiness(state))
          .orElseGet(
              () ->
                  emptyReadiness(
                      projectId,
                      repo.getId().toString(),
                      sourceSha,
                      "CURRENT_SOURCE_REVISION_UNAVAILABLE",
                      Status.FAILED));
    }
    stateStore.reconcile(projectId, repo.getId(), sourceSha);
    return stateStore
        .get(projectId, repo.getId())
        .map(state -> toReadiness(state, sourceSha))
        .orElseGet(
            () ->
                emptyReadiness(
                    projectId,
                    repo.getId().toString(),
                    sourceSha,
                    "INDEX_NOT_BUILT",
                    Status.UNINDEXED));
  }

  /** Fire-and-forget lazy build. Failure is durably visible through the readiness endpoint. */
  @Async
  public void indexRepoAsync(String projectId, ProjectRepo repo, String pat) {
    indexRepo(projectId, repo, null, pat, null);
  }

  @Async
  public void indexRepoAsync(
      String projectId, ProjectRepo repo, String credentialUsername, String credentialSecret) {
    indexRepo(projectId, repo, credentialUsername, credentialSecret, null);
  }

  @Async
  public void indexRepoAsync(
      String projectId,
      ProjectRepo repo,
      String credentialUsername,
      String credentialSecret,
      String actorUserId) {
    indexRepo(projectId, repo, credentialUsername, credentialSecret, actorUserId, false);
  }

  /** Explicit user refresh may start a new one-shot invocation after a prior terminal attempt. */
  @Async
  public void indexRepoExplicitAsync(
      String projectId,
      ProjectRepo repo,
      String credentialUsername,
      String credentialSecret,
      String actorUserId) {
    indexRepo(projectId, repo, credentialUsername, credentialSecret, actorUserId, true);
  }

  /** Clone/cache → deterministic inventory → SHA-incremental fenced publication. */
  public IndexResult indexRepo(String projectId, ProjectRepo repo, String pat) {
    return indexRepo(projectId, repo, null, pat, null);
  }

  public IndexResult indexRepo(
      String projectId, ProjectRepo repo, String credentialUsername, String credentialSecret) {
    return indexRepo(projectId, repo, credentialUsername, credentialSecret, null);
  }

  public IndexResult indexRepo(
      String projectId,
      ProjectRepo repo,
      String credentialUsername,
      String credentialSecret,
      String actorUserId) {
    return indexRepo(projectId, repo, credentialUsername, credentialSecret, actorUserId, true);
  }

  private IndexResult indexRepo(
      String projectId,
      ProjectRepo repo,
      String credentialUsername,
      String credentialSecret,
      String actorUserId,
      boolean confirmedNewInvocation) {
    if (repo == null || repo.getId() == null || projectId == null || projectId.isBlank()) {
      return new IndexResult(
          Outcome.UNSUPPORTED_SOURCE,
          emptyReadiness(
              projectId, null, "UNKNOWN", "INVALID_PROJECT_OR_REPOSITORY", Status.FAILED));
    }

    String sourceSha = sourceRevision(repo, actorUserId);
    if ("UNKNOWN".equals(sourceSha)) {
      return new IndexResult(
          Outcome.UNSUPPORTED_SOURCE,
          emptyReadiness(
              projectId,
              repo.getId().toString(),
              sourceSha,
              "CURRENT_SOURCE_REVISION_UNAVAILABLE",
              Status.FAILED));
    }
    stateStore.reconcile(projectId, repo.getId(), sourceSha);
    Readiness before = readiness(projectId, repo, actorUserId);
    if (before.semanticSearchAvailable()) {
      return new IndexResult(Outcome.ALREADY_READY, before);
    }
    if (!confirmedNewInvocation
        && (before.status() == Status.PARTIAL || before.status() == Status.FAILED)) {
      // Project-chat lazy indexing is not confirmation to replay a terminal/partial provider
      // operation. The explicit refresh endpoint is the only authority to begin a new invocation.
      return new IndexResult(Outcome.FAILED, before);
    }

    String owner = INSTANCE_ID + ":" + UUID.randomUUID();
    Lease lease =
        stateStore
            .tryAcquire(projectId, repo.getId(), sourceSha, owner, LEASE_DURATION)
            .orElse(null);
    if (lease == null) {
      return new IndexResult(Outcome.ALREADY_RUNNING, readiness(projectId, repo, actorUserId));
    }

    var embeddingAvailability = embedder.availability(projectId);
    if (!embeddingAvailability.semanticSearchAvailable()) {
      stateStore.fail(lease, "EMBEDDING_" + embeddingAvailability.reason());
      return new IndexResult(Outcome.UNSUPPORTED_SOURCE, readiness(projectId, repo, actorUserId));
    }

    EmbeddingOperationRoot.Active operation = null;
    try {
      String fullName = sourceDisplayName(repo);
      String cloneUrl = cloneUrl(repo);
      if (cloneUrl == null) {
        stateStore.fail(lease, "SOURCE_CLONE_URL_UNAVAILABLE");
        return new IndexResult(Outcome.UNSUPPORTED_SOURCE, readiness(projectId, repo, actorUserId));
      }
      String cloneWsId = CodeIndexWorkspaceIdentity.workspaceId(projectId, repo);
      try {
        String gitUsername =
            "BITBUCKET".equalsIgnoreCase(repo.getScmProvider())
                ? "x-bitbucket-api-token-auth"
                : credentialUsername;
        workspaceInitService.cloneReadOnlyRepository(
            cloneWsId, cloneUrl, gitUsername, credentialSecret);
      } catch (Exception cloneFailure) {
        stateStore.fail(lease, "SOURCE_CLONE_FAILED");
        log.warn("code index clone failed repo={} type={}", fullName, failureType(cloneFailure));
        return new IndexResult(Outcome.FAILED, readiness(projectId, repo, actorUserId));
      }

      Path root = workspaceInitService.runWorkspacePath(cloneWsId);
      if (!Files.isDirectory(root, NOFOLLOW_LINKS)) {
        stateStore.fail(lease, "SOURCE_WORKSPACE_UNAVAILABLE");
        return new IndexResult(Outcome.FAILED, readiness(projectId, repo, actorUserId));
      }

      // RR-641 — the readiness row + workspace name are labelled with `sourceSha`, but the clone
      // landed the branch's current default HEAD. When the recorded revision is a real commit id,
      // check it out exactly and verify HEAD before inventorying, so a branch that advanced between
      // metadata sync and clone cannot publish newer chunks under the older recorded SHA. A
      // revision
      // that can no longer be pinned (force-push/rebase) fails the index rather than indexing a
      // mismatched tree.
      if (isFullCommitSha(sourceSha)
          && !workspaceInitService.checkoutExactCommit(cloneWsId, sourceSha)) {
        stateStore.fail(lease, "SOURCE_REVISION_UNAVAILABLE");
        log.warn(
            "code index revision pin failed repo={} sha={}",
            sourceDisplayName(repo),
            shortSha(sourceSha));
        return new IndexResult(Outcome.FAILED, readiness(projectId, repo, actorUserId));
      }

      Inventory inventory = inventory(root);
      Map<String, String> indexed =
          codeChunkStore.indexedFileShas(projectId, repo.getId().toString());
      EmbeddingOperationRoot.Request operationRequest =
          codeIndexOperation(projectId, repo, sourceSha, lease, actorUserId);
      if (operationRoot != null) {
        operation =
            operationRoot
                .claim(operationRequest, lease.owner(), LEASE_DURATION, confirmedNewInvocation)
                .orElseThrow(
                    () ->
                        new IllegalStateException("Code-index logical operation is not claimable"));
      }
      EmbeddingOperationRoot.Active activeOperation = operation;
      Set<String> currentEligiblePaths = new HashSet<>();
      int discovered = inventory.discoveredFiles();
      int eligible = 0;
      int refreshed = 0;
      int unchanged = 0;
      int skipped = Math.max(0, discovered - inventory.files().size());
      int deleted = 0;
      int failed = 0;
      int capDeferred = 0;
      int candidatesSinceHeartbeat = 0;

      for (Candidate candidate : inventory.files()) {
        candidatesSinceHeartbeat++;
        if (activeOperation != null && candidatesSinceHeartbeat >= 100) {
          if (!renewLeases(lease, activeOperation)) throw new LeaseLostException();
          candidatesSinceHeartbeat = 0;
        }
        if (SensitiveWorkspacePathPolicy.isSensitive(candidate.relativePath())
            || isBinaryName(candidate.relativePath())
            || candidate.size() == 0
            || candidate.size() > MAX_FILE_BYTES) {
          skipped++;
          continue;
        }

        byte[] bytes;
        try {
          bytes = Files.readAllBytes(candidate.path());
        } catch (IOException readFailure) {
          skipped++;
          failed++;
          continue;
        }
        if (isBinaryContent(bytes)) {
          skipped++;
          continue;
        }
        String content = new String(bytes, StandardCharsets.UTF_8);
        if (secretScanner.scan(content).isPresent()) {
          skipped++;
          continue;
        }

        eligible++;
        currentEligiblePaths.add(candidate.relativePath());
        String contentSha = sha256(bytes);
        if (contentSha.equals(indexed.get(candidate.relativePath()))) {
          unchanged++;
          continue;
        }
        if (refreshed >= maxRefreshedFiles) {
          skipped++;
          capDeferred++;
          continue;
        }
        if (!renewLeases(lease, activeOperation)) throw new LeaseLostException();

        try {
          List<ChunkingService.Chunk> chunks = chunker.chunk(content);
          if (chunks.isEmpty()) {
            skipped++;
            failed++;
            continue;
          }
          List<float[]> embeddings =
              activeOperation == null
                  ? embedder.embedForProject(
                      projectId,
                      chunks.stream().map(ChunkingService.Chunk::content).toList(),
                      "ProjectCodeEmbedding",
                      "PROJECT_REPOSITORY",
                      repo.getId().toString(),
                      actorUserId)
                  : embedder.embedForProject(
                      projectId,
                      chunks.stream().map(ChunkingService.Chunk::content).toList(),
                      "ProjectCodeEmbedding",
                      "PROJECT_REPOSITORY",
                      operationRequest.sourceId(),
                      actorUserId,
                      (completed, total) -> renewLeases(lease, activeOperation),
                      activeOperation.authority());
          codeChunkStore.replaceFileChunks(
              lease, fullName, candidate.relativePath(), contentSha, chunks, embeddings);
          refreshed++;
        } catch (EmbeddingOwnershipLostException lost) {
          throw new LeaseLostException();
        } catch (LeaseLostException lost) {
          throw lost;
        } catch (Exception fileFailure) {
          failed++;
          log.debug(
              "code index skipped file repo={} file={} type={}",
              fullName,
              candidate.relativePath(),
              failureType(fileFailure));
        }
      }

      // Files removed from the source (or now binary/oversized) must not survive semantically.
      if (!inventory.truncated()) {
        for (String stalePath : indexed.keySet()) {
          if (!currentEligiblePaths.contains(stalePath)) {
            if (!renewLeases(lease, activeOperation)) throw new LeaseLostException();
            if (codeChunkStore.deleteFile(lease, stalePath)) deleted++;
          }
        }
      }

      int covered = unchanged + refreshed;
      double coverage = eligible == 0 ? 0 : Math.floor(covered * 10_000.0 / eligible) / 100.0;
      boolean sourcePinned = !"UNPINNED".equals(sourceSha);
      boolean ready =
          sourcePinned
              && !inventory.truncated()
              && capDeferred == 0
              && failed == 0
              && eligible > 0
              && covered == eligible;
      Status finalStatus = ready ? Status.READY : Status.PARTIAL;
      String reason =
          ready
              ? "FULL_ELIGIBLE_SOURCE_COVERAGE"
              : partialReason(sourcePinned, inventory.truncated(), capDeferred, failed, eligible);
      int chunks = codeChunkStore.countChunks(projectId, repo.getId().toString());
      Completion completion =
          new Completion(
              finalStatus,
              reason,
              discovered,
              eligible,
              covered,
              refreshed,
              unchanged,
              skipped,
              deleted,
              failed,
              chunks,
              coverage,
              inventory.truncated());
      if (!stateStore.complete(lease, completion)) throw new LeaseLostException();
      if (operation != null) {
        operationRoot.complete(operation);
        operation = null;
      }
      Readiness after = readiness(projectId, repo, actorUserId);
      log.info(
          "code index finished repo={} status={} coverage={} indexed={} eligible={} refreshed={}"
              + " deleted={} inventoryTruncated={}",
          fullName,
          finalStatus,
          coverage,
          covered,
          eligible,
          refreshed,
          deleted,
          inventory.truncated());
      return new IndexResult(ready ? Outcome.READY : Outcome.PARTIAL, after);
    } catch (LeaseLostException lost) {
      terminateOperation(operation, "CODE_INDEX_LEASE_LOST", "Code-index lease was lost");
      log.info("code index lease lost project={} repo={}", projectId, repo.getId());
      return new IndexResult(Outcome.LEASE_LOST, readiness(projectId, repo, actorUserId));
    } catch (LlmExecutionContextException contextFailure) {
      String code = "EXECUTION_CONTEXT_" + contextFailure.reason().name();
      terminateOperation(operation, "CODE_INDEX_" + code, "Code-index admission did not complete");
      stateStore.fail(lease, code);
      log.warn(
          "code index execution-context admission failed project={} repo={} reason={}",
          projectId,
          repo.getId(),
          contextFailure.reason());
      return new IndexResult(Outcome.FAILED, readiness(projectId, repo, actorUserId));
    } catch (Exception failure) {
      terminateOperation(operation, "CODE_INDEX_FAILED", "Code indexing did not complete");
      stateStore.fail(lease, "INDEX_FAILED_" + failureType(failure));
      log.warn(
          "code index failed project={} repo={} type={}",
          projectId,
          repo.getId(),
          failureType(failure));
      return new IndexResult(Outcome.FAILED, readiness(projectId, repo, actorUserId));
    }
  }

  private static EmbeddingOperationRoot.Request codeIndexOperation(
      String projectId, ProjectRepo repo, String sourceSha, Lease lease, String actorUserId) {
    String sourceIdentity = EmbeddingOperationIdentity.codeIndex(repo.getId(), sourceSha);
    return new EmbeddingOperationRoot.Request(
        projectId,
        "ProjectCodeEmbedding",
        "PROJECT_REPOSITORY",
        sourceIdentity,
        actorUserId,
        "project-code-index:" + projectId + ":" + repo.getId() + ":" + shortSha(sourceSha),
        "PROJECT_CODE_INDEX",
        projectId + "|" + repo.getId() + "|" + sourceSha);
  }

  private void terminateOperation(
      EmbeddingOperationRoot.Active operation, String code, String message) {
    if (operation == null || operationRoot == null) return;
    try {
      operationRoot.terminate(operation, code, message);
    } catch (RuntimeException terminalFailure) {
      log.warn("code-index logical-operation cleanup failed type={}", failureType(terminalFailure));
    }
  }

  private boolean renewLeases(Lease lease, EmbeddingOperationRoot.Active operation) {
    return stateStore.renew(lease, LEASE_DURATION)
        && (operation == null || operationRoot.heartbeat(operation, LEASE_DURATION));
  }

  /**
   * Deterministic bounded traversal: each directory is visited in lexical order; the bounded
   * inventory is then priority-sorted so manifests/build files and source code beat docs/tests.
   * Symlinks are never followed. Truncation is explicit rather than silently called complete.
   */
  private static Inventory inventory(Path root) throws IOException {
    InventoryCollector collector = new InventoryCollector();
    collect(root, root, collector);
    return collector.finish();
  }

  private static void collect(Path root, Path directory, InventoryCollector collector)
      throws IOException {
    List<Path> entries = new ArrayList<>();
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
      stream.forEach(entries::add);
    }
    entries.sort(Comparator.comparing(p -> p.getFileName().toString()));
    for (Path entry : entries) {
      if (Files.isSymbolicLink(entry)) continue;
      if (Files.isDirectory(entry, NOFOLLOW_LINKS)) {
        if (!SKIP_DIRS.contains(entry.getFileName().toString())) {
          collect(root, entry, collector);
        }
      } else if (Files.isRegularFile(entry, NOFOLLOW_LINKS)) {
        String rel = root.relativize(entry).toString().replace('\\', '/');
        collector.accept(new Candidate(entry, rel, Files.size(entry)));
      }
    }
  }

  private static int priority(String path) {
    String p = path.toLowerCase(Locale.ROOT);
    String file = p.substring(p.lastIndexOf('/') + 1);
    if (p.startsWith(".executespec/")
        || Set.of(
                "pom.xml",
                "build.gradle",
                "build.gradle.kts",
                "settings.gradle",
                "package.json",
                "pyproject.toml",
                "cargo.toml",
                "go.mod",
                "requirements.txt")
            .contains(file)) return 0;
    if (p.startsWith("src/") || p.contains("/src/") || p.startsWith("app/") || p.startsWith("lib/"))
      return 1;
    if (p.startsWith("test/") || p.startsWith("tests/") || p.contains("/test/")) return 2;
    if (p.startsWith("docs/") || p.endsWith(".md")) return 3;
    return 2;
  }

  private Readiness toReadiness(State state, String currentSourceSha) {
    boolean sameRevision = state.sourceSha().equals(currentSourceSha);
    Status status = sameRevision ? state.status() : Status.STALE;
    String reason = sameRevision ? state.statusReason() : "SOURCE_REVISION_CHANGED";
    var embedding = embedder.availability(state.projectId());
    boolean routeReady = embedding.semanticSearchAvailable();
    if (!routeReady && status == Status.READY) {
      status = Status.STALE;
      reason = "EMBEDDING_" + embedding.reason();
    }
    boolean semanticReady =
        routeReady && status == Status.READY && state.coveragePercent() == 100.0;
    return new Readiness(
        state.projectId(),
        state.repoId().toString(),
        currentSourceSha,
        state.sourceSha(),
        status,
        reason,
        state.discoveredFiles(),
        state.eligibleFiles(),
        state.indexedFiles(),
        state.refreshedFiles(),
        state.unchangedFiles(),
        state.skippedFiles(),
        state.deletedFiles(),
        state.failedFiles(),
        state.indexedChunks(),
        state.coveragePercent(),
        state.inventoryTruncated(),
        semanticReady,
        true,
        false,
        semanticReady ? "SEMANTIC" : "LEXICAL",
        semanticReady ? embedding.provider() : null,
        semanticReady ? embedding.model() : null);
  }

  private Readiness unavailableSourceReadiness(State state) {
    return new Readiness(
        state.projectId(),
        state.repoId().toString(),
        "UNKNOWN",
        state.sourceSha(),
        Status.FAILED,
        "CURRENT_SOURCE_REVISION_UNAVAILABLE",
        state.discoveredFiles(),
        state.eligibleFiles(),
        state.indexedFiles(),
        state.refreshedFiles(),
        state.unchangedFiles(),
        state.skippedFiles(),
        state.deletedFiles(),
        state.failedFiles(),
        state.indexedChunks(),
        state.coveragePercent(),
        state.inventoryTruncated(),
        false,
        true,
        false,
        "LEXICAL",
        null,
        null);
  }

  private Readiness emptyReadiness(
      String projectId, String repoId, String sourceSha, String reason, Status status) {
    var embedding =
        projectId == null || projectId.isBlank() ? null : embedder.availability(projectId);
    if (embedding != null && !embedding.semanticSearchAvailable()) {
      reason = "EMBEDDING_" + embedding.reason();
      status = Status.FAILED;
    }
    return new Readiness(
        projectId, repoId, sourceSha, null, status, reason, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false,
        false, true, false, "LEXICAL", null, null);
  }

  private String sourceRevision(ProjectRepo repo, String callerUserId) {
    if (ProjectRepo.SourceType.LOCAL.name().equals(repo.getSourceType())) {
      String revision = repo.getSourceRevision();
      return revision == null || revision.isBlank() ? "UNKNOWN" : revision.trim();
    }
    if (workspaceHealthService != null) {
      String current = workspaceHealthService.currentOriginSha(repo, callerUserId);
      return current == null || current.isBlank() ? "UNKNOWN" : current.trim();
    }
    // Package-level tests constructed before current-origin resolution was injected retain their
    // fixed fixture revision. The Spring constructor always installs the authoritative resolver.
    return CodeIndexWorkspaceIdentity.sourceRevision(repo);
  }

  private static String sourceDisplayName(ProjectRepo repo) {
    if (repo.getSourceDisplayName() != null && !repo.getSourceDisplayName().isBlank()) {
      return repo.getSourceDisplayName();
    }
    if (repo.getGithubRepoFullName() != null && !repo.getGithubRepoFullName().isBlank()) {
      return repo.getGithubRepoFullName();
    }
    return repo.getId().toString();
  }

  private static String cloneUrl(ProjectRepo repo) {
    if (repo.getScmCloneUrl() != null && !repo.getScmCloneUrl().isBlank()) {
      return repo.getScmCloneUrl();
    }
    if (repo.getGithubRepoFullName() != null && !repo.getGithubRepoFullName().isBlank()) {
      return "https://github.com/" + repo.getGithubRepoFullName() + ".git";
    }
    return null;
  }

  private static String partialReason(
      boolean sourcePinned, boolean inventoryTruncated, int deferred, int failed, int eligible) {
    if (!sourcePinned) return "SOURCE_REVISION_UNPINNED";
    if (inventoryTruncated) return "INVENTORY_LIMIT_REACHED";
    if (deferred > 0) return "REFRESH_LIMIT_REACHED_RESUMABLE";
    if (failed > 0) return "FILE_INDEX_FAILURES";
    if (eligible == 0) return "NO_ELIGIBLE_SOURCE_FILES";
    return "INCOMPLETE_COVERAGE";
  }

  private static boolean isBinaryName(String path) {
    String lower = path.toLowerCase(Locale.ROOT);
    return BINARY_EXT.stream().anyMatch(lower::endsWith);
  }

  private static boolean isBinaryContent(byte[] bytes) {
    int n = Math.min(bytes.length, 8000);
    for (int i = 0; i < n; i++) if (bytes[i] == 0) return true;
    return false;
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static String shortSha(String sha) {
    String safe = sha.replaceAll("[^A-Za-z0-9._-]", "_");
    return safe.substring(0, Math.min(12, safe.length()));
  }

  /**
   * RR-641 — a full 40-hex Git commit id is the only revision we can detached-checkout + verify.
   */
  private static boolean isFullCommitSha(String sha) {
    return sha != null && sha.matches("[0-9a-fA-F]{40}");
  }

  private static String failureType(Throwable failure) {
    String name = failure == null ? "UNKNOWN" : failure.getClass().getSimpleName();
    return name.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
  }
}
