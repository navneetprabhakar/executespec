package com.navneet.executespec.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

class WorkspaceGcsUploadListenerPolicyTest {

  @Test
  void localExecutionStubIsNeverArchivedAsEvidence() {
    PipelineRun local =
        PipelineRun.builder().runId("local-run").localExecutionRequested(true).build();

    assertThat(WorkspaceGcsUploadListener.shouldUpload(local)).isFalse();
  }

  @Test
  void normalUnarchivedWorkspaceRemainsEligible() {
    PipelineRun normal = PipelineRun.builder().runId("normal-run").build();

    assertThat(WorkspaceGcsUploadListener.shouldUpload(normal)).isTrue();
  }
}
