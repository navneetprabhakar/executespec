package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContextEvidenceBundleWriterTest {

  @TempDir Path tempDir;

  @Test
  void writesDeduplicatedBoundedBundleAndReadsItWhenSourcesAreCurrent() throws Exception {
    Path contextDir = tempDir.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "# Context\n");
    Files.writeString(contextDir.resolve("decisions.md"), "# Decisions\n");
    var input =
        new ContextAgentInput(
            "# Context\n",
            "M src/A.java",
            List.of("com.example", "com.example"),
            List.of("A", "A"),
            List.of("worker summary", "worker summary"),
            "PASS",
            "recent",
            "run-1",
            "ship it",
            "try the narrow seam",
            List.of("src/A.java"),
            List.of("build failed: missing import"),
            List.of("Do not repeat broad refactor"));

    var writer = new ContextEvidenceBundleWriter(new ObjectMapper());
    Path written = writer.write(tempDir, input, "run-1");

    // The writer now resolves through the shared containment guard, so the returned path is the
    // canonical (real) one; compare canonically rather than lexically.
    assertThat(written.toRealPath())
        .isEqualTo(contextDir.resolve(ContextEvidenceBundleWriter.FILE_NAME).toRealPath());
    var bundle = writer.readCurrent(tempDir, "run-1").orElseThrow();
    assertThat(bundle.path("schemaVersion").asInt()).isEqualTo(1);
    assertThat(bundle.path("evidence").path("newPackages")).hasSize(1);
    assertThat(bundle.path("evidence").path("newFiles")).hasSize(1);
    assertThat(bundle.path("sources").path("contextDocumentSha256").asText()).hasSize(64);
    assertThat(bundle.path("handoff").path("goal").asText()).isEqualTo("ship it");
    assertThat(bundle.path("handoff").path("hypothesis").asText()).isEqualTo("try the narrow seam");
    assertThat(bundle.path("handoff").path("changedFiles").get(0).asText()).isEqualTo("src/A.java");
    assertThat(bundle.path("handoff").path("failures").get(0).asText())
        .isEqualTo("build failed: missing import");
    assertThat(bundle.path("handoff").path("doNotRepeat").get(0).asText())
        .isEqualTo("Do not repeat broad refactor");
  }

  @Test
  void rejectsStaleBundleWhenAContextSourceChanges() throws Exception {
    Path contextDir = tempDir.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "old");
    Files.writeString(contextDir.resolve("decisions.md"), "decisions");
    var input =
        new ContextAgentInput(
            "old", "diff", List.of(), List.of(), List.of(), "", "", "run-1", "goal");
    var writer = new ContextEvidenceBundleWriter(new ObjectMapper());

    writer.write(tempDir, input, "run-1");
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "new");

    assertThat(writer.readCurrent(tempDir, "run-1")).isEmpty();
  }

  @Test
  void rejectsBundleWhenEitherFingerprintSourceIsMissing() throws Exception {
    Path contextDir = tempDir.resolve(".agent-context");
    Files.createDirectories(contextDir);
    Files.writeString(contextDir.resolve("CODEBASE_CONTEXT.md"), "context");
    Files.writeString(contextDir.resolve("decisions.md"), "decisions");
    var input =
        new ContextAgentInput(
            "context", "diff", List.of(), List.of(), List.of(), "", "", "run-1", "goal");
    var writer = new ContextEvidenceBundleWriter(new ObjectMapper());

    writer.write(tempDir, input, "run-1");
    Files.delete(contextDir.resolve("decisions.md"));

    assertThat(writer.readCurrent(tempDir, "run-1")).isEmpty();
  }

  @Test
  void rejectsBundleWhenRepositoryHeadMovesEvenIfContextSourcesAreUnchanged() throws Exception {
    try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
      git.getRepository().getConfig().setString("user", null, "name", "test");
      git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
      Files.createDirectories(tempDir.resolve(".agent-context"));
      Files.writeString(tempDir.resolve(".agent-context/CODEBASE_CONTEXT.md"), "context");
      Files.writeString(tempDir.resolve(".agent-context/decisions.md"), "decisions");
      Files.writeString(tempDir.resolve("tracked.txt"), "one");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("initial")
          .setAuthor(new PersonIdent("test", "test@example.com"))
          .call();

      var writer = new ContextEvidenceBundleWriter(new ObjectMapper());
      writer.write(
          tempDir,
          new ContextAgentInput(
              "context", "diff", List.of(), List.of(), List.of(), "", "", "run-1", "goal"),
          "run-1");
      assertThat(writer.readCurrent(tempDir, "run-1")).isPresent();

      Files.writeString(tempDir.resolve("tracked.txt"), "two");
      git.add().addFilepattern("tracked.txt").call();
      git.commit()
          .setMessage("move head")
          .setAuthor(new PersonIdent("test", "test@example.com"))
          .call();

      assertThat(writer.readCurrent(tempDir, "run-1")).isEmpty();
    }
  }

  @Test
  void rejectsBundleWhenWorkingTreeChangesWithoutHeadMoving() throws Exception {
    try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
      git.getRepository().getConfig().setString("user", null, "name", "test");
      git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
      Files.createDirectories(tempDir.resolve(".agent-context"));
      Files.writeString(tempDir.resolve(".agent-context/CODEBASE_CONTEXT.md"), "context");
      Files.writeString(tempDir.resolve(".agent-context/decisions.md"), "decisions");
      Files.writeString(tempDir.resolve("tracked.txt"), "one");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("initial")
          .setAuthor(new PersonIdent("test", "test@example.com"))
          .call();

      var writer = new ContextEvidenceBundleWriter(new ObjectMapper());
      writer.write(
          tempDir,
          new ContextAgentInput(
              "context", "diff", List.of(), List.of(), List.of(), "", "", "run-1", "goal"),
          "run-1");
      assertThat(writer.readCurrent(tempDir, "run-1")).isPresent();

      Files.writeString(tempDir.resolve("tracked.txt"), "two");
      assertThat(ContextEvidenceBundleWriter.resolveRepoHeadSha(tempDir)).isNotNull();
      assertThat(writer.readCurrent(tempDir, "run-1")).isEmpty();
    }
  }

  // RR-1268: an ordinary dirty tree is fully fingerprinted, so the fingerprint is COMPLETE and the
  // bundle stays reusable.
  @Test
  void repoStateFingerprintIsCompleteForOrdinaryDirtyTree() throws Exception {
    try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
      git.getRepository().getConfig().setString("user", null, "name", "test");
      git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
      Files.writeString(tempDir.resolve("changed.txt"), "hello world");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("initial")
          .setAuthor(new PersonIdent("test", "test@example.com"))
          .call();
      Files.writeString(tempDir.resolve("changed.txt"), "edited");
      Files.writeString(tempDir.resolve("untracked.txt"), "new file");

      ContextEvidenceBundleWriter.RepoStateFingerprint fp =
          ContextEvidenceBundleWriter.resolveRepoState(tempDir);
      assertThat(fp).isNotNull();
      assertThat(fp.complete()).isTrue();
      assertThat(fp.sha()).isNotBlank();
    }
  }

  // RR-1268: a bundle whose dirty-state fingerprint was recorded INCOMPLETE (a read budget tripped)
  // must fail authority on read rather than silently degrade to a HEAD-only guarantee.
  @Test
  void rejectsBundleWhoseRepoStateFingerprintIsRecordedIncomplete() throws Exception {
    try (Git git = Git.init().setDirectory(tempDir.toFile()).call()) {
      git.getRepository().getConfig().setString("user", null, "name", "test");
      git.getRepository().getConfig().setString("user", null, "email", "test@example.com");
      Files.createDirectories(tempDir.resolve(".agent-context"));
      Files.writeString(tempDir.resolve(".agent-context/CODEBASE_CONTEXT.md"), "context");
      Files.writeString(tempDir.resolve(".agent-context/decisions.md"), "decisions");
      git.add().addFilepattern(".").call();
      git.commit()
          .setMessage("initial")
          .setAuthor(new PersonIdent("test", "test@example.com"))
          .call();

      ObjectMapper mapper = new ObjectMapper();
      var writer = new ContextEvidenceBundleWriter(mapper);
      writer.write(
          tempDir,
          new ContextAgentInput(
              "context", "diff", List.of(), List.of(), List.of(), "", "", "run-1", "goal"),
          "run-1");
      assertThat(writer.readCurrent(tempDir, "run-1")).isPresent();

      Path bundle = tempDir.resolve(".agent-context/evidence-bundle.json");
      com.fasterxml.jackson.databind.node.ObjectNode root =
          (com.fasterxml.jackson.databind.node.ObjectNode)
              mapper.readTree(Files.readString(bundle));
      ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("sources"))
          .put("repoStateComplete", false);
      Files.writeString(bundle, mapper.writeValueAsString(root));

      assertThat(writer.readCurrent(tempDir, "run-1")).isEmpty();
    }
  }
}
