package com.navneet.executespec.integration.scm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.project.pr.PullRequestRecord;
import com.navneet.executespec.project.pr.PullRequestRecordRepository;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

/**
 * Pins the additive publication-eligibility acknowledgment fields ({@code alreadyPublished}, {@code
 * publishedPrUrls}) without changing the gate verdict or any existing field semantics.
 */
class PublicationEligibilityPresenterTest {

  private static final String RUN_ID = "run-eligibility-view";

  private final PublicationAdmissionService admission = mock(PublicationAdmissionService.class);
  private final PullRequestRecordRepository prRecords = mock(PullRequestRecordRepository.class);
  private final PublicationEligibilityPresenter presenter =
      new PublicationEligibilityPresenter(admission, prRecords);

  private static PublicationAdmissionService.Eligibility blockedStale() {
    return new PublicationAdmissionService.Eligibility(
        false,
        "BLOCKED",
        "PUBLICATION_EVIDENCE_STALE",
        "EVIDENCE_FRESHNESS",
        "Workspace content changed after validation; regenerate deterministic evidence",
        true,
        "Regenerate build, test, workflow, reviewer, and contract evidence on the current"
            + " workspace.",
        "a".repeat(64),
        "b".repeat(64));
  }

  private static PipelineRun run() {
    return PipelineRun.builder().runId(RUN_ID).status("COMPLETE").build();
  }

  private static PullRequestRecord prRecord(String url) {
    return PullRequestRecord.builder()
        .runId(RUN_ID)
        .repoFullName("acme/service")
        .prNumber(7)
        .prUrl(url)
        .build();
  }

  @Test
  void publishedRunAcknowledgesPrEvidenceWhileGateVerdictStaysBlocked() {
    PipelineRun run = run();
    when(admission.evaluate(run)).thenReturn(blockedStale());
    when(prRecords.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenReturn(
            List.of(
                prRecord("https://github.com/acme/service/pull/7"),
                prRecord("https://github.com/acme/web/pull/12"),
                // duplicate URL must not repeat in the acknowledgment list
                prRecord("https://github.com/acme/service/pull/7")));

    PublicationEligibilityPresenter.Response response = presenter.present(run);

    // Existing fields carried through unchanged — the gate still reads BLOCKED.
    assertThat(response.eligible()).isFalse();
    assertThat(response.eligibility()).isEqualTo("BLOCKED");
    assertThat(response.code()).isEqualTo("PUBLICATION_EVIDENCE_STALE");
    assertThat(response.blockingGate()).isEqualTo("EVIDENCE_FRESHNESS");
    assertThat(response.retryable()).isTrue();
    assertThat(response.evidenceWorkspaceGeneration()).isEqualTo("a".repeat(64));
    assertThat(response.currentWorkspaceGeneration()).isEqualTo("b".repeat(64));

    // Additive acknowledgment.
    assertThat(response.alreadyPublished()).isTrue();
    assertThat(response.publishedPrUrls())
        .containsExactly(
            "https://github.com/acme/service/pull/7", "https://github.com/acme/web/pull/12");
  }

  @Test
  void unpublishedBlockedRunReportsFalseAndEmptyWithExistingFieldsUnchanged() {
    PipelineRun run = run();
    PublicationAdmissionService.Eligibility verdict = blockedStale();
    when(admission.evaluate(run)).thenReturn(verdict);
    when(prRecords.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());

    PublicationEligibilityPresenter.Response response = presenter.present(run);

    assertThat(response.alreadyPublished()).isFalse();
    assertThat(response.publishedPrUrls()).isEmpty();
    assertThat(response.eligible()).isEqualTo(verdict.eligible());
    assertThat(response.eligibility()).isEqualTo(verdict.eligibility());
    assertThat(response.code()).isEqualTo(verdict.code());
    assertThat(response.blockingGate()).isEqualTo(verdict.blockingGate());
    assertThat(response.reason()).isEqualTo(verdict.reason());
    assertThat(response.retryable()).isEqualTo(verdict.retryable());
    assertThat(response.remediation()).isEqualTo(verdict.remediation());
    assertThat(response.evidenceWorkspaceGeneration())
        .isEqualTo(verdict.evidenceWorkspaceGeneration());
    assertThat(response.currentWorkspaceGeneration())
        .isEqualTo(verdict.currentWorkspaceGeneration());
  }

  @Test
  void eligibleRunWithoutPrEvidenceStaysEligibleAndUnpublished() {
    PipelineRun run = run();
    when(admission.evaluate(run))
        .thenReturn(
            new PublicationAdmissionService.Eligibility(
                true,
                "ELIGIBLE",
                "PUBLICATION_ELIGIBLE",
                null,
                "All deterministic publication gates pass on the current workspace generation.",
                false,
                null,
                "a".repeat(64),
                "a".repeat(64)));
    when(prRecords.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());

    PublicationEligibilityPresenter.Response response = presenter.present(run);

    assertThat(response.eligible()).isTrue();
    assertThat(response.eligibility()).isEqualTo("ELIGIBLE");
    assertThat(response.alreadyPublished()).isFalse();
    assertThat(response.publishedPrUrls()).isEmpty();
  }

  @Test
  void legacyRunWithoutPrRecordsFallsBackToDurableRunPrUrl() {
    PipelineRun run = run();
    run.setGithubPrUrl("https://github.com/acme/service/pull/3");
    when(admission.evaluate(run)).thenReturn(blockedStale());
    when(prRecords.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());

    PublicationEligibilityPresenter.Response response = presenter.present(run);

    assertThat(response.alreadyPublished()).isTrue();
    assertThat(response.publishedPrUrls())
        .containsExactly("https://github.com/acme/service/pull/3");
  }

  @Test
  void canonicalScmPrUrlWinsOverLegacyGithubUrl() {
    PipelineRun run = run();
    run.setScmPrUrl("https://bitbucket.org/acme/service/pull-requests/9");
    run.setGithubPrUrl("https://github.com/acme/service/pull/3");
    when(admission.evaluate(run)).thenReturn(blockedStale());
    when(prRecords.findByRunIdOrderByCreatedAtAsc(RUN_ID)).thenReturn(List.of());

    PublicationEligibilityPresenter.Response response = presenter.present(run);

    assertThat(response.publishedPrUrls())
        .containsExactly("https://bitbucket.org/acme/service/pull-requests/9");
  }

  @Test
  void acknowledgmentLookupFailureNeverHidesTheGateVerdict() {
    PipelineRun run = run();
    run.setScmPrUrl("https://github.com/acme/service/pull/5");
    when(admission.evaluate(run)).thenReturn(blockedStale());
    when(prRecords.findByRunIdOrderByCreatedAtAsc(RUN_ID))
        .thenThrow(new RuntimeException("db unavailable"));

    PublicationEligibilityPresenter.Response response = presenter.present(run);

    assertThat(response.eligibility()).isEqualTo("BLOCKED");
    assertThat(response.code()).isEqualTo("PUBLICATION_EVIDENCE_STALE");
    // Falls back to the durable run-row URL rather than failing the read endpoint.
    assertThat(response.alreadyPublished()).isTrue();
    assertThat(response.publishedPrUrls())
        .containsExactly("https://github.com/acme/service/pull/5");
  }
}
