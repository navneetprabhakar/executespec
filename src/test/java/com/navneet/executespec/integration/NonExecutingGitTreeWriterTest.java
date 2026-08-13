package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.revwalk.RevWalk;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NonExecutingGitTreeWriterTest {

  @Test
  void preparesDeterministicCommitWithoutHelpersIndexOrRefMutation(@TempDir Path workspace)
      throws Exception {
    Path tracked = workspace.resolve("tracked.txt");
    Files.writeString(tracked, "before\n");
    try (Git git = Git.init().setDirectory(workspace.toFile()).call()) {
      git.add().addFilepattern("tracked.txt").call();
      git.commit()
          .setAuthor("Test", "test@example.com")
          .setCommitter("Test", "test@example.com")
          .setMessage("base")
          .call();
    }

    Path helperMarker = workspace.resolve("filter-ran");
    Path hookMarker = workspace.resolve("hook-ran");
    Files.writeString(workspace.resolve(".gitattributes"), "*.txt filter=evil\n");
    try (Git git = Git.open(workspace.toFile())) {
      var config = git.getRepository().getConfig();
      config.setString(
          "filter", "evil", "clean", "sh -c 'printf ran > \"" + helperMarker + "\"; cat'");
      config.save();
      String head = git.getRepository().resolve(Constants.HEAD).name();
      String indexTree =
          git.getRepository()
              .readDirCache()
              .writeTree(git.getRepository().newObjectInserter())
              .name();
      Path hook = workspace.resolve(".git/hooks/pre-commit");
      Files.writeString(hook, "#!/bin/sh\nprintf ran > '" + hookMarker + "'\nexit 41\n");
      hook.toFile().setExecutable(true);
      Files.writeString(tracked, "after\n");

      NonExecutingGitTreeWriter writer = new NonExecutingGitTreeWriter();
      Instant timestamp = Instant.parse("2026-08-12T12:00:00Z");
      NonExecutingGitTreeWriter.PreparedCommit first =
          writer.prepareCommit(workspace, "prepared output", timestamp, () -> {});
      Path looseCommit =
          workspace
              .resolve(".git/objects")
              .resolve(first.commitId().substring(0, 2))
              .resolve(first.commitId().substring(2));
      assertThat(looseCommit).exists();
      Files.delete(looseCommit);
      NonExecutingGitTreeWriter.PreparedCommit replay =
          writer.prepareCommit(workspace, "prepared output", timestamp, () -> {});

      assertThat(first.hasChanges()).isTrue();
      assertThat(replay).isEqualTo(first);
      assertThat(looseCommit).exists();
      assertThat(first.parentCommitId()).isEqualTo(head);
      assertThat(git.getRepository().resolve(Constants.HEAD).name()).isEqualTo(head);
      assertThat(
              git.getRepository()
                  .readDirCache()
                  .writeTree(git.getRepository().newObjectInserter())
                  .name())
          .isEqualTo(indexTree);
      try (RevWalk walk = new RevWalk(git.getRepository())) {
        var commit = walk.parseCommit(git.getRepository().resolve(first.commitId()));
        assertThat(commit.getTree().getId().name()).isEqualTo(first.treeId());
        assertThat(commit.getParent(0).getId().name()).isEqualTo(head);
      }
    }
    assertThat(helperMarker).doesNotExist();
    assertThat(hookMarker).doesNotExist();
  }
}
