package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.execution.service.ExecutionBranchService;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExecutionBranchServicePublicationAdmissionTest {

  @Mock private GitHubRepoService gitHubRepoService;
  @Mock private PullRequestRecordService prRecordService;
  @Mock private PipelineRunRepository pipelineRunRepository;
  @Mock private PublicationAdmissionService publicationAdmissionService;
  @InjectMocks private ExecutionBranchService service;

  @Test
  void blocksBeforeGitWhenAnyBackingRunFailsCanonicalAdmission() {
    PipelineRun first = PipelineRun.builder().runId("pipeline-1").build();
    PipelineRun second = PipelineRun.builder().runId("pipeline-2").build();
    when(pipelineRunRepository.findById("pipeline-1")).thenReturn(Optional.of(first));
    when(pipelineRunRepository.findById("pipeline-2")).thenReturn(Optional.of(second));
    when(publicationAdmissionService.assertEligible(first))
        .thenReturn(new PublicationAdmissionService.Admission("generation-1", "ELIGIBLE"));
    var eligibility =
        new PublicationAdmissionService.Eligibility(
            false,
            "BLOCKED",
            "PUBLICATION_TESTS_UNPROVEN",
            "TESTS",
            "Tests were not execution verified",
            true,
            "Run at least one real test and regenerate validation evidence.",
            "generation-1",
            "generation-1");
    when(publicationAdmissionService.assertEligible(second))
        .thenThrow(new PublicationAdmissionService.PublicationBlockedException(eligibility));

    var result =
        service.createFinalPr(
            "acme/service",
            UUID.randomUUID(),
            "execution-12345678",
            List.of("pipeline-1", "pipeline-2"),
            "report",
            "pat");

    assertThat(result.created()).isFalse();
    assertThat(result.code()).isEqualTo("PUBLICATION_TESTS_UNPROVEN");
    assertThat(result.blockingGate()).isEqualTo("TESTS");
    assertThat(result.pipelineRunId()).isEqualTo("pipeline-2");
    assertThat(result.retryable()).isTrue();
    assertThat(result.remediation()).contains("real test");
    verify(publicationAdmissionService).assertEligible(first);
    verify(publicationAdmissionService).assertEligible(second);
    verify(gitHubRepoService, never())
        .createPullRequest(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void missingBackingRunFailsClosedBeforeGit() {
    var result =
        service.createFinalPr(
            "acme/service",
            UUID.randomUUID(),
            "execution-12345678",
            List.of("missing-run"),
            "report",
            "pat");

    assertThat(result.created()).isFalse();
    assertThat(result.code()).isEqualTo("EXECUTION_FINAL_PR_BACKING_RUN_NOT_FOUND");
    assertThat(result.blockingGate()).isEqualTo("RUN");
    assertThat(result.pipelineRunId()).isEqualTo("missing-run");
    verify(gitHubRepoService, never())
        .createPullRequest(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void nullGithubPrUrlIsStructuredFailureAndNeverClaimsCreated() {
    PipelineRun run = PipelineRun.builder().runId("pipeline-1").build();
    UUID repoId = UUID.randomUUID();
    when(pipelineRunRepository.findById("pipeline-1")).thenReturn(Optional.of(run));
    when(publicationAdmissionService.assertEligible(run))
        .thenReturn(new PublicationAdmissionService.Admission("generation-1", "ELIGIBLE"));
    when(gitHubRepoService.createPullRequest(
            "https://github.com/acme/service",
            "exec/executio",
            null,
            "Execution executio — combined solutions",
            "report",
            "pat"))
        .thenReturn(null);

    var result =
        service.createFinalPr(
            "acme/service", repoId, "execution-12345678", List.of("pipeline-1"), "report", "pat");

    assertThat(result.created()).isFalse();
    assertThat(result.record()).isNull();
    assertThat(result.code()).isEqualTo("EXECUTION_FINAL_PR_CREATE_FAILED");
    assertThat(result.blockingGate()).isEqualTo("PR_CREATION");
    verify(prRecordService, never())
        .recordCreated(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void admissionInfrastructureFailureIsStructuredAndFailsBeforeGit() {
    PipelineRun run = PipelineRun.builder().runId("pipeline-1").build();
    when(pipelineRunRepository.findById("pipeline-1")).thenReturn(Optional.of(run));
    when(publicationAdmissionService.assertEligible(run))
        .thenThrow(new IllegalStateException("validation store unavailable"));

    var result =
        service.createFinalPr(
            "acme/service",
            UUID.randomUUID(),
            "execution-12345678",
            List.of("pipeline-1"),
            "report",
            "pat");

    assertThat(result.created()).isFalse();
    assertThat(result.code()).isEqualTo("EXECUTION_FINAL_PR_ADMISSION_UNAVAILABLE");
    assertThat(result.blockingGate()).isEqualTo("PUBLICATION_ADMISSION");
    assertThat(result.pipelineRunId()).isEqualTo("pipeline-1");
    verify(gitHubRepoService, never())
        .createPullRequest(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void createdPrWithPersistenceFailureIsNonRetryableAndPreservesUrl() {
    PipelineRun run = PipelineRun.builder().runId("pipeline-1").build();
    UUID repoId = UUID.randomUUID();
    when(pipelineRunRepository.findById("pipeline-1")).thenReturn(Optional.of(run));
    when(publicationAdmissionService.assertEligible(run))
        .thenReturn(new PublicationAdmissionService.Admission("generation-1", "ELIGIBLE"));
    when(gitHubRepoService.createPullRequest(
            "https://github.com/acme/service",
            "exec/executio",
            null,
            "Execution executio — combined solutions",
            "report",
            "pat"))
        .thenReturn("https://github.com/acme/service/pull/42");
    doThrow(new IllegalStateException("database unavailable"))
        .when(prRecordService)
        .recordCreated(
            null,
            repoId,
            "acme/service",
            "https://github.com/acme/service/pull/42",
            "exec/executio",
            null);

    var result =
        service.createFinalPr(
            "acme/service", repoId, "execution-12345678", List.of("pipeline-1"), "report", "pat");

    assertThat(result.created()).isFalse();
    assertThat(result.code()).isEqualTo("EXECUTION_FINAL_PR_RECORD_FAILED");
    assertThat(result.blockingGate()).isEqualTo("PR_STATE_PERSISTENCE");
    assertThat(result.retryable()).isFalse();
    assertThat(result.message()).contains("https://github.com/acme/service/pull/42");
    assertThat(result.remediation()).contains("do not retry PR creation");
  }
}
