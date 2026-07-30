package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.report.model.WorkflowConfigEvidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkflowConfigEvidenceInspectorTest {

  @TempDir Path workspace;

  private final WorkflowConfigEvidenceInspector inspector = new WorkflowConfigEvidenceInspector();

  @Test
  void persistsExactPathsDigestsAndParseResultsForBuildAndTestWorkflows() throws Exception {
    Path workflows = Files.createDirectories(workspace.resolve(".github/workflows"));
    Files.writeString(
        workflows.resolve("build.yml"),
        "name: build\non: [pull_request]\njobs:\n  build:\n    runs-on: ubuntu-latest\n");
    Files.writeString(
        workflows.resolve("test.yaml"),
        "name: test\non: [push]\njobs:\n  test:\n    runs-on: ubuntu-latest\n");
    Files.createDirectories(workspace.resolve("target/workflows"));
    Files.writeString(workspace.resolve("target/workflows/copied.yml"), "invalid: [");

    WorkflowConfigEvidence evidence = inspector.inspect(workspace);

    assertThat(evidence.status()).isEqualTo(WorkflowConfigEvidence.Status.PASSED);
    assertThat(evidence.files())
        .extracting(WorkflowConfigEvidence.FileEvidence::path)
        .containsExactly(".github/workflows/build.yml", ".github/workflows/test.yaml");
    assertThat(evidence.files())
        .allMatch(file -> file.contentSha256().matches("[0-9a-f]{64}"))
        .allMatch(file -> file.parseStatus() == WorkflowConfigEvidence.ParseStatus.VALID);
  }

  @Test
  void malformedWorkflowIsDeterministicFailureWithSourceLocation() throws Exception {
    Path workflows = Files.createDirectories(workspace.resolve(".github/workflows"));
    Files.writeString(workflows.resolve("deploy.yml"), "jobs: [");

    WorkflowConfigEvidence evidence = inspector.inspect(workspace);

    assertThat(evidence.status()).isEqualTo(WorkflowConfigEvidence.Status.FAILED);
    assertThat(evidence.files())
        .singleElement()
        .satisfies(
            file -> {
              assertThat(file.path()).isEqualTo(".github/workflows/deploy.yml");
              assertThat(file.parseStatus()).isEqualTo(WorkflowConfigEvidence.ParseStatus.INVALID);
              assertThat(file.diagnostic()).isNotBlank();
            });
  }

  @Test
  void nonWorkflowProjectIsExplicitlyNotApplicable() {
    assertThat(inspector.inspect(workspace).status())
        .isEqualTo(WorkflowConfigEvidence.Status.NOT_APPLICABLE);
  }
}
