package com.navneet.executespec.onboarding;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.auth.security.AppUserDetails;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Wave 2.2 — onboarding endpoints used by the frontend {@code /welcome} page.
 *
 * <p>Authenticated (JWT required) but no specific role. Every user — including FREE-tier solo users
 * — can hit these routes.
 *
 * <p>Full plan: docs/plans/ttfv-reduction-plan.md §6.
 */
@RestController
@RequestMapping("/api/onboarding")
@RequiredArgsConstructor
@Slf4j
public class OnboardingController {

  private final SampleSpecRegistry sampleSpecs;
  private final SandboxProvisioningService sandboxService;

  public record SampleSpecDto(
      String id,
      String title,
      String category,
      String complexity,
      String preferredProvider,
      String projectType) {
    static SampleSpecDto from(SampleSpec s) {
      return new SampleSpecDto(
          s.id(), s.title(), s.category(), s.complexity(), s.preferredProvider(), s.projectType());
    }
  }

  public record PickSampleRequest(String sampleId) {}

  public record PickSampleResponse(
      String projectId, String spec, String projectType, String preferredProvider) {}

  /**
   * List available sample specs. Response excludes the full spec body (callers fetch it lazily via
   * /pick-sample).
   */
  @GetMapping("/sample-specs")
  public ResponseEntity<List<SampleSpecDto>> listSamples() {
    List<SampleSpecDto> list = sampleSpecs.listAll().stream().map(SampleSpecDto::from).toList();
    return ResponseEntity.ok(list);
  }

  /**
   * Provision a sandbox project (idempotent) and return the pre-filled spec text + preferred
   * provider so the frontend can route the user directly to {@code /runs/new?prefill=<sampleId>}.
   */
  @PostMapping("/pick-sample")
  public ResponseEntity<PickSampleResponse> pickSample(
      @AuthenticationPrincipal AppUserDetails userDetails, @RequestBody PickSampleRequest req) {
    if (req == null || req.sampleId() == null || req.sampleId().isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    SampleSpec sample = sampleSpecs.findById(req.sampleId()).orElse(null);
    if (sample == null) {
      return ResponseEntity.notFound().build();
    }
    String projectId = sandboxService.provisionIfNeeded(userDetails.getUser().getId(), sample.id());
    log.info(
        "Sample picked: userId={} sampleId={} projectId={}",
        userDetails.getUser().getId(),
        sample.id(),
        projectId);
    return ResponseEntity.ok(
        new PickSampleResponse(
            projectId, sample.spec(), sample.projectType(), sample.preferredProvider()));
  }

  /** Returns null-safe sandbox status — used by WelcomePage's redirect logic. */
  @GetMapping("/sandbox-status")
  public ResponseEntity<Map<String, Object>> sandboxStatus(
      @AuthenticationPrincipal AppUserDetails userDetails) {
    String sandboxId = sandboxService.currentSandboxId(userDetails.getUser().getId());
    return ResponseEntity.ok(
        Map.of(
            "sandboxProjectId",
            sandboxId == null ? "" : sandboxId,
            "hasSandbox",
            sandboxId != null));
  }
}
