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
