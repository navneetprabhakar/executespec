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
  void writesVersionedEnvelopeWithTimestampAndEntries() throws Exception {
    RunJournalStore.save(workspace, List.of("task-1 -> COMPLETE"));

    var root =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(Files.readString(RunJournalStore.path(workspace)));

    assertThat(root.path("schemaVersion").asInt()).isEqualTo(RunJournalStore.SCHEMA_VERSION);
    assertThat(root.path("updatedAt").asText()).isNotBlank();
    assertThat(root.path("entries")).hasSize(1);
    assertThat(root.path("entries").get(0).asText()).isEqualTo("task-1 -> COMPLETE");
  }

  @Test
  void readsLegacyBareArrayForWorkspaceUpgrade() throws Exception {
    Path path = RunJournalStore.path(workspace);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "[\"legacy task\"]");

    assertThat(RunJournalStore.load(workspace)).containsExactly("legacy task");
  }

  @Test
  void rejectsUnknownEnvelopeSchemaInsteadOfTreatingItAsJournal() throws Exception {
    Path path = RunJournalStore.path(workspace);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "{\"schemaVersion\":99,\"entries\":[\"stale\"]}");

    assertThat(RunJournalStore.load(workspace)).isEmpty();
  }

  @Test
  void malformedJournalFailsClosed() throws Exception {
    Path path = RunJournalStore.path(workspace);
    Files.createDirectories(path.getParent());
    Files.writeString(path, "not-json");

    assertThat(RunJournalStore.load(workspace)).isEmpty();
  }
}
