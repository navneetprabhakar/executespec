package com.navneet.executespec.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.navneet.executespec.config.AgentProperties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ArtifactPublicationGuardTest {

  @TempDir Path temp;

  @Test
  void blocksShellOrLocalHandsCreatedUntrackedSecretBeforePushWithoutDisclosingValue()
      throws Exception {
    Fixture fixture = fixture("run-shell");
    String secret = "github_pat_" + "A1".repeat(25);
    Files.writeString(fixture.repo().resolve("generated.txt"), "token=" + secret);

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-shell"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("GITHUB_TOKEN")
        .hasMessageContaining("generated.txt")
        .hasMessageNotContaining(secret);
  }

  @Test
  void scansPreExistingTrackedFilesRatherThanOnlyTheFinalDiff() throws Exception {
    Fixture fixture = fixture("run-existing");
    Files.writeString(fixture.repo().resolve("legacy.conf"), "AWS=" + "AKIA" + "A".repeat(16));
    git(fixture.repo(), "add", "legacy.conf");

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-existing"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("AWS_ACCESS_KEY")
        .hasMessageContaining("legacy.conf");
  }

  @Test
  void blocksCredentialInArtifactPathWithoutEchoingThePath() throws Exception {
    Fixture fixture = fixture("run-secret-path");
    String secret = "es_pat_" + "A".repeat(32);
    Files.writeString(fixture.repo().resolve(secret), "ordinary content");

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-secret-path"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("EXECUTESPEC_PAT")
        .hasMessageContaining("credential-like artifact path")
        .hasMessageNotContaining(secret);
  }

  @Test
  void permitsOrdinaryTrackedAndGeneratedFiles() throws Exception {
    Fixture fixture = fixture("run-safe");
    Files.writeString(
        fixture.repo().resolve("app.js"), "const token = process.env.GITHUB_TOKEN;\n");
    git(fixture.repo(), "add", "app.js");

    fixture.guard().assertGitWorkspaceSafe("run-safe");
  }

  @Test
  void blocksSecretInsidePreviouslyArchivedWorkspaceZip() throws Exception {
    Fixture fixture = fixture("run-zip");
    String secret = "es_pat_" + "A".repeat(32);
    Path archive = temp.resolve("workspace.zip");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
      zip.putNextEntry(new ZipEntry("src/config.txt"));
      zip.write(("TOKEN=" + secret).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }

    assertThatThrownBy(() -> fixture.guard().assertZipSafe(archive))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("EXECUTESPEC_PAT")
        .hasMessageContaining("src/config.txt")
        .hasMessageNotContaining(secret);
  }

  @Test
  void archiveScanFailsClosedOnEntryAndInflatedByteLimits() throws Exception {
    Fixture fixture = fixture("run-zip-limits");
    Path entriesArchive = temp.resolve("many-entries.zip");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(entriesArchive))) {
      for (int i = 0; i < 3; i++) {
        zip.putNextEntry(new ZipEntry("file-" + i));
        zip.write('x');
        zip.closeEntry();
      }
    }
    assertThatThrownBy(() -> fixture.guard().assertZipSafe(entriesArchive, 2, 100, 100))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("ARCHIVE_ENTRY_LIMIT_EXCEEDED");

    Path inflatedArchive = temp.resolve("inflated.zip");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(inflatedArchive))) {
      zip.putNextEntry(new ZipEntry("large.txt"));
      zip.write("ordinary-content".repeat(20).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
    }
    assertThatThrownBy(() -> fixture.guard().assertZipSafe(inflatedArchive, 10, 64, 128))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("ARCHIVE_MEMBER_TOO_LARGE");
  }

  @Test
  void archiveScanRejectsTraversalAndAbsoluteMemberNames() throws Exception {
    Fixture fixture = fixture("run-zip-paths");
    for (String unsafeName : new String[] {"../escape.txt", "/absolute.txt", "C:/windows.txt"}) {
      Path archive = temp.resolve(Integer.toHexString(unsafeName.hashCode()) + ".zip");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
        zip.putNextEntry(new ZipEntry(unsafeName));
        zip.write("ordinary".getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      assertThatThrownBy(() -> fixture.guard().assertZipSafe(archive))
          .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
          .hasMessageContaining("ARCHIVE_PATH_TRAVERSAL")
          .hasMessageNotContaining(unsafeName);
    }
  }

  @Test
  void gitlinkIsScannedAsPublishedMetadataWithoutFollowingSubmoduleContents() throws Exception {
    Fixture fixture = fixture("run-gitlink");
    Files.writeString(fixture.repo().resolve("README.md"), "safe");
    git(fixture.repo(), "add", "README.md");
    git(
        fixture.repo(),
        "-c",
        "user.name=Test",
        "-c",
        "user.email=test@example.com",
        "commit",
        "-qm",
        "base");
    String commit = gitOutput(fixture.repo(), "rev-parse", "HEAD").trim();
    Files.createDirectories(fixture.repo().resolve("vendor/submodule"));
    git(
        fixture.repo(),
        "update-index",
        "--add",
        "--cacheinfo",
        "160000," + commit + ",vendor/submodule");

    fixture.guard().assertGitWorkspaceSafe("run-gitlink");
  }

  /**
   * RR-080 — the staged blob is what an outgoing commit publishes. Scanning only the worktree let a
   * secret be staged and then replaced on disk with harmless bytes.
   */
  @Test
  void blocksSecretStagedThenOverwrittenInTheWorktree() throws Exception {
    Fixture fixture = fixture("run-staged-overwritten");
    String secret = "es_pat_" + "A".repeat(32);
    Path file = fixture.repo().resolve("config.txt");
    Files.writeString(file, "TOKEN=" + secret);
    git(fixture.repo(), "add", "config.txt");
    Files.writeString(file, "TOKEN=redacted");

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-staged-overwritten"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("EXECUTESPEC_PAT")
        .hasMessageContaining("config.txt")
        .hasMessageNotContaining(secret);
  }

  /** RR-080 — deleting the worktree copy does not remove the staged blob from the outgoing tree. */
  @Test
  void blocksSecretStagedThenDeletedFromTheWorktree() throws Exception {
    Fixture fixture = fixture("run-staged-deleted");
    String secret = "es_pat_" + "B".repeat(32);
    Path file = fixture.repo().resolve("secrets.txt");
    Files.writeString(file, "TOKEN=" + secret);
    git(fixture.repo(), "add", "secrets.txt");
    Files.delete(file);

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-staged-deleted"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("EXECUTESPEC_PAT")
        .hasMessageContaining("secrets.txt")
        .hasMessageNotContaining(secret);
  }

  /** RR-080 — untracked files have no indexed blob, so the worktree bytes remain the content. */
  @Test
  void blocksSecretInUntrackedFileThatHasNoIndexedBlob() throws Exception {
    Fixture fixture = fixture("run-untracked-secret");
    String secret = "es_pat_" + "C".repeat(32);
    Files.writeString(fixture.repo().resolve("scratch.txt"), "TOKEN=" + secret);

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-untracked-secret"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("EXECUTESPEC_PAT")
        .hasMessageContaining("scratch.txt")
        .hasMessageNotContaining(secret);
  }

  /** RR-080 — a clean staged tree with a clean worktree still publishes. */
  @Test
  void permitsCleanStagedContentAlongsideCleanWorktree() throws Exception {
    Fixture fixture = fixture("run-staged-clean");
    Path file = fixture.repo().resolve("notes.md");
    Files.writeString(file, "first revision\n");
    git(fixture.repo(), "add", "notes.md");
    Files.writeString(file, "second revision\n");

    fixture.guard().assertGitWorkspaceSafe("run-staged-clean");
  }

  /**
   * RR-081 — Unix Git paths are arbitrary bytes. A tracked entry whose name is not valid UTF-8 is
   * still scanned by object id; an untracked one has no blob to fall back on and is refused rather
   * than silently skipped as "does not exist on disk".
   */
  @Test
  void undecodableGitPathBytesAreParsedAsBytesAndNeverSilentlySkipped() {
    byte[] invalid = {'b', 'a', 'd', (byte) 0xff, '.', 't', 'x', 't'};

    byte[] untracked = invalid.clone();
    ArtifactPublicationGuard.GitCandidate untrackedCandidate =
        ArtifactPublicationGuard.parseGitCandidate(untracked);
    assertThat(untrackedCandidate.pathDecodable()).isFalse();
    assertThat(untrackedCandidate.objectId()).isNull();
    assertThat(untrackedCandidate.label()).isEqualTo("(undecodable git path)");

    String oid = "0".repeat(40);
    byte[] prefix = ("100644 " + oid + " 0\t").getBytes(StandardCharsets.UTF_8);
    byte[] tracked = new byte[prefix.length + invalid.length];
    System.arraycopy(prefix, 0, tracked, 0, prefix.length);
    System.arraycopy(invalid, 0, tracked, prefix.length, invalid.length);
    ArtifactPublicationGuard.GitCandidate trackedCandidate =
        ArtifactPublicationGuard.parseGitCandidate(tracked);
    assertThat(trackedCandidate.pathDecodable()).isFalse();
    assertThat(trackedCandidate.objectId()).isEqualTo(oid);
    assertThat(trackedCandidate.gitlink()).isFalse();

    ArtifactPublicationGuard.GitCandidate ordinary =
        ArtifactPublicationGuard.parseGitCandidate(
            ("100644 " + oid + " 0\tsrc/app.ts").getBytes(StandardCharsets.UTF_8));
    assertThat(ordinary.pathDecodable()).isTrue();
    assertThat(ordinary.relativePath()).isEqualTo("src/app.ts");
    assertThat(ordinary.label()).isEqualTo("src/app.ts");
  }

  @Test
  void nestedRepositoryCannotEscapeRunWorkspace() throws Exception {
    Fixture fixture = fixture("run-escape");

    assertThatThrownBy(() -> fixture.guard().assertGitWorkspaceSafe("run-escape", "../../outside"))
        .isInstanceOf(ArtifactPublicationGuard.PublicationBlockedException.class)
        .hasMessageContaining("INVALID_WORKSPACE");
  }

  private Fixture fixture(String runId) throws Exception {
    Path repo = temp.resolve(runId);
    Files.createDirectories(repo);
    git(repo, "init", "-q");
    AgentProperties properties = new AgentProperties();
    properties.setWorkspace(temp.toString());
    return new Fixture(
        repo, new ArtifactPublicationGuard(properties, new GeneratedSecretScanner()));
  }

  private static void git(Path repo, String... args) throws Exception {
    String output = gitOutput(repo, args);
    assertThat(output).doesNotStartWith("EXIT=");
  }

  private static String gitOutput(Path repo, String... args) throws Exception {
    String[] withRepo = new String[args.length + 3];
    withRepo[0] = "git";
    withRepo[1] = "-C";
    withRepo[2] = repo.toString();
    System.arraycopy(args, 0, withRepo, 3, args.length);
    Process process = new ProcessBuilder(withRepo).redirectErrorStream(true).start();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    process.getInputStream().transferTo(output);
    int exit = process.waitFor();
    if (exit != 0) return "EXIT=" + exit + " " + output.toString(StandardCharsets.UTF_8);
    return output.toString(StandardCharsets.UTF_8);
  }

  private record Fixture(Path repo, ArtifactPublicationGuard guard) {}
}
