package com.navneet.executespec.api.controller;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.navneet.executespec.api.dto.DeployConfirmRequest;
import com.navneet.executespec.api.dto.DeployRequest;
import com.navneet.executespec.api.dto.DeploymentDetail;
import com.navneet.executespec.api.dto.DeploymentSummary;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.auth.service.TierPermissionService;
import com.navneet.executespec.deployment.DeploymentService;
import com.navneet.executespec.deployment.WorkspaceRestoreService;
import com.navneet.executespec.deployment.analyzer.DeploymentAnalyzer;
import com.navneet.executespec.deployment.analyzer.DeploymentDecisionEngine;
import com.navneet.executespec.deployment.analyzer.DeploymentPath;
import com.navneet.executespec.deployment.analyzer.InfrastructureManifest;
import com.navneet.executespec.deployment.credential.CredentialService;
import com.navneet.executespec.deployment.engine.DeployEngineOrchestrator;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.entity.RunRepo;
import com.navneet.executespec.state.repository.DeploymentRepository;
import com.navneet.executespec.state.repository.RunRepoRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Deployment CRUD endpoints. Disabled by default — set {@code agent.deployment.enabled=true} to
 * activate.
 */
@RestController
@RequestMapping("/api/deployments")
@ConditionalOnProperty(name = "agent.deployment.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DeploymentController {

  private final DeploymentService deploymentService;
  private final DeploymentRepository deploymentRepo;
  private final RunRepoRepository runRepoRepo;
  private final RunAccessGuard guard;
  private final DeploymentAnalyzer deploymentAnalyzer;
  private final DeploymentDecisionEngine decisionEngine;
  private final WorkspaceRestoreService workspaceRestore;
  private final StateStore stateStore;
  private final CredentialService credentialService;
  private final DeployEngineOrchestrator deployEngine;
  private final TierPermissionService tierPermissionService;

  @PostMapping
  public ResponseEntity<DeploymentSummary> deploy(
      @RequestBody DeployRequest request, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(request.runId(), userDetails);
    // [F-31] (audit Sprint 2, 2026-04-30) — server-side tier gate.
    // Was: only `RunAccessGuard.requireRunAccess` ran, which only
    // checks run ownership. With `freeUserPermissions` returning
    // `canDeploy=true, maxDeployments=3` for FREE pre-PR-3 (now
    // tier_config-driven via [F-03]), a curl-bypass deploy from a
    // FREE-tier user with a valid runId would have succeeded even
    // when `tier_config.FREE.can_deploy=false`. Sidebar-only gating
    // hid this from the UI but not from the API. Now consults the
    // permission map (which post-F-03 reads tier_config) and 403s
    // on canDeploy=false. Quota enforcement stays in DeploymentService
    // (per-user maxDeployments check).
    if (userDetails != null) {
      var perms = tierPermissionService.getPermissions(userDetails.getUser().getId());
      if (!perms.canDeploy()) {
        throw new ResponseStatusException(
            HttpStatus.FORBIDDEN,
            "Deployment is not available on your current tier. Upgrade to enable.");
      }
    }
    try {
      RunRepo.RepoMode mode = RunRepo.RepoMode.valueOf(request.repoMode().toUpperCase());
      Deployment d =
          deploymentService.deploy(request.runId(), mode, request.repoUrl(), request.repoName());
      return ResponseEntity.accepted().body(DeploymentSummary.from(d));
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid repoMode: " + request.repoMode() + ". Must be NEW, EXISTING, or NONE.");
    }
  }

  @GetMapping
  public List<DeploymentSummary> list(
      @RequestParam(required = false) String runId,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    if (runId != null) {
      guard.requireRunAccess(runId, userDetails);
      return deploymentRepo.findByRunIdOrderByCreatedAtDesc(runId).stream()
          .map(DeploymentSummary::from)
          .toList();
    }
    // Listing all deployments requires org admin
    guard.requireOrgAdmin(userDetails);
    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      return deploymentRepo.findAll().stream().map(DeploymentSummary::from).toList();
    }
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null || orgId.isBlank()) {
      return List.of();
    }
    return deploymentRepo.findByOrgIdOrderByCreatedAtDesc(orgId).stream()
        .map(DeploymentSummary::from)
        .toList();
  }

  @GetMapping("/{id}")
  public DeploymentDetail detail(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireAuthenticated(userDetails);
    Deployment d =
        deploymentRepo
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Deployment not found: " + id));
    guard.requireRunAccess(d.getRunId(), userDetails);
    RunRepo repo =
        (d.getRunRepoId() != null) ? runRepoRepo.findById(d.getRunRepoId()).orElse(null) : null;
    return DeploymentDetail.from(d, repo);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    Deployment d =
        deploymentRepo
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Deployment not found: " + id));
    guard.requireRunAccess(d.getRunId(), userDetails);
    try {
      deploymentService.deleteDeployment(id);
      return ResponseEntity.noContent().build();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
    }
  }

  @PostMapping("/{id}/rollback")
  public ResponseEntity<Void> rollback(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    Deployment d =
        deploymentRepo
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Deployment not found: " + id));
    guard.requireRunAccess(d.getRunId(), userDetails);
    try {
      deploymentService.rollback(id);
      return ResponseEntity.ok().build();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Rollback failed: " + e.getMessage());
    }
  }

  @GetMapping("/live")
  public List<DeploymentSummary> live(@AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireOrgAdmin(userDetails);
    if ("SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())) {
      return deploymentRepo.findByStatus(Deployment.DeployStatus.LIVE).stream()
          .map(DeploymentSummary::from)
          .toList();
    }
    String orgId = userDetails.getUser().getOrgId();
    if (orgId == null || orgId.isBlank()) {
      return List.of();
    }
    return deploymentRepo
        .findByStatusAndOrgIdOrderByCreatedAtDesc(Deployment.DeployStatus.LIVE, orgId)
        .stream()
        .map(DeploymentSummary::from)
        .toList();
  }

  /**
   * Analyze a run's workspace to determine infrastructure requirements and available deployment
   * paths.
   */
  @PostMapping("/analyze")
  public ResponseEntity<?> analyze(
      @RequestParam String runId, @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(runId, userDetails);

    PipelineRun run =
        stateStore
            .findRun(runId)
            .orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Run not found: " + runId));
    if (!"COMPLETE".equals(run.getStatus()) && !"FAILED".equals(run.getStatus())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Run must be complete before analyzing for deployment. Current status: "
              + run.getStatus());
    }

    // Ensure workspace is available (restore from GCS if needed)
    Path workspace;
    try {
      workspace = workspaceRestore.ensureWorkspaceAvailable(runId);
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Failed to access workspace: " + e.getMessage());
    }

    // Run analysis
    InfrastructureManifest manifest = deploymentAnalyzer.analyze(workspace);
    DeploymentDecisionEngine.DeploymentDecision decision = decisionEngine.decide(manifest);

    // Check if user has GCP credentials
    String userId = userDetails.getUser().getId();
    boolean hasCredentials = credentialService.getCredentialSummary(userId).isPresent();

    return ResponseEntity.ok(
        Map.of(
            "manifest", manifest,
            "decision", decision,
            "hasGcpCredentials", hasCredentials));
  }

  // Deploy Engine: Confirm & Deploy

  /**
   * Confirm a deployment after analysis. The user has chosen a path and provided environment
   * variables. Initiates the async deployment pipeline.
   */
  @PostMapping("/confirm")
  public ResponseEntity<DeploymentSummary> confirm(
      @RequestBody DeployConfirmRequest request,
      @AuthenticationPrincipal AppUserDetails userDetails) {
    guard.requireRunAccess(request.runId(), userDetails);
    String userId = userDetails.getUser().getId();

    try {
      DeploymentPath path = DeploymentPath.valueOf(request.deploymentPath());

      if (path != DeploymentPath.CLOUD_RUN_DIRECT) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Only CLOUD_RUN_DIRECT is currently supported. "
                + "COMPOSE_ON_VM and MANAGED_INFRASTRUCTURE coming soon.");
      }

      String region = request.region() != null ? request.region() : "us-central1";
      int port = request.appPort() != null ? request.appPort() : 8080;

      Deployment d =
          deployEngine.deployCloudRunDirect(
              request.runId(),
              userId,
              region,
              request.envVars(),
              request.manifestJson(),
              request.healthCheckPath(),
              port);

      return ResponseEntity.accepted().body(DeploymentSummary.from(d));

    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid deploymentPath: "
              + request.deploymentPath()
              + ". Must be CLOUD_RUN_DIRECT, COMPOSE_ON_VM, or MANAGED_INFRASTRUCTURE.");
    }
  }

  /**
   * Rollback a deploy-engine deployment to a previous revision. Uses user's GCP credentials (not
   * system ADC).
   */
  @PostMapping("/{id}/engine-rollback")
  public ResponseEntity<Void> engineRollback(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    Deployment d =
        deploymentRepo
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Deployment not found: " + id));
    guard.requireRunAccess(d.getRunId(), userDetails);

    try {
      deployEngine.rollback(id, userDetails.getUser().getId());
      return ResponseEntity.ok().build();
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Rollback failed: " + e.getMessage());
    }
  }

  /**
   * Destroy a deploy-engine deployment — delete Cloud Run service and image from the user's GCP
   * project.
   */
  @DeleteMapping("/{id}/engine-destroy")
  public ResponseEntity<Void> engineDestroy(
      @PathVariable String id, @AuthenticationPrincipal AppUserDetails userDetails) {
    Deployment d =
        deploymentRepo
            .findById(id)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Deployment not found: " + id));
    guard.requireRunAccess(d.getRunId(), userDetails);

    try {
      deployEngine.destroy(id, userDetails.getUser().getId());
      return ResponseEntity.noContent().build();
    } catch (IllegalStateException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    } catch (Exception e) {
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Destroy failed: " + e.getMessage());
    }
  }
}
