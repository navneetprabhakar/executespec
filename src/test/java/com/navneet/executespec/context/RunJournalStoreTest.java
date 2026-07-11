package com.navneet.executespec.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunJournalStoreTest {

  @TempDir Path workspace;

  @Test
  void savesAndReloadsOnlyTheBoundedLatestEntries() {
    List<String> entries = new ArrayList<>();
    for (int i = 0; i < RunJournalStore.MAX_ENTRIES + 3; i++) {
      entries.add("task-" + i + " (task) -> COMPLETE");
    }

    RunJournalStore.save(workspace, entries);

    assertThat(RunJournalStore.load(workspace))
        .containsExactlyElementsOf(entries.subList(3, entries.size()));
    assertThat(Files.isRegularFile(RunJournalStore.path(workspace))).isTrue();
  }

  @Test
  void malformedJournalFailsClosed() throws Exception {
    Path path = RunJournalStore.path(workspace);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "not-json");

    assertThat(RunJournalStore.load(workspace)).isEmpty();
  }
}
