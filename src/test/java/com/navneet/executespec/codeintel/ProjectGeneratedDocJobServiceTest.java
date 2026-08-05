package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.codeintel.entity.ProjectGeneratedDoc.DocType;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelMatrixConfigurationException;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.OrgModelOverrideService;
import com.navneet.executespec.router.ProjectProviderResolver;
import com.navneet.executespec.router.ProjectRoutingException;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ProjectGeneratedDocJobServiceTest {

  @Test
  void confirmedRetryRequeuesTheSameIdentityForTheOriginalRequester() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    UUID id = UUID.randomUUID();
    var uncertain = job(id, ProjectGeneratedDocJobStore.Status.NEEDS_CONFIRMATION, "user-1");
    var queued = job(id, ProjectGeneratedDocJobStore.Status.QUEUED, "user-1");
    when(store.getScoped(id, "project-1")).thenReturn(Optional.of(uncertain), Optional.of(queued));
    when(store.confirmRetry(
            id, "project-1", "EXPLANATION", "user-1", java.time.Duration.ofMinutes(30)))
        .thenReturn(true);
    ProjectGeneratedDocJobService service = service(store);

    ProjectGeneratedDocJobStore.Job result =
        service.confirmRetry("project-1", DocType.EXPLANATION, id, "user-1");

    assertThat(result.id()).isEqualTo(id);
    assertThat(result.status()).isEqualTo(ProjectGeneratedDocJobStore.Status.QUEUED);
    assertThat(result.requestedProvider()).isEqualTo(uncertain.requestedProvider());
    assertThat(result.requestedModel()).isEqualTo(uncertain.requestedModel());
  }

  @Test
  void ordinaryFailedJobCannotUseThePaidRetryConfirmationRoute() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    UUID id = UUID.randomUUID();
    when(store.getScoped(id, "project-1"))
        .thenReturn(Optional.of(job(id, ProjectGeneratedDocJobStore.Status.FAILED, "user-1")));
    ProjectGeneratedDocJobService service = service(store);

    assertThatThrownBy(() -> service.confirmRetry("project-1", DocType.EXPLANATION, id, "user-1"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT");
    verify(store, never())
        .confirmRetry(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void missingProjectProviderRemainsAStableCustomerCorrectableConflict() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectRepository projects = mock(ProjectRepository.class);
    ProjectProviderResolver providers = mock(ProjectProviderResolver.class);
    Project project = Project.builder().id("project-1").orgId("org-1").build();
    when(projects.findById("project-1")).thenReturn(Optional.of(project));
    when(providers.resolve(project)).thenThrow(ProjectRoutingException.providerNotConfigured());
    ProjectGeneratedDocJobService service =
        new ProjectGeneratedDocJobService(
            store,
            projects,
            providers,
            mock(ModelResolver.class),
            mock(OrgModelOverrideService.class));

    assertThatThrownBy(() -> service.request("project-1", DocType.EXPLANATION, "actor-1"))
        .isInstanceOfSatisfying(
            ProjectRoutingException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(error.errorCode()).isEqualTo("PROJECT_PROVIDER_NOT_CONFIGURED");
            });
    verify(store, never())
        .createOrGet(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void invalidServerModelMatrixReturnsStableServiceUnavailable() {
    ProjectGeneratedDocJobStore store = mock(ProjectGeneratedDocJobStore.class);
    ProjectRepository projects = mock(ProjectRepository.class);
    ProjectProviderResolver providers = mock(ProjectProviderResolver.class);
    ModelResolver models = mock(ModelResolver.class);
    OrgModelOverrideService overrides = mock(OrgModelOverrideService.class);
    Project project = Project.builder().id("project-1").orgId("org-1").build();
    when(projects.findById("project-1")).thenReturn(Optional.of(project));
    when(providers.resolve(project)).thenReturn(LlmProvider.ANTHROPIC);
    when(overrides.overridesForRun("org-1")).thenReturn(java.util.Map.of());
    when(models.resolve("ProjectDocGeneratorService", LlmProvider.ANTHROPIC))
        .thenThrow(new ModelMatrixConfigurationException("missing server matrix row"));
    ProjectGeneratedDocJobService service =
        new ProjectGeneratedDocJobService(store, projects, providers, models, overrides);

    assertThatThrownBy(() -> service.request("project-1", DocType.EXPLANATION, "actor-1"))
        .isInstanceOfSatisfying(
            ProjectRoutingException.class,
            error -> {
              assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(error.errorCode()).isEqualTo("MODEL_MATRIX_UNAVAILABLE");
            });
    verify(store, never())
        .createOrGet(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any());
  }

  private static ProjectGeneratedDocJobService service(ProjectGeneratedDocJobStore store) {
    return new ProjectGeneratedDocJobService(
        store,
        mock(ProjectRepository.class),
        mock(ProjectProviderResolver.class),
        mock(ModelResolver.class),
        mock(OrgModelOverrideService.class));
  }

  private static ProjectGeneratedDocJobStore.Job job(
      UUID id, ProjectGeneratedDocJobStore.Status status, String actor) {
    boolean terminal = status.terminal();
    return new ProjectGeneratedDocJobStore.Job(
        id,
        "project-1",
        "org-1",
        actor,
        "EXPLANATION",
        "ANTHROPIC",
        "claude-sonnet-4-6",
        status,
        terminal ? 1 : 0,
        Instant.now(),
        Instant.now().plusSeconds(1800),
        null,
        1,
        null,
        terminal ? Instant.now() : null,
        null,
        null,
        terminal ? "OUTCOME_UNCERTAIN" : null,
        terminal ? "Retry requires confirmation." : null,
        Instant.now(),
        terminal ? Instant.now().minusSeconds(30) : null,
        terminal ? Instant.now() : null);
  }
}
