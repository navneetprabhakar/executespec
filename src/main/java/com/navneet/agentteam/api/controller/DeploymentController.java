package com.navneet.agentteam.api.controller;

import com.navneet.agentteam.api.dto.DeployRequest;
import com.navneet.agentteam.api.dto.DeploymentDetail;
import com.navneet.agentteam.api.dto.DeploymentSummary;
import com.navneet.agentteam.deployment.DeploymentService;
import com.navneet.agentteam.state.entity.Deployment;
import com.navneet.agentteam.state.entity.RunRepo;
import com.navneet.agentteam.state.repository.DeploymentRepository;
import com.navneet.agentteam.state.repository.RunRepoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @PostMapping
    public ResponseEntity<DeploymentSummary> deploy(@RequestBody DeployRequest request) {
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
    public List<DeploymentSummary> list(@RequestParam(required = false) String runId) {
        List<Deployment> list = (runId != null)
            ? deploymentRepo.findByRunIdOrderByCreatedAtDesc(runId)
            : deploymentRepo.findAll();
        return list.stream().map(DeploymentSummary::from).toList();
    }

    @GetMapping("/{id}")
    public DeploymentDetail detail(@PathVariable String id) {
        Deployment d = deploymentRepo.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Deployment not found: " + id));
        RunRepo repo = (d.getRunRepoId() != null)
            ? runRepoRepo.findById(d.getRunRepoId()).orElse(null)
            : null;
        return DeploymentDetail.from(d, repo);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        try {
            deploymentService.deleteDeployment(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/{id}/rollback")
    public ResponseEntity<Void> rollback(@PathVariable String id) {
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
    public List<DeploymentSummary> live() {
        return deploymentRepo.findByStatus(Deployment.DeployStatus.LIVE)
            .stream().map(DeploymentSummary::from).toList();
    }
}
