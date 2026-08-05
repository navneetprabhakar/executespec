package com.navneet.executespec.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.report.entity.PipelineResult;
import com.navneet.executespec.report.entity.ValidationReport;
import com.navneet.executespec.report.model.ValidationReportShareDTO;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.sharing.entity.ProjectShareLink;
import com.navneet.executespec.sharing.service.ProjectShareLinkService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ValidationReportControllerShareTest {

  private final ValidationReportService reportService = mock(ValidationReportService.class);
  private final ValidationReportPdfService pdfService = mock(ValidationReportPdfService.class);
  private final RunAccessGuard accessGuard = mock(RunAccessGuard.class);
  private final PipelineRunRepository runRepository = mock(PipelineRunRepository.class);
  private final ProjectShareLinkService shareLinkService = mock(ProjectShareLinkService.class);
  private final ValidationReportController controller =
      new ValidationReportController(
          reportService,
          pdfService,
          accessGuard,
          runRepository,
          shareLinkService,
          new GeneratedSecretScanner(),
          new ObjectMapper().findAndRegisterModules());

  @Test
  void legacyPublicReportRejectsSerializedCredentialMaterialWithoutEchoingIt() {
    String secret = "sk-ant-" + "A1".repeat(20);
    ValidationReportShareDTO report =
        new ValidationReportShareDTO(
            "a".repeat(64),
            1,
            List.of("Java"),
            PipelineResult.PASSED,
            List.of(),
            List.of(),
            "evidence contained " + secret,
            Instant.now(),
            100,
            100,
            true,
            1,
            1,
            0,
            "CLEAN",
            List.of());
    when(reportService.getByShareToken("shared-token")).thenReturn(Optional.of(report));

    assertThatThrownBy(() -> controller.getSharedReport("shared-token"))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(error.getReason()).doesNotContain(secret);
            });
  }

  @Test
  void createsGovernedProjectShareLinkInsteadOfLegacyReportBearer() {
    String token = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    AppUser user = AppUser.builder().id("user-1").tier("TEAM").build();
    AppUserDetails principal = mock(AppUserDetails.class);
    when(principal.getUser()).thenReturn(user);
    when(reportService.findEntityByRunId("run-1"))
        .thenReturn(Optional.of(ValidationReport.builder().runId("run-1").build()));
    when(runRepository.findById("run-1"))
        .thenReturn(
            Optional.of(PipelineRun.builder().runId("run-1").projectId("project-1").build()));
    when(shareLinkService.createLink(
            eq("project-1"),
            eq(ProjectShareLink.Surface.VALIDATION_REPORT),
            eq("run-1"),
            eq("Validation report"),
            any(),
            eq(user)))
        .thenReturn(ProjectShareLink.builder().token(token).build());

    var response = controller.shareReport("run-1", principal);

    assertThat(response.getBody()).containsEntry("shareToken", token);
    assertThat(response.getBody()).containsEntry("shareUrl", "/shared/" + token);
    verify(accessGuard).requireRunWriteAccess("run-1", principal);
  }

  @Test
  void refusesUngovernedStandaloneRunShare() {
    AppUserDetails principal = mock(AppUserDetails.class);
    when(reportService.findEntityByRunId("run-standalone"))
        .thenReturn(Optional.of(ValidationReport.builder().runId("run-standalone").build()));
    when(runRepository.findById("run-standalone"))
        .thenReturn(Optional.of(PipelineRun.builder().runId("run-standalone").build()));

    assertThatThrownBy(() -> controller.shareReport("run-standalone", principal))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
  }
}
