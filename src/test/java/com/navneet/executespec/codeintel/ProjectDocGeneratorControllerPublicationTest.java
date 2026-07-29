package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectDocGeneratorControllerPublicationTest {

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
    ProjectDocGeneratorController controller = new ProjectDocGeneratorController(service, guard);

    assertThatThrownBy(() -> controller.download("project-1", "SETUP", principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(secret);
            });
  }

  @Test
  void truncatedGenerationIsProjectedAsBadGatewayAndNotCreated() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    when(service.generate("project-1", DocType.EXPLANATION, null, null))
        .thenThrow(
            new ProjectDocGeneratorService.IncompleteGenerationException(DocType.EXPLANATION));
    ProjectDocGeneratorController controller = new ProjectDocGeneratorController(service, guard);

    assertThatThrownBy(() -> controller.generate("project-1", "EXPLANATION", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
              assertThat(error.getReason()).contains("no document was saved");
            });
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
    ProjectDocGeneratorController controller = new ProjectDocGeneratorController(service, guard);

    assertThatThrownBy(() -> controller.latest("project-1", "EXPLANATION", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(secret);
            });
  }

  @Test
  void unsafeGenerationIsProjectedAsUnprocessableAndNotCreated() {
    ProjectDocGeneratorService service = mock(ProjectDocGeneratorService.class);
    ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    when(service.generate("project-1", DocType.SETUP, null, null))
        .thenThrow(new ProjectDocGeneratorService.UnsafeGeneratedDocumentException());
    ProjectDocGeneratorController controller = new ProjectDocGeneratorController(service, guard);

    assertThatThrownBy(() -> controller.generate("project-1", "SETUP", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).contains("no document was saved");
            });
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
    ProjectDocGeneratorController controller = new ProjectDocGeneratorController(service, guard);

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
