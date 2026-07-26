package com.navneet.executespec.previewrender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.auth.security.RunAccessGuard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PreviewRenderControllerTest {

  private static final String RUN_ID = "run-123";

  private PreviewRenderService service;
  private PreviewRenderController controller;

  @BeforeEach
  void setUp() {
    service = mock(PreviewRenderService.class);
    controller =
        new PreviewRenderController(
            service, mock(PreviewRenderGuard.class), mock(RunAccessGuard.class));
  }

  @Test
  void featureOffTakesPrecedenceWithoutInspectingProductChanges() {
    when(service.enabled()).thenReturn(false);
    when(service.latestJob(RUN_ID)).thenReturn(Optional.empty());

    PreviewRenderDtos.RenderManifest manifest = controller.manifest(RUN_ID, null).getBody();

    assertThat(manifest).isNotNull();
    assertThat(manifest.available()).isFalse();
    assertThat(manifest.renderAllowed()).isFalse();
    verify(service, never()).hasProductFilesToRender(anyString());
  }

  @Test
  void admittedBaselineControlsAnAbsentJobsRenderEligibility() {
    when(service.enabled()).thenReturn(true);
    when(service.latestJob(RUN_ID)).thenReturn(Optional.empty());
    when(service.hasProductFilesToRender(RUN_ID)).thenReturn(true);

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
    verify(service, never()).hasProductFilesToRender(anyString());
  }
}
