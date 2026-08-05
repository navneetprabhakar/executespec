package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.repo.GitHubRepoService;

import org.junit.jupiter.api.Test;

/**
 * Webhook-onboarding surfacing (2026-08-05, P1 backend half) — an UNKNOWN {@code ciStatus} used to
 * ship with no reason, so the frontend could not distinguish "webhook not installed" from "not yet
 * polled". {@code GET /api/runs/{id}/prs} now carries additive {@code ciStatusReason} ({@code
 * WEBHOOK_NOT_INSTALLED} only when the repo record PROVES no webhook) and {@code repoId} (so the
 * client can offer {@code POST /api/repos/{repoId}/webhook}).
 */
class PullRequestControllerCiStatusReasonTest {

  private final PullRequestRecordService service = mock(PullRequestRecordService.class);
  private final RunAccessGuard runAccessGuard = mock(RunAccessGuard.class);
  private final PullRequestController controller =
      new PullRequestController(
          service,
          mock(GitHubRepoService.class),
          mock(PrRefreshService.class),
          runAccessGuard,
          mock(ProjectAccessGuard.class));

  private static PullRequestRecord record(UUID repoId, PullRequestRecord.CiCheckStatus ci) {
    return PullRequestRecord.builder()
        .runId("run-1")
        .repoId(repoId)
        .repoFullName("acme/repo")
        .prNumber(7)
        .prUrl("https://github.com/acme/repo/pull/7")
        .status(PullRequestRecord.Status.OPEN)
        .ciStatus(ci)
        .build();
  }

  @Test
  void unknownCiStatusWithProvablyMissingWebhookCarriesReasonAndRepoId() {
    UUID repoId = UUID.randomUUID();
    when(service.listForRun("run-1"))
        .thenReturn(List.of(record(repoId, PullRequestRecord.CiCheckStatus.UNKNOWN)));
    when(service.webhookInstalled(repoId)).thenReturn(Boolean.FALSE);

    var out = controller.listPrsForRun("run-1", null).getBody();

    assertThat(out).hasSize(1);
    assertThat(out.get(0).ciStatus()).isEqualTo("UNKNOWN");
    assertThat(out.get(0).ciStatusReason())
        .isEqualTo(PullRequestController.CI_STATUS_REASON_WEBHOOK_NOT_INSTALLED);
    assertThat(out.get(0).repoId()).isEqualTo(repoId.toString());
  }

  @Test
  void unknownCiStatusWithInstalledWebhookHasNullReason() {
    UUID repoId = UUID.randomUUID();
    when(service.listForRun("run-1"))
        .thenReturn(List.of(record(repoId, PullRequestRecord.CiCheckStatus.UNKNOWN)));
    when(service.webhookInstalled(repoId)).thenReturn(Boolean.TRUE);

    var out = controller.listPrsForRun("run-1", null).getBody();

    assertThat(out.get(0).ciStatusReason()).isNull();
    assertThat(out.get(0).repoId()).isEqualTo(repoId.toString());
  }

  @Test
  void unknownInstallStateIsNotPresentedAsNotInstalled() {
    UUID repoId = UUID.randomUUID();
    when(service.listForRun("run-1"))
        .thenReturn(List.of(record(repoId, PullRequestRecord.CiCheckStatus.UNKNOWN)));
    // Repo row missing / unknown state — the service returns null, never false.
    when(service.webhookInstalled(repoId)).thenReturn(null);

    var out = controller.listPrsForRun("run-1", null).getBody();

    assertThat(out.get(0).ciStatusReason()).isNull();
  }

  @Test
  void knownCiStatusNeverCarriesTheWebhookReasonEvenWithoutAWebhook() {
    UUID repoId = UUID.randomUUID();
    when(service.listForRun("run-1"))
        .thenReturn(List.of(record(repoId, PullRequestRecord.CiCheckStatus.SUCCESS)));
    when(service.webhookInstalled(repoId)).thenReturn(Boolean.FALSE);

    var out = controller.listPrsForRun("run-1", null).getBody();

    assertThat(out.get(0).ciStatus()).isEqualTo("SUCCESS");
    assertThat(out.get(0).ciStatusReason()).isNull();
  }

  @Test
  void recordWithoutRepoLinkageHasNullRepoIdAndNoReason() {
    when(service.listForRun("run-1"))
        .thenReturn(List.of(record(null, PullRequestRecord.CiCheckStatus.UNKNOWN)));

    var out = controller.listPrsForRun("run-1", null).getBody();

    assertThat(out.get(0).repoId()).isNull();
    assertThat(out.get(0).ciStatusReason()).isNull();
  }
}
