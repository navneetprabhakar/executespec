package com.navneet.executespec.previewrender;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.executioncontext.LlmExecutionOperationCoordinator;
import com.navneet.executespec.executioncontext.LlmExecutionOperationDescriptor;
import com.navneet.executespec.executioncontext.LlmExecutionPolicyFactory;
import com.navneet.executespec.executioncontext.LlmExecutionReference;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.tool.ExecutionContext;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/** Issues a one-shot logical operation for one persisted preview surface. */
@Service
@RequiredArgsConstructor
class PreviewRenderOperationRoot {

  record Active(LlmExecutionOperationCoordinator.Lease lease, ExecutionContext context) {}

  private final LlmExecutionPolicyFactory policyFactory;
  private final LlmExecutionOperationCoordinator coordinator;

  Optional<Active> claim(
      UUID jobId,
      String runId,
      PreviewSurface surface,
      ResolvedModel route,
      String owner,
      Duration leaseDuration) {
    String attemptId = jobId + ":" + surface.id();
    LlmExecutionReference reference = policyFactory.forRunPreviewSurface(runId, attemptId, route);
    LlmExecutionOperationDescriptor descriptor =
        LlmExecutionOperationDescriptor.of(
            "preview-render:" + surface.id(),
            "PREVIEW_RENDER",
            immutableIdentity(jobId, runId, surface, route),
            1);
    return coordinator
        .claimOrResume(reference, descriptor, owner, leaseDuration)
        .map(
            lease ->
                new Active(
                    lease,
                    coordinator.bind(
                        new ExecutionContext(
                            runId,
                            "preview-render:" + surface.id(),
                            Path.of(System.getProperty("java.io.tmpdir", "/tmp"))
                                .toAbsolutePath()
                                .normalize(),
                            reference),
                        lease)));
  }

  boolean heartbeat(Active active, Duration leaseDuration) {
    return coordinator.heartbeat(active.lease(), leaseDuration);
  }

  void complete(Active active) {
    if (!coordinator.complete(active.lease())) {
      throw new IllegalStateException("preview render operation could not be completed");
    }
  }

  void terminate(Active active, String code, String message) {
    if (active == null) return;
    if (coordinator.acknowledgeCancellation(active.lease())) return;
    if (!coordinator.fail(active.lease(), code, message, false, null)) {
      throw new IllegalStateException("preview render operation could not be terminalized");
    }
  }

  private static String immutableIdentity(
      UUID jobId, String runId, PreviewSurface surface, ResolvedModel route) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      update(digest, surface.source());
      for (PreviewSurface.SupportingSource supporting : surface.supporting()) {
        update(digest, supporting.path());
        update(digest, supporting.content());
      }
      return String.join(
          "\n",
          jobId.toString(),
          runId,
          surface.id(),
          surface.path(),
          surface.target().name(),
          route.getActualProvider().name(),
          route.getModel(),
          HexFormat.of().formatHex(digest.digest()));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  private static void update(MessageDigest digest, String value) {
    if (value != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
    digest.update((byte) 0);
  }
}
