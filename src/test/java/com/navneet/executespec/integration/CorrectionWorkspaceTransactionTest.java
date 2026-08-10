package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.workspacegeneration.WorkspaceIgnoredArtifactGuard;

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
  void rollbackDoesNotDeletePreexistingIgnoredDependencyRoots() throws Exception {
    Path repository = repository("ignored-baseline");
    Files.writeString(repository.resolve(".gitignore"), "baseline-cache/\n");
    git(repository, "add", ".gitignore");
    git(repository, "commit", "-q", "-m", "ignore dependency cache");
    Files.createDirectories(repository.resolve("baseline-cache"));
    Files.writeString(repository.resolve("baseline-cache/dependency.bin"), "keep\n");
    var checkpoint = transaction.begin(repository);

    Files.writeString(repository.resolve("tracked.txt"), "rejected\n");
    transaction.rollback(checkpoint);

    assertThat(repository.resolve("baseline-cache/dependency.bin")).exists();
    assertThat(transaction.currentGeneration(checkpoint)).isEqualTo(checkpoint.generation());
  }

  @Test
  void buildGenerationGuardDeletesIgnoredDependencyRootsAfterMultiRepoRollback() throws Exception {
    Path root = repository("nested-ignored-baseline");
    Path nested = repository(Files.createDirectories(root.resolve("repos")), "api");
    git(root, "add", "repos/api");
    git(root, "commit", "-q", "-m", "record nested repository");
    Files.writeString(root.resolve(".gitignore"), "baseline-cache/\nscratch/\n");
    git(root, "add", ".gitignore");
    git(root, "commit", "-q", "-m", "ignore dependency cache");
    Files.createDirectories(root.resolve("baseline-cache"));
    Files.writeString(root.resolve("baseline-cache/dependency.bin"), "keep\n");
    Files.writeString(root.resolve("staged.txt"), "staged-before\n");
    git(root, "add", "staged.txt");
    Files.writeString(root.resolve("untracked.txt"), "untracked-before\n");
    var checkpoint = transaction.begin(root);
    WorkspaceIgnoredArtifactGuard ignored = new WorkspaceIgnoredArtifactGuard(new ObjectMapper());
    var baseline = ignored.prepare(checkpoint, () -> {});

    Files.writeString(root.resolve("tracked.txt"), "rejected\n");
    Files.writeString(root.resolve("staged.txt"), "rejected\n");
    Files.delete(root.resolve("untracked.txt"));
    Files.writeString(nested.resolve("tracked.txt"), "rejected\n");
    Files.createDirectories(root.resolve("scratch"));
    Files.writeString(root.resolve("scratch/rejected.bin"), "remove\n");
    transaction.rollback(checkpoint);
    ignored.cleanAttemptArtifacts(checkpoint, baseline.json(), () -> {});

    assertThat(root.resolve("baseline-cache")).doesNotExist();
    assertThat(transaction.currentGeneration(checkpoint)).isEqualTo(checkpoint.generation());
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
  void correctionDiffEvidenceIsDeterministicAndNamesChangedFiles() throws Exception {
    Path repository = repository("diff-evidence");
    var checkpoint = transaction.begin(repository);
    Files.writeString(repository.resolve("tracked.txt"), "corrected\n");
    Files.writeString(repository.resolve("added.txt"), "new\n");

    var first = transaction.diffEvidence(checkpoint);
    var second = transaction.diffEvidence(checkpoint);

    assertThat(first).isEqualTo(second);
    assertThat(first.changedFiles()).containsExactly("0:added.txt", "0:tracked.txt");
    assertThat(first.diffDigest()).hasSize(64);
    assertThat(first.unifiedPatch())
        .contains("### repository 0", "--- a/tracked.txt", "+++ b/tracked.txt", "+corrected");
    assertThat(first.patchCapturedBytes()).isPositive();
    assertThat(first.patchFullBytes()).isEqualTo(first.patchCapturedBytes());
    assertThat(first.patchTruncated()).isFalse();
    assertThat(first.patchRedacted()).isFalse();
  }

  @Test
  void correctionPatchIsBoundedWhileFullTreeDigestCoversBytesPastTheBoundary() throws Exception {
    Path repository = repository("bounded-diff");
    var checkpoint = transaction.begin(repository);
    String common = "x".repeat(CorrectionWorkspaceTransaction.MAX_PATCH_BYTES + 8_192);

    Files.writeString(repository.resolve("tracked.txt"), common + "first-tail\n");
    var first = transaction.diffEvidence(checkpoint);
    Files.writeString(repository.resolve("tracked.txt"), common + "second-tail\n");
    var second = transaction.diffEvidence(checkpoint);

    assertThat(first.patchCapturedBytes())
        .isLessThanOrEqualTo(CorrectionWorkspaceTransaction.MAX_PATCH_BYTES);
    assertThat(first.patchFullBytes()).isGreaterThan(first.patchCapturedBytes());
    assertThat(first.patchTruncated()).isTrue();
    assertThat(first.unifiedPatch()).doesNotContain("first-tail");
    assertThat(second.unifiedPatch()).doesNotContain("second-tail");
    assertThat(second.diffDigest()).isNotEqualTo(first.diffDigest());
  }

  @Test
  void correctionPatchRedactsSensitivePathsAndDoesNotInlineBinaryBytes() throws Exception {
    Path repository = repository("safe-diff");
    var checkpoint = transaction.begin(repository);
    Files.writeString(repository.resolve(".env"), "API_TOKEN=must-not-leak\n");
    Files.write(repository.resolve("image.bin"), new byte[] {0, 1, 2, 3, 4});

    var evidence = transaction.diffEvidence(checkpoint);

    assertThat(evidence.changedFiles()).contains("0:[REDACTED_SENSITIVE_PATH]", "0:image.bin");
    assertThat(evidence.patchRedacted()).isTrue();
    assertThat(evidence.unifiedPatch())
        .doesNotContain("must-not-leak", ".env")
        .contains("Binary files");
  }

  @Test
  void correctionDiffDigestChangesWhenSamePathsContainDifferentBytes() throws Exception {
    Path repository = repository("diff-content-evidence");
    var checkpoint = transaction.begin(repository);

    Files.writeString(repository.resolve("tracked.txt"), "first correction\n");
    var first = transaction.diffEvidence(checkpoint);
    Files.writeString(repository.resolve("tracked.txt"), "second correction\n");
    var second = transaction.diffEvidence(checkpoint);

    assertThat(first.changedFiles()).containsExactly("0:tracked.txt");
    assertThat(second.changedFiles()).containsExactly("0:tracked.txt");
    assertThat(second.diffDigest()).isNotEqualTo(first.diffDigest());
  }

  @Test
  void capturesEvidenceWithoutDisturbingPreStagedCallerState() throws Exception {
    Path repository = repository("staged");
    Files.writeString(repository.resolve("tracked.txt"), "staged state\n");
    git(repository, "add", "tracked.txt");

    var checkpoint = transaction.begin(repository);

    // Generation capture runs on a temporary index, so the caller's staged entry survives.
    assertThat(gitOutput(repository, "diff", "--cached", "--name-only")).contains("tracked.txt");
    Files.writeString(repository.resolve("tracked.txt"), "post-checkpoint edit\n");
    assertThat(transaction.currentGeneration(checkpoint)).isNotEqualTo(checkpoint.generation());
  }

  @Test
  void nestedRepositoryWorkingTreesAreFirstClassGenerationEvidence() throws Exception {
    Path root = repository("nested-workspace");
    Path app = repository(Files.createDirectories(root.resolve("repos")), "app");

    String baseline = transaction.captureGeneration(root);

    Files.writeString(app.resolve("service.txt"), "first uncommitted edit\n");
    String firstEdit = transaction.captureGeneration(root);
    Files.writeString(app.resolve("service.txt"), "second uncommitted edit\n");
    String secondEdit = transaction.captureGeneration(root);

    assertThat(firstEdit).isNotEqualTo(baseline);
    assertThat(secondEdit).isNotEqualTo(firstEdit);

    // A nested commit with byte-identical working trees must not move the generation
    // (commit-only auto-push previously flipped freshness to STALE through the parent gitlink).
    git(app, "add", "service.txt");
    git(app, "commit", "-q", "-m", "commit-only");
    assertThat(transaction.captureGeneration(root)).isEqualTo(secondEdit);
  }

  @Test
  void rollbackAndDiffEvidenceCoverNestedRepositories() throws Exception {
    Path root = repository("nested-rollback");
    Path app = repository(Files.createDirectories(root.resolve("repos")), "app");
    Files.writeString(app.resolve("tracked.txt"), "nested worker state\n");

    var checkpoint = transaction.begin(root);
    String before = checkpoint.generation();
    assertThat(checkpoint.repositories()).hasSize(2);

    Files.writeString(app.resolve("tracked.txt"), "regressive nested correction\n");
    Files.writeString(root.resolve("root-note.txt"), "root correction\n");

    var evidence = transaction.diffEvidence(checkpoint);
    assertThat(evidence.changedFiles())
        .containsExactlyInAnyOrder("0:root-note.txt", "1:tracked.txt");

    transaction.rollback(checkpoint);

    assertThat(Files.readString(app.resolve("tracked.txt"))).isEqualTo("nested worker state\n");
    assertThat(root.resolve("root-note.txt")).doesNotExist();
    assertThat(app.resolve(".git")).exists();
    assertThat(transaction.currentGeneration(checkpoint)).isEqualTo(before);
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

  @Test
  void fencedRollbackStopsBeforeNextGitMutationWhenAuthorityIsLost() throws Exception {
    Path repository = repository("fenced-rollback");
    var checkpoint = transaction.begin(repository);
    Files.writeString(repository.resolve("tracked.txt"), "correction still owned elsewhere\n");
    java.util.concurrent.atomic.AtomicInteger fences =
        new java.util.concurrent.atomic.AtomicInteger();

    assertThatThrownBy(
            () ->
                transaction.rollback(
                    checkpoint,
                    () -> {
                      if (fences.incrementAndGet() == 3) {
                        throw new IllegalStateException("lease lost");
                      }
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("restore one or more");

    assertThat(Files.readString(repository.resolve("tracked.txt")))
        .isEqualTo("correction still owned elsewhere\n");
    transaction.rollback(checkpoint);
    assertThat(Files.readString(repository.resolve("tracked.txt"))).isEqualTo("baseline\n");
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

  private static String gitOutput(Path repository, String... args) throws Exception {
    String[] command = new String[args.length + 1];
    command[0] = "git";
    System.arraycopy(args, 0, command, 1, args.length);
    Process process =
        new ProcessBuilder(command)
            .directory(repository.toFile())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor()).isZero();
    return output;
  }
}
