package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectDocGeneratorControllerPublicationTest {

  @Test
  void generationReturnsAcceptedDurableJobWithExactRoute() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectGeneratedDocJobService jobs = mock(ProjectGeneratedDocJobService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    AppUserDetails principal = mock(AppUserDetails.class);
    AppUser user = mock(AppUser.class);
    when(principal.getUser()).thenReturn(user);
    when(user.getId()).thenReturn("user-1");
    UUID jobId = UUID.randomUUID();
    var job =
        new ProjectGeneratedDocJobStore.Job(
            jobId,
            "project-1",
            "org-1",
            "user-1",
            "EXPLANATION",
            "ANTHROPIC",
            "claude-sonnet-4-6",
            ProjectGeneratedDocJobStore.Status.QUEUED,
            0,
            Instant.now(),
            Instant.now().plusSeconds(1800),
            null,
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.now(),
            null,
            null);
    when(jobs.request("project-1", DocType.EXPLANATION, "user-1")).thenReturn(job);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(service, jobs, guard);

    var response = controller.generate("project-1", "EXPLANATION", principal);

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getHeaders().getLocation().toString())
        .endsWith("/docs/EXPLANATION/jobs/" + jobId);
    assertThat(response.getBody().provider()).isEqualTo("ANTHROPIC");
    assertThat(response.getBody().model()).isEqualTo("claude-sonnet-4-6");
  }

  @Test
  void uncertainRetryRequiresWriteAuthorityAndReturnsAcceptedSameJob() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectGeneratedDocJobService jobs = mock(ProjectGeneratedDocJobService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    AppUserDetails principal = mock(AppUserDetails.class);
    AppUser user = mock(AppUser.class);
    when(principal.getUser()).thenReturn(user);
    when(user.getId()).thenReturn("user-1");
    UUID jobId = UUID.randomUUID();
    var queued =
        new ProjectGeneratedDocJobStore.Job(
            jobId,
            "project-1",
            "org-1",
            "user-1",
            "EXPLANATION",
            "ANTHROPIC",
            "claude-sonnet-4-6",
            ProjectGeneratedDocJobStore.Status.QUEUED,
            0,
            Instant.now(),
            Instant.now().plusSeconds(1800),
            null,
            1,
            null,
            null,
            null,
            null,
            null,
            null,
            Instant.now(),
            null,
            null);
    when(jobs.confirmRetry("project-1", DocType.EXPLANATION, jobId, "user-1")).thenReturn(queued);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(service, jobs, guard);

    var response = controller.confirmRetry("project-1", "EXPLANATION", jobId, principal);

    assertThat(response.getStatusCode().value()).isEqualTo(202);
    assertThat(response.getBody().id()).isEqualTo(jobId);
    verify(guard).assertCanWrite("project-1", principal);
  }

  @Test
  void generatedDocumentDownloadRejectsCredentialMaterialWithoutEchoingIt() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    AppUserDetails principal = mock(AppUserDetails.class);
    String secret = "sk-ant-" + "A1".repeat(20);
    when(service.latest("project-1", DocType.SETUP))
        .thenReturn(
            Optional.of(
                ProjectGeneratedDoc.builder()
                    .projectId("project-1")
                    .docType(DocType.SETUP)
                    .content("setup evidence=" + secret)
                    .build()));
    doThrow(new ProjectDocGeneratorService.UnsafeGeneratedDocumentException())
        .when(service)
        .assertSafeForCustomer("setup evidence=" + secret);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(
            service, mock(ProjectGeneratedDocJobService.class), guard);

    assertThatThrownBy(() -> controller.download("project-1", "SETUP", principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(secret);
            });
  }

  @Test
  void generationAdmissionRequiresAnAuthenticatedActor() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(
            service, mock(ProjectGeneratedDocJobService.class), guard);

    assertThatThrownBy(() -> controller.generate("project-1", "EXPLANATION", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
  }

  @Test
  void latestDocumentRejectsCredentialMaterialInsteadOfReturningItToTheUi() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    String secret = "sk-ant-" + "B2".repeat(20);
    when(service.latest("project-1", DocType.EXPLANATION))
        .thenReturn(
            Optional.of(
                ProjectGeneratedDoc.builder()
                    .projectId("project-1")
                    .docType(DocType.EXPLANATION)
                    .content("# Architecture\ncredential=" + secret)
                    .build()));
    doThrow(new ProjectDocGeneratorService.UnsafeGeneratedDocumentException())
        .when(service)
        .assertSafeForCustomer("# Architecture\ncredential=" + secret);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(
            service, mock(ProjectGeneratedDocJobService.class), guard);

    assertThatThrownBy(() -> controller.latest("project-1", "EXPLANATION", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(secret);
            });
  }

  @Test
  void unknownDocumentTypeIsRejectedBeforeJobAdmission() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(
            service, mock(ProjectGeneratedDocJobService.class), guard);

    assertThatThrownBy(() -> controller.generate("project-1", "UNKNOWN", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
  }

  @Test
  void latestAndDownloadBothRejectInternalToolProtocolWithoutEchoingIt() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    String protocol = "<tool_call>{\"name\":\"readFile\"}</tool_call>";
    ProjectGeneratedDoc unsafe =
        ProjectGeneratedDoc.builder()
            .projectId("project-1")
            .docType(DocType.EXPLANATION)
            .content(protocol)
            .build();
    when(service.latest("project-1", DocType.EXPLANATION)).thenReturn(Optional.of(unsafe));
    doThrow(new ProjectDocGeneratorService.UnsafeGeneratedDocumentException())
        .when(service)
        .assertSafeForCustomer(protocol);
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(
            service, mock(ProjectGeneratedDocJobService.class), guard);

    assertThatThrownBy(() -> controller.latest("project-1", "EXPLANATION", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(protocol);
            });
    assertThatThrownBy(() -> controller.download("project-1", "EXPLANATION", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(protocol);
            });
  }
}
