package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorrectionWorkspaceTransactionTest {

  @TempDir Path tempDir;

  private final CorrectionWorkspaceTransaction transaction = new CorrectionWorkspaceTransaction();

  @Test
  void rollbackRestoresTrackedAndUntrackedFilesExactly() throws Exception {
    Path repository = repository("single");
    Files.writeString(repository.resolve("tracked.txt"), "worker state\n");
    Files.writeString(repository.resolve("worker-new.txt"), "worker output\n");

    var checkpoint = transaction.begin(repository);
    String beforeGeneration = checkpoint.generation();

    Files.writeString(repository.resolve("tracked.txt"), "regressive correction\n");
    Files.delete(repository.resolve("worker-new.txt"));
    Files.writeString(repository.resolve("correction-new.txt"), "must disappear\n");

    transaction.rollback(checkpoint);

    assertThat(Files.readString(repository.resolve("tracked.txt"))).isEqualTo("worker state\n");
    assertThat(Files.readString(repository.resolve("worker-new.txt"))).isEqualTo("worker output\n");
    assertThat(repository.resolve("correction-new.txt")).doesNotExist();
    assertThat(transaction.currentGeneration(checkpoint)).isEqualTo(beforeGeneration);
  }

  @Test
  void acceptedCorrectionReturnsDifferentContentAddressedGeneration() throws Exception {
    Path repository = repository("accepted");
    Files.writeString(repository.resolve("tracked.txt"), "worker state\n");
    var checkpoint = transaction.begin(repository);

    Files.writeString(repository.resolve("tracked.txt"), "verified correction\n");

    var accepted = transaction.accept(checkpoint);

    assertThat(accepted.beforeGeneration()).isEqualTo(checkpoint.generation());
    assertThat(accepted.afterGeneration()).isNotEqualTo(accepted.beforeGeneration());
    assertThat(Files.readString(repository.resolve("tracked.txt")))
        .isEqualTo("verified correction\n");
  }

  @Test
  void refusesToDestroyPreStagedCallerState() throws Exception {
    Path repository = repository("staged");
    Files.writeString(repository.resolve("tracked.txt"), "staged state\n");
    git(repository, "add", "tracked.txt");

    assertThatThrownBy(() -> transaction.begin(repository))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pre-staged");
  }

  @Test
  void checkpointsAndRollsBackEveryRepositoryInProjectWorkspace() throws Exception {
    Path root = Files.createDirectories(tempDir.resolve("workspace/repos"));
    Path api = repository(root, "api");
    Path web = repository(root, "web");
    Files.writeString(api.resolve("tracked.txt"), "api worker\n");
    Files.writeString(web.resolve("tracked.txt"), "web worker\n");

    var checkpoint = transaction.begin(tempDir.resolve("workspace"));
    Files.writeString(api.resolve("tracked.txt"), "api correction\n");
    Files.writeString(web.resolve("new.txt"), "web correction\n");

    transaction.rollback(checkpoint);

    assertThat(Files.readString(api.resolve("tracked.txt"))).isEqualTo("api worker\n");
    assertThat(web.resolve("new.txt")).doesNotExist();
    assertThat(checkpoint.repositories()).hasSize(2);
  }

  private Path repository(String name) throws Exception {
    return repository(tempDir, name);
  }

  private Path repository(Path parent, String name) throws Exception {
    Path repository = Files.createDirectories(parent.resolve(name));
    git(repository, "init", "-q");
    git(repository, "config", "user.email", "test@executespec.invalid");
    git(repository, "config", "user.name", "ExecuteSpec Test");
    Files.writeString(repository.resolve("tracked.txt"), "baseline\n");
    git(repository, "add", "tracked.txt");
    git(repository, "commit", "-q", "-m", "baseline");
    return repository;
  }

  private static void git(Path repository, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process = new ProcessBuilder(command).directory(repository.toFile()).start();
    assertThat(process.waitFor()).isZero();
  }
}
