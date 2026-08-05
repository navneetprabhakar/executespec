package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.api.advice.GlobalExceptionAdvice;
import com.navneet.executespec.api.advice.ValidationAdvice;
import com.navneet.executespec.auth.security.RunAccessGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class PreviewRenderControllerTest {

  private static final String RUN_ID = "run-123";

  private PreviewRenderService service;
  private PreviewRenderController controller;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    service = mock(PreviewRenderService.class);
    controller =
        new PreviewRenderController(
            service, mock(PreviewRenderGuard.class), mock(RunAccessGuard.class));
    mvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new ValidationAdvice(), new GlobalExceptionAdvice())
            .build();
  }

  @Test
  void featureOffHidesStaleJobsAndBundlesWithoutInspectingProductChanges() {
    when(service.enabled()).thenReturn(false);

    PreviewRenderDtos.RenderManifest manifest = controller.manifest(RUN_ID, null).getBody();

    assertThat(manifest).isNotNull();
    assertThat(manifest.available()).isFalse();
    assertThat(manifest.renderAllowed()).isFalse();
    assertThat(manifest.jobId()).isNull();
    assertThat(manifest.surfaces()).isEmpty();
    verify(service, never()).latestJob(anyString());
    verify(service, never()).canStartRender(anyString());
  }

  @Test
  void featureOffRefusesToServeAStoredSurface() {
    when(service.enabled()).thenReturn(false);

    assertThatThrownBy(() -> controller.surface(RUN_ID, "surface-1", null))
        .isInstanceOfSatisfying(
            ResponseStatusException.class,
            error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE))
        .hasMessageContaining("not enabled");

    verify(service, never()).latestJob(anyString());
  }

  @Test
  void admittedBaselineControlsAnAbsentJobsRenderEligibility() {
    when(service.enabled()).thenReturn(true);
    when(service.latestJob(RUN_ID)).thenReturn(Optional.empty());
    when(service.canStartRender(RUN_ID)).thenReturn(true);

    PreviewRenderDtos.RenderManifest manifest = controller.manifest(RUN_ID, null).getBody();

    assertThat(manifest).isNotNull();
    assertThat(manifest.renderAllowed()).isTrue();
  }

  @Test
  void activeJobRemainsReloadableWithoutRecheckingWorkspaceEvidence() {
    PreviewRenderJob active =
        PreviewRenderJob.builder()
            .runId(RUN_ID)
            .status(PreviewRenderJob.Status.RENDERING.name())
            .requestedAt(Instant.now())
            .build();
    when(service.enabled()).thenReturn(true);
    when(service.latestJob(RUN_ID)).thenReturn(Optional.of(active));
    when(service.bundles(active.getId())).thenReturn(java.util.List.of());

    PreviewRenderDtos.RenderManifest manifest = controller.manifest(RUN_ID, null).getBody();

    assertThat(manifest).isNotNull();
    assertThat(manifest.jobId()).isEqualTo(active.getId().toString());
    assertThat(manifest.renderAllowed()).isTrue();
    verify(service, never()).canStartRender(anyString());
  }

  @Test
  void missingSurfaceRemainsNotFoundForJsonAcceptingClients() throws Exception {
    PreviewRenderJob failed =
        PreviewRenderJob.builder()
            .runId(RUN_ID)
            .status(PreviewRenderJob.Status.FAILED.name())
            .requestedAt(Instant.now())
            .build();
    when(service.enabled()).thenReturn(true);
    when(service.latestJob(RUN_ID)).thenReturn(Optional.of(failed));
    when(service.bundle(failed.getId(), "missing-surface")).thenReturn(Optional.empty());

    mvc.perform(
            get("/api/runs/{runId}/preview-render/surfaces/{surfaceId}", RUN_ID, "missing-surface")
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("not_found"))
        .andExpect(jsonPath("$.message").value("surface not rendered"));
  }
}
