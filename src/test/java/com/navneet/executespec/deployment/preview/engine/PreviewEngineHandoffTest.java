package com.navneet.executespec.deployment.preview.engine;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;

import com.navneet.executespec.deployment.preview.PreviewProjectType;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RR-1620 — a non-READY preview-engine handoff response can still carry a preview id whose
 * container may already be running with the customer's injected env secrets. The handoff must
 * release that id before failing instead of discarding the only handle to it (which orphaned both
 * the running container and the pushed image with no reconciliation path).
 */
class PreviewEngineHandoffTest {

  @Test
  void nonReadyResponseWithAnIdTriggersUnregister() {
    PreviewImagePusher pusher = Mockito.mock(PreviewImagePusher.class);
    PreviewEngineClient client = Mockito.mock(PreviewEngineClient.class);

    when(client.isEnabled()).thenReturn(true);
    when(client.registryUrl()).thenReturn("localhost:5500");
    when(pusher.buildAndPush(anyString(), anyString(), any(), any()))
        .thenReturn(new PreviewImagePusher.PushResult("localhost:5500/app:sha", false));
    when(client.registerFromImage(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyString(),
            anyInt(),
            any()))
        .thenReturn(
            new PreviewEngineClient.EngineResponse(
                "preview-123", "STARTING", "still booting", null, null));
    // The engine's DELETE succeeds.
    when(client.unregister("preview-123")).thenReturn(true);

    PreviewEngineHandoff handoff = new PreviewEngineHandoff(pusher, client);

    assertThatThrownBy(
            () ->
                handoff.start(
                    "run-1",
                    Path.of("/tmp/ws"),
                    Path.of("/tmp/ws/Dockerfile"),
                    PreviewProjectType.NODE_BACKEND,
                    "user-1",
                    "PRO",
                    600,
                    Map.of()))
        .isInstanceOf(PreviewEngineClient.PreviewEngineException.class);

    // The orphaned id was released, not discarded.
    verify(client, times(1)).unregister("preview-123");
  }

  @Test
  void unregisterFailureStillCompletesFailurePath() {
    PreviewImagePusher pusher = Mockito.mock(PreviewImagePusher.class);
    PreviewEngineClient client = Mockito.mock(PreviewEngineClient.class);

    when(client.isEnabled()).thenReturn(true);
    when(client.registryUrl()).thenReturn("localhost:5500");
    when(pusher.buildAndPush(anyString(), anyString(), any(), any()))
        .thenReturn(new PreviewImagePusher.PushResult("localhost:5500/app:sha", false));
    when(client.registerFromImage(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            anyString(),
            anyInt(),
            any()))
        .thenReturn(
            new PreviewEngineClient.EngineResponse(
                "preview-9", "FAILED", "health probe failed", null, null));
    // The engine's DELETE could not confirm removal — still surfaced (logged), never swallowed.
    when(client.unregister("preview-9")).thenReturn(false);

    PreviewEngineHandoff handoff = new PreviewEngineHandoff(pusher, client);

    assertThatThrownBy(
            () ->
                handoff.start(
                    "run-2",
                    Path.of("/tmp/ws"),
                    Path.of("/tmp/ws/Dockerfile"),
                    PreviewProjectType.PYTHON,
                    "user-2",
                    "FREE",
                    300,
                    Map.of()))
        .isInstanceOf(PreviewEngineClient.PreviewEngineException.class);

    verify(client, times(1)).unregister("preview-9");
  }
}
