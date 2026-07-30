package com.navneet.executespec.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
    Objects.requireNonNull(checkpoint, "checkpoint");
    List<String> trees = new ArrayList<>();
    for (int i = 0; i < checkpoint.repositories().size(); i++) {
      RepositoryCheckpoint repository = checkpoint.repositories().get(i);
      assertHeadUnchanged(repository);
      trees.add(i + "=" + writeTree(repository.repository()));
    }
    return org.apache.commons.codec.digest.DigestUtils.sha256Hex(String.join("\n", trees));
  }

  /** Accepts the current workspace only when it differs from no uncommitted caller-owned index. */
  public Accepted accept(Checkpoint checkpoint) {
    String generation = currentGeneration(checkpoint);
    return new Accepted(checkpoint.generation(), generation);
  }

  /** Restores every repository to the exact pre-correction tree and original {@code HEAD}. */
  public void rollback(Checkpoint checkpoint) {
    Objects.requireNonNull(checkpoint, "checkpoint");
    RuntimeException failure = null;
    List<RepositoryCheckpoint> repositories = checkpoint.repositories();
    for (int i = repositories.size() - 1; i >= 0; i--) {
      try {
        restore(repositories.get(i));
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
    Path repository = checkpoint.repository();
    assertHeadUnchanged(checkpoint);
    git(repository, "reset", "--hard", "--quiet", checkpoint.head());
    git(repository, "clean", "-fd", "--quiet");
    git(repository, "read-tree", "--reset", "-u", checkpoint.tree());
    git(repository, "reset", "--mixed", "--quiet", checkpoint.head());
    String restored = writeTree(repository);
    if (!checkpoint.tree().equals(restored)) {
      throw new IllegalStateException(
          "Correction rollback did not restore the checkpointed workspace tree for " + repository);
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

  public record RepositoryCheckpoint(Path repository, String head, String tree) {}

  private record CommandResult(int exitCode, String output) {}
}
