package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.navneet.executespec.engine.EngineOutcome;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunPipelineSupportTest {

  @TempDir Path workspace;

  @Test
  void listWorkspaceFilesPrunesIgnoredSubtreesAndDotDirs() throws Exception {
    // RR-404 — node_modules / target / .git and dot-dirs are pruned at the directory boundary; only
    // real deliverable files (plus .env.example) are returned.
    Files.createDirectories(workspace.resolve("src"));
    Files.writeString(workspace.resolve("src/App.java"), "class App {}");
    Files.writeString(workspace.resolve(".env.example"), "KEY=");
    Files.createDirectories(workspace.resolve("node_modules/pkg"));
    Files.writeString(workspace.resolve("node_modules/pkg/index.js"), "x");
    Files.createDirectories(workspace.resolve("target/classes"));
    Files.writeString(workspace.resolve("target/classes/App.class"), "x");
    Files.createDirectories(workspace.resolve(".git"));
    Files.writeString(workspace.resolve(".git/config"), "x");

    var files = RunPipelineSupport.listWorkspaceFiles(workspace);

    assertThat(files).contains("src/App.java", ".env.example");
    assertThat(files).noneMatch(p -> p.contains("node_modules"));
    assertThat(files).noneMatch(p -> p.startsWith("target"));
    assertThat(files).noneMatch(p -> p.contains(".git"));
  }

  @Test
  void findingPatchStoppedIsNotPublishableAndIsFlaggedUnverified() {
    // RR-1567 — a finding PATCH that ended STOPPED_WITHOUT_COMPLETE never faced the HARD_GATE
    // verification check, so it is neither publishable nor a clean completion.
    assertThat(
            RunPipelineSupport.patchResultPublishable(EngineOutcome.STOPPED, "finding:PR_REVIEW"))
        .isFalse();
    assertThat(
            RunPipelineSupport.patchStoppedUnverified(EngineOutcome.STOPPED, "finding:PR_REVIEW"))
        .isTrue();
  }

  @Test
  void findingPatchCompletedIsPublishable() {
    // RR-1567 — a genuine completeTask (verified under the gate) may be pushed; a stagnation-forced
    // terminal with writes present is also publishable.
    assertThat(
            RunPipelineSupport.patchResultPublishable(EngineOutcome.COMPLETED, "finding:TECH_DEBT"))
        .isTrue();
    assertThat(
            RunPipelineSupport.patchResultPublishable(EngineOutcome.STAGNATED, "finding:TECH_DEBT"))
        .isTrue();
    assertThat(RunPipelineSupport.patchStoppedUnverified(EngineOutcome.COMPLETED, "finding:X"))
        .isFalse();
  }

  @Test
  void nonFindingSourcesAreNotGovernedByThePublicationGate() {
    // RR-1567 — benchmark PATCH runs never reach the auto-push block; the gate returns true so it
    // does not change their behaviour, and a bench STOPPED is not flagged unverified here.
    assertThat(RunPipelineSupport.patchResultPublishable(EngineOutcome.STOPPED, "benchmark"))
        .isTrue();
    assertThat(RunPipelineSupport.patchResultPublishable(EngineOutcome.STOPPED, null)).isTrue();
    assertThat(RunPipelineSupport.patchStoppedUnverified(EngineOutcome.STOPPED, "benchmark"))
        .isFalse();
  }
}
