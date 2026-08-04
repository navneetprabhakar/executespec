package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.contractsurface.BrownfieldHttpContractGuard;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.project.pr.PullRequestRecordService;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.BitbucketRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.saga.SideEffectOutbox;
import com.navneet.executespec.security.ArtifactPublicationGuard;
import com.navneet.executespec.sse.RunEvent;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.RunRepoSnapshotRepository;
import com.navneet.executespec.tool.GitTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * A clean run whose auto-push was blocked must leave in-band, structured evidence: a bounded
 * durable {@code PUBLICATION_BLOCKED[<code>]} marker for the terminal-outcome projection plus an
 * event-log line carrying the admission code/gate/remediation — never only a bare transport error.
 */
@ExtendWith(MockitoExtension.class)
class ScmPushServicePublicationBlockEvidenceTest {

  @Mock private GitTool gitTool;
  @Mock private GitHubRepoService gitHubRepoService;
  @Mock private BitbucketRepoService bitbucketRepoService;
  @Mock private AgentProperties props;
  @Mock private StateStore stateStore;
  @Mock private SseBroadcaster broadcaster;
  @Mock private AppUserRepository appUserRepository;
  @Mock private ProjectRepository projectRepository;
  @Mock private PullRequestRecordService pullRequestRecordService;
  @Mock private PullRequestRecordRepository pullRequestRecordRepository;
  @Mock private ProjectRepoRepository projectRepoRepository;
  @Mock private ArtifactPublicationGuard publicationGuard;
  @Mock private BrownfieldHttpContractGuard brownfieldHttpContractGuard;
  @Mock private PublicationAdmissionService publicationAdmissionService;
  @Mock private SideEffectOutbox sideEffectOutbox;
  @Mock private RunRepoSnapshotRepository runRepoSnapshotRepository;
  @Spy private ObjectMapper objectMapper = new ObjectMapper();

  @InjectMocks private ScmPushService service;

  private static PipelineRun cleanRun() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-1");
    run.setStatus("COMPLETE");
    run.setStatusReason("CLEAN_COMPLETION");
    return run;
  }

  @Test
  void admissionBlockPersistsBoundedMarkerAndEmitsStructuredEvent() {
    PipelineRun run = cleanRun();
    PublicationAdmissionService.Eligibility stale =
        new PublicationAdmissionService.Eligibility(
            false,
            "BLOCKED",
            "PUBLICATION_EVIDENCE_STALE",
            "EVIDENCE_FRESHNESS",
            "Workspace content changed after validation",
            true,
            "Regenerate deterministic evidence on the current workspace.",
            "old-generation",
            "new-generation");
    when(publicationAdmissionService.assertEligible(run))
        .thenThrow(new PublicationAdmissionService.PublicationBlockedException(stale));
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

    ScmPushService.PushResult result = service.push(run);

    assertThat(result.pushed()).isFalse();
    assertThat(result.code()).isEqualTo("PUBLICATION_EVIDENCE_STALE");

    ArgumentCaptor<PipelineRun> saved = ArgumentCaptor.forClass(PipelineRun.class);
    verify(stateStore).save(saved.capture());
    assertThat(saved.getValue().getFailureReason())
        .startsWith("PUBLICATION_BLOCKED[PUBLICATION_EVIDENCE_STALE]")
        .contains("gate=EVIDENCE_FRESHNESS")
        .hasSizeLessThanOrEqualTo(500);

    ArgumentCaptor<RunEvent> event = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emit(eq("run-1"), event.capture());
    assertThat(event.getValue().payload())
        .contains("Push blocked [PUBLICATION_EVIDENCE_STALE]")
        .contains("gate=EVIDENCE_FRESHNESS")
        .contains("Regenerate validation on the current workspace");
  }

  @Test
  void realFailureReasonIsNeverOverwrittenByThePublicationMarker() {
    PipelineRun run = cleanRun();
    run.setFailureReason("WORKSPACE_PREFLIGHT_FAILED:CLONE_MISSING: clone missing");
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

    service.recordPublicationBlockEvidence(
        run,
        ScmPushService.PushResult.blocked(
            "PUBLICATION_EVIDENCE_STALE",
            "EVIDENCE_FRESHNESS",
            "stale",
            "GITHUB",
            true,
            "Regenerate."));

    verify(stateStore, never()).save(run);
    assertThat(run.getFailureReason()).startsWith("WORKSPACE_PREFLIGHT_FAILED");
  }

  @Test
  void successfulPushClearsAStalePublicationMarker() {
    PipelineRun run = cleanRun();
    run.setFailureReason(
        "PUBLICATION_BLOCKED[PUBLICATION_EVIDENCE_STALE]: gate=EVIDENCE_FRESHNESS");
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));

    service.recordPublicationBlockEvidence(
        run, ScmPushService.PushResult.pushed("GITHUB", "https://example/pr/1", "ok"));

    verify(stateStore).save(run);
    assertThat(run.getFailureReason()).isNull();
  }
}
