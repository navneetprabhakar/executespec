package com.navneet.executespec.eval.v2.cases;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.navneet.executespec.eval.v2.WorkhorseEvalCase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic contract for the provider-free three-case repair corpus. */
class WorkhorseCasesContractTest {

  @TempDir Path tempDir;

  @Test
  void corpusHasStableDefectClassesAndSeededWorkspaces() throws Exception {
    List<WorkhorseEvalCase> cases = WorkhorseCases.all();

    assertThat(cases)
        .extracting(WorkhorseEvalCase::name)
        .containsExactly(
            "workhorse-add-function", "workhorse-multi-file-create", "workhorse-fix-bug");
    assertThat(cases).allSatisfy(testCase -> assertThat(testCase.taskPrompt()).isNotBlank());

    for (WorkhorseEvalCase testCase : cases) {
      Path workspace = Files.createDirectory(tempDir.resolve(testCase.name()));
      testCase.seedWorkspace(workspace);
      try (var files = Files.list(workspace)) {
        assertThat(files.toList()).as("seeded workspace for %s", testCase.name()).isNotNull();
      }
    }
  }
}
