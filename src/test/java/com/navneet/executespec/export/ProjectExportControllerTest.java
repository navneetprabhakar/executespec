package com.navneet.executespec.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.security.ProjectAccessGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@ExtendWith(MockitoExtension.class)
class ProjectExportControllerTest {

  @Mock ProjectExportService service;
  @Mock ProjectExportObjectStore objectStore;
  @Mock ProjectAccessGuard guard;

  private ProjectExportController controller;
  private AppUserDetails principal;
  private Project project;

  @BeforeEach
  void setUp() {
    controller = new ProjectExportController(service, objectStore, guard);
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("admin@example.test")
            .passwordHash("hash")
            .status("ACTIVE")
            .globalRole("ORG_ADMIN")
            .orgId("org-1")
            .build();
    principal = new AppUserDetails(user, null);
    project = Project.builder().id("project-1").orgId("org-1").name("Project").build();
  }

  @Test
  void createUsesWriteGuardBeforeQueueing() {
    var job = ExportTestFixtures.job(ProjectExportJobStore.Status.QUEUED);
    when(guard.assertCanWrite("project-1", principal)).thenReturn(project);
    when(service.request(project, "user-1", "request-key")).thenReturn(job);

    var response = controller.create("project-1", "request-key", principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    assertThat(response.getHeaders().getLocation())
        .hasPath("/api/projects/project-1/export/" + job.id());
    verify(guard).assertCanWrite("project-1", principal);
  }

  @Test
  void deniedTenantNeverReachesService() {
    when(guard.assertCanWrite("foreign", principal))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));

    assertThatThrownBy(() -> controller.create("foreign", "request-key", principal))
        .isInstanceOf(ResponseStatusException.class);
    verify(service, never()).request(project, "user-1", "request-key");
  }

  @Test
  void malformedJobIdFailsBeforeAnyLookup() {
    when(guard.assertCanRead("project-1", principal)).thenReturn(project);

    assertThatThrownBy(() -> controller.status("project-1", "not-a-uuid", principal))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            error ->
                assertThat(((ResponseStatusException) error).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST));
    verify(service, never()).get(any(), any());
  }

  @Test
  void controllerRoleGateDoesNotAdmitBillingOrProjectOnlyRoles() {
    String expression = ProjectExportController.class.getAnnotation(PreAuthorize.class).value();

    assertThat(expression)
        .contains("SUPER_ADMIN", "ORG_ADMIN")
        .doesNotContain("BILLING_ADMIN", "PROJECT_ADMIN", "PROJECT_DEVELOPER");
  }

  @Test
  void successfulDownloadIsTenantCheckedAuditedAndStreamedWithoutHeapResponse() throws Exception {
    var job = ExportTestFixtures.job(ProjectExportJobStore.Status.SUCCEEDED);
    when(guard.assertCanRead("project-1", principal)).thenReturn(project);
    when(service.get(project, job.id())).thenReturn(job);
    when(objectStore.exists(job.objectKey())).thenReturn(true);
    when(service.recordDownload(project, job.id(), "user-1")).thenReturn(true);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              ((OutputStream) invocation.getArgument(1))
                  .write("archive".getBytes(StandardCharsets.UTF_8));
              return null;
            })
        .when(objectStore)
        .copyTo(eq(job.objectKey()), any());

    var response = controller.download("project-1", job.id().toString(), principal);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).isInstanceOf(StreamingResponseBody.class);
    ByteArrayOutputStream target = new ByteArrayOutputStream();
    ((StreamingResponseBody) response.getBody()).writeTo(target);
    assertThat(target.toString(StandardCharsets.UTF_8)).isEqualTo("archive");
    verify(service).recordDownload(project, job.id(), "user-1");
    verify(objectStore).copyTo(eq(job.objectKey()), any());
  }
}
