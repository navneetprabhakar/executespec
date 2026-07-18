package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.security.GeneratedSecretScanner;

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
    ProjectDocGeneratorController controller =
        new ProjectDocGeneratorController(service, guard, new GeneratedSecretScanner());

    assertThatThrownBy(() -> controller.download("project-1", "SETUP", principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(secret);
            });
  }
}
