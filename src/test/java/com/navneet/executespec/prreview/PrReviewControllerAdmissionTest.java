package com.navneet.executespec.prreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.prreview.entity.PrReview;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PrReviewControllerAdmissionTest {

  private final PrReviewService service = mock(PrReviewService.class);
  private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
  private final ProjectRepoService projectRepoService = mock(ProjectRepoService.class);
  private final PrReviewAutofixService autofixService = mock(PrReviewAutofixService.class);
  private final com.navneet.executespec.auth.repository.ProjectRepository projectRepository =
      mock(com.navneet.executespec.auth.repository.ProjectRepository.class);
  private final PrReviewController controller =
      new PrReviewController(service, guard, projectRepoService, autofixService, projectRepository);
  private final UUID repoId = UUID.randomUUID();
  private final AppUserDetails principal = principal();

  @BeforeEach
  void canonicalRepo() {
    when(projectRepoService.resolveGitHubReviewRepoStrict("project-1", repoId, "claimed/repo"))
        .thenReturn(ProjectRepo.builder().id(repoId).githubRepoFullName("canonical/repo").build());
  }

  @Test
  void submitRequiresLinkedRepoId() {
    ResponseStatusException error =
        assertThrows(
            ResponseStatusException.class,
            () ->
                controller.submit(
                    principal,
                    new PrReviewController.SubmitPrReviewRequest(
                        "project-1", null, "claimed/repo", 42)));

    assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    verify(guard, never()).assertCanWrite("project-1", principal);
  }

  @Test
  void writeGuardAndCanonicalLinkRunBeforeTierQuotaAndQueue() {
    PrReview queued =
        PrReview.builder()
            .projectId("project-1")
            .repoId(repoId)
            .repoFullName("canonical/repo")
            .prNumber(42)
            .headSha("abc")
            .status("QUEUED")
            .build();
    when(service.submitReview("project-1", repoId, "canonical/repo", 42, principal.getUser()))
        .thenReturn(queued);

    controller.submit(
        principal,
        new PrReviewController.SubmitPrReviewRequest("project-1", repoId, "claimed/repo", 42));

    var order = inOrder(guard, projectRepoService, service);
    order.verify(guard).assertCanWrite("project-1", principal);
    order
        .verify(projectRepoService)
        .resolveGitHubReviewRepoStrict("project-1", repoId, "claimed/repo");
    order.verify(service).assertTierAndQuota(principal.getUser());
    order
        .verify(service)
        .submitReview("project-1", repoId, "canonical/repo", 42, principal.getUser());
  }

  @Test
  void deniedCrossOrgOrViewerWriteCannotProbeQuotaOrQueue() {
    when(guard.assertCanWrite("project-1", principal))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "read only"));

    assertThrows(
        ResponseStatusException.class,
        () ->
            controller.submit(
                principal,
                new PrReviewController.SubmitPrReviewRequest(
                    "project-1", repoId, "claimed/repo", 42)));

    verify(projectRepoService, never())
        .resolveGitHubReviewRepoStrict("project-1", repoId, "claimed/repo");
    verify(service, never()).assertTierAndQuota(principal.getUser());
  }

  @Test
  void directGetChecksOwningProjectBeforeReturningComments() {
    UUID reviewId = UUID.randomUUID();
    when(service.findById(reviewId))
        .thenReturn(PrReview.builder().id(reviewId).projectId("other-project").build());
    when(guard.assertCanRead("other-project", principal))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-org"));

    assertThrows(ResponseStatusException.class, () -> controller.get(reviewId, principal));

    verify(service, never()).commentsFor(reviewId);
  }

  @Test
  void listByPrChecksProjectBeforeResolvingRepoOrReadingRows() {
    when(guard.assertCanRead("other-project", principal))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "cross-org"));

    assertThrows(
        ResponseStatusException.class,
        () ->
            controller.listForPr(
                principal, "other-project", repoId, "claimed/repo", 42, 0, 50, 366));

    verify(projectRepoService, never())
        .resolveGitHubReviewRepoStrict("other-project", repoId, "claimed/repo");
    verify(service, never())
        .findByPr(
            org.mockito.ArgumentMatchers.eq("other-project"),
            org.mockito.ArgumentMatchers.eq(repoId),
            org.mockito.ArgumentMatchers.eq(42),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void projectHistoryIsServerBoundedAndReportsContinuation() {
    PrReview row = PrReview.builder().id(UUID.randomUUID()).projectId("project-1").build();
    when(service.findByProject(eq("project-1"), any(), any()))
        .thenReturn(new SliceImpl<>(java.util.List.of(row), PageRequest.of(1000, 200), true));

    var response =
        controller.listForProject(
            "project-1", Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, principal);

    assertEquals("1000", response.getHeaders().getFirst("X-Page"));
    assertEquals("200", response.getHeaders().getFirst("X-Page-Size"));
    assertEquals("true", response.getHeaders().getFirst("X-Has-More"));
    assertEquals(1, response.getBody().size());
  }

  private static AppUserDetails principal() {
    return new AppUserDetails(
        AppUser.builder()
            .id("user-1")
            .email("developer@example.com")
            .passwordHash("hash")
            .status("ACTIVE")
            .globalRole("USER")
            .tier("PRO")
            .build(),
        null);
  }
}
