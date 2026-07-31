package com.navneet.executespec.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

/**
 * Creates a reversible, content-addressed checkpoint before an automatic correction mutates a run
 * workspace.
 *
 * <p>The checkpoint is a Git tree object produced with plumbing commands. It captures tracked and
 * untracked (but not ignored) files without moving {@code HEAD}, creating a branch, or copying the
 * repository. A rejected correction restores that exact tree while leaving the repository on its
 * original commit. The service rejects pre-staged work because resetting an unknown caller-owned
 * index would destroy authority that this transaction did not create.
 *
 * <p>Multi-repository project workspaces are supported by checkpointing every Git repository below
 * the run root. All repositories must checkpoint successfully before correction may begin.
 */
@Component
public class CorrectionWorkspaceTransaction {

  private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(30);
  private static final int MAX_OUTPUT_BYTES = 16_384;
  static final int MAX_PATCH_BYTES = 32_768;
  private static final int MAX_REPOSITORIES = 32;

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
        captured.add(capture(repository));
      }
      return new Checkpoint(List.copyOf(captured));
    } catch (RuntimeException failure) {
      for (int i = captured.size() - 1; i >= 0; i--) {
        try {
          restore(captured.get(i));
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
    for (int i = 0; i < checkpoint.repositories().size(); i++) {
      RepositoryCheckpoint repository = checkpoint.repositories().get(i);
      authorityFence.run();
      assertHeadUnchanged(repository);
      trees.add(i + "=" + writeTree(repository.repository(), authorityFence));
    }
    return org.apache.commons.codec.digest.DigestUtils.sha256Hex(String.join("\n", trees));
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
      String currentTree = writeTree(repository.repository(), authorityFence);
      contentAddressedTreePairs.add(i + "\t" + repository.tree() + "\t" + currentTree);
      authorityFence.run();
      String output =
          git(
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
        BinaryCommandResult patchResult =
            gitBytes(repository.repository(), MAX_PATCH_BYTES, args.toArray(String[]::new));
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
      try {
        authorityFence.run();
        restore(repositories.get(i), authorityFence);
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

  private RepositoryCheckpoint capture(Path repository) {
    requireCleanIndex(repository);
    String head = git(repository, "rev-parse", "HEAD").trim();
    String tree = writeTree(repository);
    return new RepositoryCheckpoint(repository.toAbsolutePath().normalize(), head, tree);
  }

  private String writeTree(Path repository) {
    requireCleanIndex(repository);
    boolean stagedByThisService = false;
    try {
      git(repository, "add", "-A");
      stagedByThisService = true;
      return git(repository, "write-tree").trim();
    } finally {
      if (stagedByThisService) {
        git(repository, "reset", "--mixed", "--quiet", "HEAD");
      }
    }
  }

  private void restore(RepositoryCheckpoint checkpoint) {
    restore(checkpoint, () -> {});
  }

  private void restore(RepositoryCheckpoint checkpoint, Runnable authorityFence) {
    Path repository = checkpoint.repository();
    authorityFence.run();
    assertHeadUnchanged(checkpoint);
    authorityFence.run();
    git(repository, "reset", "--hard", "--quiet", checkpoint.head());
    authorityFence.run();
    git(repository, "clean", "-fd", "--quiet");
    authorityFence.run();
    git(repository, "read-tree", "--reset", "-u", checkpoint.tree());
    authorityFence.run();
    git(repository, "reset", "--mixed", "--quiet", checkpoint.head());
    authorityFence.run();
    String restored = writeTree(repository, authorityFence);
    if (!checkpoint.tree().equals(restored)) {
      throw new IllegalStateException(
          "Correction rollback did not restore the checkpointed workspace tree for " + repository);
    }
  }

  private String writeTree(Path repository, Runnable authorityFence) {
    authorityFence.run();
    requireCleanIndex(repository);
    boolean stagedByThisService = false;
    try {
      authorityFence.run();
      git(repository, "add", "-A");
      stagedByThisService = true;
      authorityFence.run();
      return git(repository, "write-tree").trim();
    } finally {
      if (stagedByThisService) {
        authorityFence.run();
        git(repository, "reset", "--mixed", "--quiet", "HEAD");
      }
    }
  }

  private void assertHeadUnchanged(RepositoryCheckpoint checkpoint) {
    String current = git(checkpoint.repository(), "rev-parse", "HEAD").trim();
    if (!checkpoint.head().equals(current)) {
      throw new IllegalStateException(
          "Correction changed repository HEAD; refusing to attest or roll back "
              + checkpoint.repository());
    }
  }

  private void requireCleanIndex(Path repository) {
    CommandResult result = run(repository, "git", "diff", "--cached", "--quiet", "--exit-code");
    if (result.exitCode() == 0) {
      return;
    }
    if (result.exitCode() == 1) {
      throw new IllegalStateException(
          "Automatic correction cannot checkpoint a workspace with pre-staged changes: "
              + repository);
    }
    throw commandFailure(repository, result, "git diff --cached");
  }

  private List<Path> discoverRepositories(Path workspaceRoot) {
    if (workspaceRoot == null) {
      return List.of();
    }
    Path root = workspaceRoot.toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<Path> repositories = new ArrayList<>();
    if (Files.exists(root.resolve(".git"))) {
      repositories.add(root);
    } else {
      try (Stream<Path> paths = Files.walk(root, 3)) {
        paths
            .filter(Files::isDirectory)
            .filter(
                path -> path.getFileName() != null && ".git".equals(path.getFileName().toString()))
            .map(Path::getParent)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(Path::toString))
            .limit(MAX_REPOSITORIES + 1L)
            .forEach(repositories::add);
      } catch (IOException e) {
        throw new IllegalStateException("Failed to discover correction workspace repositories", e);
      }
    }
    if (repositories.size() > MAX_REPOSITORIES) {
      throw new IllegalStateException(
          "Automatic correction workspace exceeds the repository checkpoint limit");
    }
    return List.copyOf(repositories);
  }

  private String git(Path repository, String... args) {
    List<String> command = new ArrayList<>(args.length + 1);
    command.add("git");
    command.addAll(List.of(args));
    CommandResult result = run(repository, command.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw commandFailure(repository, result, String.join(" ", command));
    }
    return result.output();
  }

  private BinaryCommandResult gitBytes(Path repository, int captureLimit, String... args) {
    List<String> command = new ArrayList<>(args.length + 1);
    command.add("git");
    command.addAll(List.of(args));
    BinaryCommandResult result = runBytes(repository, captureLimit, command.toArray(String[]::new));
    if (result.exitCode() != 0) {
      throw new IllegalStateException(
          String.join(" ", command)
              + " failed in "
              + repository
              + " (exit="
              + result.exitCode()
              + ")");
    }
    return result;
  }

  private IllegalStateException commandFailure(
      Path repository, CommandResult result, String command) {
    return new IllegalStateException(
        command
            + " failed in "
            + repository
            + " (exit="
            + result.exitCode()
            + "): "
            + result.output());
  }

  private CommandResult run(Path repository, String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(repository.toFile())
              .redirectErrorStream(true)
              .start();
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var input = process.getInputStream()) {
                      input.transferTo(output);
                    } catch (IOException ignored) {
                      // Process exit/timeout remains the authoritative failure signal.
                    }
                  });
      boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        reader.join(Duration.ofSeconds(5));
        throw new IllegalStateException(
            String.join(" ", command) + " exceeded the correction checkpoint timeout");
      }
      reader.join(Duration.ofSeconds(5));
      byte[] bytes = output.toByteArray();
      int length = Math.min(bytes.length, MAX_OUTPUT_BYTES);
      return new CommandResult(
          process.exitValue(), new String(bytes, 0, length, StandardCharsets.UTF_8).trim());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Correction checkpoint command was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Correction checkpoint command could not start", e);
    }
  }

  private BinaryCommandResult runBytes(Path repository, int captureLimit, String... command) {
    try {
      Process process =
          new ProcessBuilder(command)
              .directory(repository.toFile())
              .redirectErrorStream(true)
              .start();
      BoundedCountingOutputStream output = new BoundedCountingOutputStream(captureLimit);
      Thread reader =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (var input = process.getInputStream()) {
                      input.transferTo(output);
                    } catch (IOException ignored) {
                      // Process exit/timeout remains the authoritative failure signal.
                    }
                  });
      boolean completed = process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
      if (!completed) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
        reader.join(Duration.ofSeconds(5));
        throw new IllegalStateException(
            String.join(" ", command) + " exceeded the correction checkpoint timeout");
      }
      reader.join(Duration.ofSeconds(5));
      return new BinaryCommandResult(process.exitValue(), output.captured(), output.count());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Correction checkpoint command was interrupted", e);
    } catch (IOException e) {
      throw new IllegalStateException("Correction checkpoint command could not start", e);
    }
  }

  public record Checkpoint(List<RepositoryCheckpoint> repositories) {
    public Checkpoint {
      repositories = repositories == null ? List.of() : List.copyOf(repositories);
      if (repositories.isEmpty()) {
        throw new IllegalArgumentException("checkpoint requires at least one repository");
      }
    }

    public String generation() {
      List<String> trees = new ArrayList<>();
      for (int i = 0; i < repositories.size(); i++) {
        trees.add(i + "=" + repositories.get(i).tree());
      }
      return org.apache.commons.codec.digest.DigestUtils.sha256Hex(String.join("\n", trees));
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

  private record CommandResult(int exitCode, String output) {}

  private record BinaryCommandResult(int exitCode, byte[] output, long fullOutputBytes) {}

  private static final class BoundedCountingOutputStream extends OutputStream {
    private final ByteArrayOutputStream captured;
    private final int limit;
    private long count;

    private BoundedCountingOutputStream(int limit) {
      this.limit = Math.max(0, limit);
      this.captured = new ByteArrayOutputStream(this.limit);
    }

    @Override
    public void write(int value) {
      count++;
      if (captured.size() < limit) captured.write(value);
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      count += length;
      int remaining = limit - captured.size();
      if (remaining > 0) captured.write(bytes, offset, Math.min(remaining, length));
    }

    private byte[] captured() {
      return captured.toByteArray();
    }

    private long count() {
      return count;
    }
  }
}
