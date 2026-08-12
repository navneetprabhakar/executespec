package com.navneet.executespec.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Creates a reversible, content-addressed checkpoint before an automatic correction mutates a run
 * workspace.
 *
 * <p>The checkpoint is a Git tree object produced by a non-executing JGit tree writer. It captures
 * tracked and untracked (but not ignored) files without applying repository filters or invoking
 * hooks/helpers, and without moving {@code HEAD}, creating a branch, copying the repository, or
 * staging/resetting the live caller-owned index. A rejected correction restores that exact tree
 * while leaving the repository on its original commit.
 *
 * <p>Multi-repository project workspaces are supported by checkpointing the workspace root
 * repository (when it is Git-backed) PLUS every nested repository below it, so uncommitted worker
 * edits inside {@code repos/<name>} clones are first-class evidence rather than opaque gitlinks.
 * Nested repository paths are excluded from their parent's tree so each byte is counted exactly
 * once by the repository that owns it; commit-only gitlink movement therefore cannot change the
 * workspace generation. All repositories must checkpoint successfully before correction may begin.
 */
@Component
public class CorrectionWorkspaceTransaction {

  static final int MAX_PATCH_BYTES = 32_768;
  private static final int MAX_REPOSITORIES = 32;

  /**
   * Version tag hashed INSIDE the generation preimage. Legacy (pre-v2) digests hashed {@code
   * <index>=<tree>} rows computed over the workspace root repository alone, so uncommitted edits
   * inside nested {@code repos/<name>} clones were invisible. Every persisted pre-v2 digest
   * intentionally compares unequal to a v2 digest, so legacy generations read as stale and route to
   * the existing regenerate/mismatch remediation instead of silently attesting unseen bytes.
   */
  static final String GENERATION_PREIMAGE_VERSION = "v2\n";

  @FunctionalInterface
  interface PhysicalMutationObserver {
    void afterMutation(String label, Path repository);
  }

  private final CorrectionGitCommands commands;
  private final NonExecutingGitTreeWriter treeWriter;
  private final PhysicalMutationObserver physicalMutations;

  public CorrectionWorkspaceTransaction() {
    this(new CorrectionGitCommands(), new NonExecutingGitTreeWriter(), (label, repository) -> {});
  }

  CorrectionWorkspaceTransaction(
      CorrectionGitCommands commands,
      NonExecutingGitTreeWriter treeWriter,
      PhysicalMutationObserver physicalMutations) {
    this.commands = Objects.requireNonNull(commands);
    this.treeWriter = Objects.requireNonNull(treeWriter);
    this.physicalMutations = Objects.requireNonNull(physicalMutations);
  }

  /**
   * Captures the current content-addressed workspace generation without retaining rollback state.
   *
   * <p>This is the validation/reporting seam: callers can bind evidence to the same canonical tree
   * calculation used by correction acceptance without creating a branch, commit, clone, or
   * worktree.
   */
  public String captureGeneration(Path workspaceRoot) {
    return begin(workspaceRoot).generation();
  }

  public Checkpoint begin(Path workspaceRoot) {
    List<Path> repositories = discoverRepositories(workspaceRoot);
    if (repositories.isEmpty()) {
      throw new IllegalStateException(
          "Automatic correction requires a Git-backed workspace checkpoint");
    }
    List<RepositoryCheckpoint> captured = new ArrayList<>();
    try {
      for (Path repository : repositories) {
        captured.add(capture(repository, nestedRepositoryPaths(repositories, repository)));
      }
      return new Checkpoint(workspaceRoot.toAbsolutePath().normalize(), List.copyOf(captured));
    } catch (RuntimeException failure) {
      for (int i = captured.size() - 1; i >= 0; i--) {
        RepositoryCheckpoint checkpoint = captured.get(i);
        try {
          restore(
              checkpoint, nestedRepositoryPaths(repositories, checkpoint.repository()), () -> {});
        } catch (RuntimeException restoreFailure) {
          failure.addSuppressed(restoreFailure);
        }
      }
      throw failure;
    }
  }

  /** Returns the current content-addressed workspace generation without changing the worktree. */
  public String currentGeneration(Checkpoint checkpoint) {
    return currentGeneration(checkpoint, () -> {});
  }

  /** Computes a generation while rechecking exact authority before every Git/index operation. */
  public String currentGeneration(Checkpoint checkpoint, Runnable authorityFence) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Objects.requireNonNull(authorityFence, "authorityFence");
    List<String> trees = new ArrayList<>();
    for (RepositoryCheckpoint repository : checkpoint.repositories()) {
      authorityFence.run();
      assertHeadUnchanged(repository);
      trees.add(
          workspaceRelativePath(checkpoint.workspaceRoot(), repository.repository())
              + "="
              + writeTree(
                  repository.repository(),
                  checkpoint.nestedRepositoriesOf(repository),
                  authorityFence));
    }
    return org.apache.commons.codec.digest.DigestUtils.sha256Hex(
        GENERATION_PREIMAGE_VERSION + String.join("\n", trees));
  }

  /**
   * Captures the current trees while retaining the checkpoint's exact repository identities and
   * original HEAD fence. This is the archive boundary: callers receive the concrete candidate tree
   * objects, not merely their aggregate generation digest.
   */
  public Checkpoint snapshotCurrent(Checkpoint checkpoint, Runnable authorityFence) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Objects.requireNonNull(authorityFence, "authorityFence");
    List<RepositoryCheckpoint> current = new ArrayList<>();
    for (RepositoryCheckpoint repository : checkpoint.repositories()) {
      authorityFence.run();
      assertHeadUnchanged(repository);
      current.add(
          new RepositoryCheckpoint(
              repository.repository(),
              repository.head(),
              writeTree(
                  repository.repository(),
                  checkpoint.nestedRepositoriesOf(repository),
                  authorityFence)));
    }
    return new Checkpoint(checkpoint.workspaceRoot(), current);
  }

  /** Accepts the current workspace only when it differs from no uncommitted caller-owned index. */
  public Accepted accept(Checkpoint checkpoint) {
    String generation = currentGeneration(checkpoint);
    return new Accepted(checkpoint.generation(), generation);
  }

  /**
   * Produces deterministic visibility evidence without changing the index. The authoritative digest
   * covers every tree byte; the human-readable unified patch is separately bounded and excludes
   * likely credential files and binary payloads.
   */
  public DiffEvidence diffEvidence(Checkpoint checkpoint) {
    return diffEvidence(checkpoint, () -> {});
  }

  /** Produces diff evidence while fencing every index mutation and Git subprocess boundary. */
  public DiffEvidence diffEvidence(Checkpoint checkpoint, Runnable authorityFence) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Objects.requireNonNull(authorityFence, "authorityFence");
    List<String> changedFiles = new ArrayList<>();
    List<String> contentAddressedTreePairs = new ArrayList<>();
    ByteArrayOutputStream patch = new ByteArrayOutputStream(MAX_PATCH_BYTES);
    long fullPatchBytes = 0L;
    boolean patchRedacted = false;
    for (int i = 0; i < checkpoint.repositories().size(); i++) {
      RepositoryCheckpoint repository = checkpoint.repositories().get(i);
      authorityFence.run();
      assertHeadUnchanged(repository);
      String currentTree =
          writeTree(
              repository.repository(), checkpoint.nestedRepositoriesOf(repository), authorityFence);
      contentAddressedTreePairs.add(i + "\t" + repository.tree() + "\t" + currentTree);
      authorityFence.run();
      String output =
          commands.git(
              repository.repository(),
              "diff",
              "--name-status",
              "--no-renames",
              repository.tree(),
              currentTree);
      if (output.isBlank()) {
        continue;
      }
      List<String> safePaths = new ArrayList<>();
      for (String row : output.lines().limit(500).toList()) {
        String[] columns = row.trim().split("\\t");
        String path = columns.length == 0 ? row.trim() : columns[columns.length - 1];
        if (isSensitivePath(path)) {
          changedFiles.add(i + ":[REDACTED_SENSITIVE_PATH]");
          patchRedacted = true;
        } else {
          changedFiles.add(i + ":" + path);
          safePaths.add(path);
        }
      }
      if (!safePaths.isEmpty()) {
        List<String> args = new ArrayList<>();
        args.add("diff");
        args.add("--no-ext-diff");
        args.add("--no-textconv");
        args.add("--no-color");
        args.add("--no-renames");
        args.add("--unified=3");
        args.add(repository.tree());
        args.add(currentTree);
        args.add("--");
        args.addAll(safePaths);
        byte[] header = ("### repository " + i + "\n").getBytes(StandardCharsets.UTF_8);
        fullPatchBytes += header.length;
        appendBounded(patch, header);
        authorityFence.run();
        CorrectionGitCommands.BinaryCommandResult patchResult =
            commands.gitBytes(
                repository.repository(), MAX_PATCH_BYTES, args.toArray(String[]::new));
        fullPatchBytes += patchResult.fullOutputBytes();
        appendBounded(patch, patchResult.output());
      }
    }
    // Git tree object ids are content-addressed over every path, mode and blob. Hashing the exact
    // before/after tree pair therefore distinguishes two corrections that touch the same filenames
    // with different bytes without retaining or truncating sensitive patch content.
    String canonical = String.join("\n", contentAddressedTreePairs);
    String unifiedPatch = utf8Prefix(patch.toByteArray(), MAX_PATCH_BYTES);
    return new DiffEvidence(
        List.copyOf(changedFiles),
        org.apache.commons.codec.digest.DigestUtils.sha256Hex(canonical),
        unifiedPatch,
        unifiedPatch.getBytes(StandardCharsets.UTF_8).length,
        fullPatchBytes,
        fullPatchBytes > MAX_PATCH_BYTES,
        patchRedacted);
  }

  private static boolean isSensitivePath(String path) {
    if (path == null || path.isBlank()) return true;
    String normalized = path.replace('\\', '/').toLowerCase(java.util.Locale.ROOT);
    String name = normalized.substring(normalized.lastIndexOf('/') + 1);
    return name.equals(".env")
        || name.startsWith(".env.")
        || name.equals("credentials")
        || name.startsWith("credentials.")
        || name.equals("secrets.yml")
        || name.equals("secrets.yaml")
        || name.endsWith(".pem")
        || name.endsWith(".key")
        || name.endsWith(".p12")
        || name.endsWith(".pfx")
        || name.equals("id_rsa")
        || name.equals("id_ed25519")
        || normalized.contains("/.ssh/");
  }

  private static void appendBounded(ByteArrayOutputStream target, byte[] source) {
    int remaining = MAX_PATCH_BYTES - target.size();
    if (remaining > 0) target.write(source, 0, Math.min(remaining, source.length));
  }

  private static String utf8Prefix(byte[] bytes, int limit) {
    int length = Math.min(bytes.length, limit);
    while (length > 0) {
      try {
        return StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes, 0, length))
            .toString();
      } catch (java.nio.charset.CharacterCodingException incompleteSuffix) {
        length--;
      }
    }
    return "";
  }

  /** Restores every repository to the exact pre-correction tree and original {@code HEAD}. */
  public void rollback(Checkpoint checkpoint) {
    rollback(checkpoint, () -> {});
  }

  /** Restores with an exact authority fence before every repository and Git mutation. */
  public void rollback(Checkpoint checkpoint, Runnable authorityFence) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Objects.requireNonNull(authorityFence, "authorityFence");
    RuntimeException failure = null;
    List<RepositoryCheckpoint> repositories = checkpoint.repositories();
    for (int i = repositories.size() - 1; i >= 0; i--) {
      RepositoryCheckpoint repository = repositories.get(i);
      try {
        authorityFence.run();
        restore(repository, checkpoint.nestedRepositoriesOf(repository), authorityFence);
      } catch (RuntimeException restoreFailure) {
        if (failure == null) {
          failure =
              new IllegalStateException(
                  "Failed to restore one or more correction workspace checkpoints");
        }
        failure.addSuppressed(restoreFailure);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  /**
   * Idempotent single-repository rollback primitive for the durable workspace-generation saga.
   * Replaying this method after any individual Git mutation is safe; the final tree attestation is
   * the only success signal.
   */
  public void rollbackRepository(
      Checkpoint checkpoint, RepositoryCheckpoint repository, Runnable authorityFence) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(authorityFence, "authorityFence");
    if (!checkpoint.repositories().contains(repository)) {
      throw new IllegalArgumentException("CORRECTION_REPOSITORY_NOT_IN_CHECKPOINT");
    }
    authorityFence.run();
    restore(repository, checkpoint.nestedRepositoriesOf(repository), authorityFence);
  }

  /** Non-mutating exact-tree proof used to settle an APPLIED repository step after restart. */
  public String attestRepository(
      Checkpoint checkpoint, RepositoryCheckpoint repository, Runnable authorityFence) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(authorityFence, "authorityFence");
    if (!checkpoint.repositories().contains(repository)) {
      throw new IllegalArgumentException("CORRECTION_REPOSITORY_NOT_IN_CHECKPOINT");
    }
    authorityFence.run();
    assertHeadUnchanged(repository);
    return writeTree(
        repository.repository(), checkpoint.nestedRepositoriesOf(repository), authorityFence);
  }

  private RepositoryCheckpoint capture(Path repository, List<String> nestedRepositories) {
    String head = treeWriter.head(repository);
    String tree = writeTree(repository, nestedRepositories, () -> {});
    return new RepositoryCheckpoint(repository.toAbsolutePath().normalize(), head, tree);
  }

  /**
   * Computes the content-addressed working-tree object without a Git subprocess, temporary index,
   * or repository-controlled filter/helper execution. The live index remains untouched. Nested
   * repository paths are removed/excluded so the parent tree owns none of their bytes.
   */
  private String writeTree(
      Path repository, List<String> nestedRepositories, Runnable authorityFence) {
    return treeWriter.writeTree(repository, nestedRepositories, authorityFence);
  }

  private void restore(
      RepositoryCheckpoint checkpoint, List<String> nestedRepositories, Runnable authorityFence) {
    Path repository = checkpoint.repository();
    authorityFence.run();
    assertHeadUnchanged(checkpoint);
    authorityFence.run();
    commands.git(repository, "reset", "--hard", "--quiet", checkpoint.head());
    physicalMutations.afterMutation("RESET_HARD", repository);
    authorityFence.run();
    // Nested repositories are restored by their own checkpoint entries; the parent clean must
    // never delete them.
    List<String> clean = new ArrayList<>(List.of("clean", "-fd", "--quiet"));
    nestedRepositories.forEach(nested -> clean.add("--exclude=" + nested));
    commands.git(repository, clean.toArray(String[]::new));
    physicalMutations.afterMutation("CLEAN_UNTRACKED", repository);
    authorityFence.run();
    commands.git(repository, "read-tree", "--reset", "-u", checkpoint.tree());
    physicalMutations.afterMutation("READ_TREE", repository);
    authorityFence.run();
    commands.git(repository, "reset", "--mixed", "--quiet", checkpoint.head());
    physicalMutations.afterMutation("RESET_MIXED", repository);
    authorityFence.run();
    String restored = writeTree(repository, nestedRepositories, authorityFence);
    if (!checkpoint.tree().equals(restored)) {
      throw new IllegalStateException(
          "Correction rollback did not restore the checkpointed workspace tree for " + repository);
    }
  }

  private void assertHeadUnchanged(RepositoryCheckpoint checkpoint) {
    String current = commands.git(checkpoint.repository(), "rev-parse", "HEAD").trim();
    if (!checkpoint.head().equals(current)) {
      throw new IllegalStateException(
          "Correction changed repository HEAD; refusing to attest or roll back "
              + checkpoint.repository());
    }
  }

  /**
   * Discovers the workspace root repository (when Git-backed) PLUS every nested repository from a
   * depth-bounded walk, deduplicated in deterministic path order. Candidates inside another
   * repository's {@code .git/} metadata are skipped.
   */
  private List<Path> discoverRepositories(Path workspaceRoot) {
    if (workspaceRoot == null) {
      return List.of();
    }
    Path root = workspaceRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    TreeSet<Path> repositories = new TreeSet<>(Comparator.comparing(Path::toString));
    if (Files.exists(root.resolve(".git"))) {
      repositories.add(root);
    }
    try (Stream<Path> paths = Files.walk(root, 3)) {
      paths
          .filter(Files::isDirectory)
          .filter(
              path -> path.getFileName() != null && ".git".equals(path.getFileName().toString()))
          .map(Path::getParent)
          .filter(Objects::nonNull)
          .filter(parent -> !isInsideGitMetadata(root, parent))
          .forEach(repositories::add);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to discover correction workspace repositories", e);
    }
    if (repositories.size() > MAX_REPOSITORIES) {
      throw new IllegalStateException(
          "Automatic correction workspace exceeds the repository checkpoint limit");
    }
    return List.copyOf(repositories);
  }

  /** True when any path segment between the workspace root and the candidate is {@code .git}. */
  private static boolean isInsideGitMetadata(Path root, Path candidate) {
    Path current = candidate;
    while (current != null && !current.equals(root)) {
      Path name = current.getFileName();
      if (name != null && ".git".equals(name.toString())) {
        return true;
      }
      current = current.getParent();
    }
    return false;
  }

  /** Workspace-relative POSIX repository identity; {@code "."} for the workspace root itself. */
  private static String workspaceRelativePath(Path workspaceRoot, Path repository) {
    Path root = workspaceRoot.toAbsolutePath().normalize();
    Path repo = repository.toAbsolutePath().normalize();
    if (root.equals(repo)) {
      return ".";
    }
    return posix(root.relativize(repo));
  }

  /** Relative paths (from {@code repository}) of every other discovered repository beneath it. */
  private static List<String> nestedRepositoryPaths(List<Path> repositories, Path repository) {
    Path parent = repository.toAbsolutePath().normalize();
    return repositories.stream()
        .map(candidate -> candidate.toAbsolutePath().normalize())
        .filter(candidate -> !candidate.equals(parent) && candidate.startsWith(parent))
        .map(candidate -> posix(parent.relativize(candidate)))
        .sorted()
        .toList();
  }

  private static String posix(Path path) {
    return path.toString().replace('\\', '/');
  }

  public record Checkpoint(Path workspaceRoot, List<RepositoryCheckpoint> repositories) {
    public Checkpoint {
      Objects.requireNonNull(workspaceRoot, "workspaceRoot");
      workspaceRoot = workspaceRoot.toAbsolutePath().normalize();
      repositories = repositories == null ? List.of() : List.copyOf(repositories);
      if (repositories.isEmpty()) {
        throw new IllegalArgumentException("checkpoint requires at least one repository");
      }
    }

    public String generation() {
      List<String> trees = new ArrayList<>();
      for (RepositoryCheckpoint repository : repositories) {
        trees.add(
            workspaceRelativePath(workspaceRoot, repository.repository())
                + "="
                + repository.tree());
      }
      return org.apache.commons.codec.digest.DigestUtils.sha256Hex(
          GENERATION_PREIMAGE_VERSION + String.join("\n", trees));
    }

    List<String> nestedRepositoriesOf(RepositoryCheckpoint repository) {
      return nestedRepositoryPaths(
          repositories.stream().map(RepositoryCheckpoint::repository).toList(),
          repository.repository());
    }
  }

  public record Accepted(String beforeGeneration, String afterGeneration) {}

  public record DiffEvidence(
      List<String> changedFiles,
      String diffDigest,
      String unifiedPatch,
      int patchCapturedBytes,
      long patchFullBytes,
      boolean patchTruncated,
      boolean patchRedacted) {
    public DiffEvidence {
      changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
      unifiedPatch = unifiedPatch == null ? "" : unifiedPatch;
    }

    public DiffEvidence(List<String> changedFiles, String diffDigest) {
      this(changedFiles, diffDigest, "", 0, 0L, false, false);
    }
  }

  public record RepositoryCheckpoint(Path repository, String head, String tree) {}
}
