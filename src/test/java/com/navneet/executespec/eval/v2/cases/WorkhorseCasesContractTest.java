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

  @Test
  void validatorsDoNotAwardCompletionForMissingOrMalformedArtifacts() throws Exception {
    WorkhorseEvalCase add = WorkhorseCases.all().get(0);
    Path addWorkspace = Files.createDirectory(tempDir.resolve("malformed-add"));
    Files.writeString(addWorkspace.resolve("calc.py"), "def add(a, b):\n    return a + b\n");
    assertThat(add.validate(addWorkspace, null).correctness()).isEqualTo(30);

    WorkhorseEvalCase create = WorkhorseCases.all().get(1);
    Path createWorkspace = Files.createDirectory(tempDir.resolve("malformed-create"));
    Files.writeString(
        createWorkspace.resolve("stringutils.py"), "def reverse(s): return s[::-1]\n");
    Files.writeString(createWorkspace.resolve("test_stringutils.py"), "reverse('abc') == 'cba'\n");
    assertThat(create.validate(createWorkspace, null).correctness()).isEqualTo(55);
  }

  @Test
  void fixValidatorRejectsMissingSourceEvenWhenOutcomeClaimsCompletion() throws Exception {
    WorkhorseEvalCase fix = WorkhorseCases.all().get(2);
    Path workspace = Files.createDirectory(tempDir.resolve("malformed-fix"));
    assertThat(fix.validate(workspace, null).correctness()).isZero();
  }
}
