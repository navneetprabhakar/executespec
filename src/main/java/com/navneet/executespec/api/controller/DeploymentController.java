package com.navneet.executespec.api.controller;

import com.navneet.executespec.api.dto.DeployRequest;
import com.navneet.executespec.api.dto.DeploymentDetail;
import com.navneet.executespec.api.dto.DeploymentSummary;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.deployment.DeploymentService;
import com.navneet.executespec.state.entity.Deployment;
import com.navneet.executespec.state.entity.RunRepo;
import com.navneet.executespec.state.repository.DeploymentRepository;
import com.navneet.executespec.state.repository.RunRepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

/**
 * Deployment CRUD endpoints. Disabled by default — set {@code agent.deployment.enabled=true} to activate.
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

    @PostMapping
    public ResponseEntity<DeploymentSummary> deploy(
            @RequestBody DeployRequest request,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireRunAccess(request.runId(), userDetails);
        try {
            RunRepo.RepoMode mode = RunRepo.RepoMode.valueOf(request.repoMode().toUpperCase());
            Deployment d = deploymentService.deploy(
                request.runId(), mode, request.repoUrl(), request.repoName());
            return ResponseEntity.accepted().body(DeploymentSummary.from(d));
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
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
            return deploymentRepo.findByRunIdOrderByCreatedAtDesc(runId)
                .stream().map(DeploymentSummary::from).toList();
        }
        // Listing all deployments requires org admin
        guard.requireOrgAdmin(userDetails);
        return deploymentRepo.findAll().stream().map(DeploymentSummary::from).toList();
    }

    @GetMapping("/{id}")
    public DeploymentDetail detail(
            @PathVariable String id,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireAuthenticated(userDetails);
        Deployment d = deploymentRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Deployment not found: " + id));
        guard.requireRunAccess(d.getRunId(), userDetails);
        RunRepo repo = (d.getRunRepoId() != null)
            ? runRepoRepo.findById(d.getRunRepoId()).orElse(null)
            : null;
        return DeploymentDetail.from(d, repo);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable String id,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        Deployment d = deploymentRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Deployment not found: " + id));
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
            @PathVariable String id,
            @AuthenticationPrincipal AppUserDetails userDetails) {
        Deployment d = deploymentRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Deployment not found: " + id));
        guard.requireRunAccess(d.getRunId(), userDetails);
        try {
            deploymentService.rollback(id);
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Rollback failed: " + e.getMessage());
        }
    }

    @GetMapping("/live")
    public List<DeploymentSummary> live(
            @AuthenticationPrincipal AppUserDetails userDetails) {
        guard.requireOrgAdmin(userDetails);
        return deploymentRepo.findByStatus(Deployment.DeployStatus.LIVE)
            .stream().map(DeploymentSummary::from).toList();
    }
}
