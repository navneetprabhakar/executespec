package com.navneet.executespec.project.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.dto.CreateRepoRequest;
import com.navneet.executespec.project.dto.ProjectRepoDto;
import com.navneet.executespec.project.dto.ProjectRepoLinkDto;
import com.navneet.executespec.project.dto.UpdateRelationshipsRequest;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubRepoService.RemoteRepo;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

/**
 * Repo lifecycle at {@code /api/repos} (scope-owned: ORG or USER), separate from project linking at
 * {@code /api/projects/{id}/repos/{repoId}}.
 */
@RestController
@RequiredArgsConstructor
public class ProjectRepoController {

  private final ProjectRepoService service;
  private final GitHubRepoService gitHub;

  /**
   * Lists every repo the caller's GitHub PAT can see (owned + collaborator + org-member).
   * Optionally filtered by {@code owner}. Returns lightweight snapshots so the AddRepoModal "Pick
   * existing" tab can show the real GitHub catalogue, not just repos already registered in our DB.
   */
  @GetMapping("/api/repos/github")
  public ResponseEntity<List<RemoteRepo>> listGitHubRepos(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(value = "owner", required = false) String owner) {
    var u = userDetails.getUser();
    String effectiveOwner = (owner != null && !owner.isBlank()) ? owner : u.getGithubOwner();
    return ResponseEntity.ok(gitHub.listOwnerRepos(effectiveOwner, u.getGithubPat()));
  }

  /* ── Lifecycle ── */

  @PostMapping("/api/repos")
  public ResponseEntity<ProjectRepoDto> createRepo(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @Valid @RequestBody CreateRepoRequest req) {
    ProjectRepoDto dto =
        service.createRepo(
            req,
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            isOrgAdmin(userDetails));
    return ResponseEntity.status(HttpStatus.CREATED).body(dto);
  }

  @GetMapping("/api/repos")
  public ResponseEntity<List<ProjectRepoDto>> listRepos(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam(value = "scope", required = false) String scope) {
    return ResponseEntity.ok(
        service.listAvailable(
            userDetails.getUser().getId(), userDetails.getUser().getOrgId(), scope));
  }

  @PostMapping("/api/repos/{repoId}/sync")
  public ResponseEntity<ProjectRepoDto> syncRepo(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable UUID repoId) {
    return ResponseEntity.ok(
        service.syncRepo(
            repoId,
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            isOrgAdmin(userDetails),
            userDetails.getUser().getGithubPat()));
  }

  /**
   * v2.5.2 — install the PR webhook on this repo using the caller's PAT. Returns {@code {installed:
   * true}} on success or {@code {installed: false, reason}} when install failed (no PAT admin,
   * network, etc.).
   */
  @PostMapping("/api/repos/{repoId}/webhook")
  public ResponseEntity<java.util.Map<String, Object>> installRepoWebhook(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable UUID repoId) {
    var u = userDetails.getUser();
    boolean ok = service.installWebhook(repoId, u.getGithubPat(), u.getId(), u.getOrgId());
    if (ok) {
      return ResponseEntity.ok(java.util.Map.of("installed", true));
    }
    // Upstream (GitHub API) rejected — typically missing `admin:repo_hook` scope or
    // the repo is archived. 502 communicates "we talked to upstream and it said no"
    // so API consumers don't treat this as success. Closes adversarial-test-report
    // Finding 9.
    return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_GATEWAY)
        .body(
            java.util.Map.of(
                "installed",
                false,
                "reason",
                "GitHub API rejected webhook install — check PAT has admin:repo_hook"));
  }

  @DeleteMapping("/api/repos/{repoId}")
  public ResponseEntity<Void> deleteRepo(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable UUID repoId) {
    service.deleteRepo(
        repoId,
        userDetails.getUser().getId(),
        userDetails.getUser().getOrgId(),
        isOrgAdmin(userDetails));
    return ResponseEntity.noContent().build();
  }

  /* ── Project linking ── */

  @GetMapping("/api/projects/{projectId}/repos")
  public ResponseEntity<List<ProjectRepoLinkDto>> listProjectRepos(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String projectId) {
    return ResponseEntity.ok(
        service.listProjectRepos(
            projectId, userDetails.getUser().getId(), userDetails.getUser().getOrgId()));
  }

  @PostMapping("/api/projects/{projectId}/repos/{repoId}")
  public ResponseEntity<ProjectRepoLinkDto> linkToProject(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String projectId,
      @PathVariable UUID repoId) {
    ProjectRepoLinkDto dto =
        service.linkToProject(
            projectId, repoId, userDetails.getUser().getId(), userDetails.getUser().getOrgId());
    return ResponseEntity.status(HttpStatus.CREATED).body(dto);
  }

  @DeleteMapping("/api/projects/{projectId}/repos/{repoId}")
  public ResponseEntity<Void> unlinkFromProject(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String projectId,
      @PathVariable UUID repoId) {
    service.unlinkFromProject(
        projectId, repoId, userDetails.getUser().getId(), userDetails.getUser().getOrgId());
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/api/projects/{projectId}/repos/{repoId}/relationships")
  public ResponseEntity<ProjectRepoLinkDto> updateRelationships(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String projectId,
      @PathVariable UUID repoId,
      @RequestBody UpdateRelationshipsRequest req) {
    return ResponseEntity.ok(
        service.updateRelationships(
            projectId,
            repoId,
            req.relationships(),
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId()));
  }

  private static boolean isOrgAdmin(AppUserDetails userDetails) {
    return "SUPER_ADMIN".equals(userDetails.getUser().getGlobalRole())
        || (userDetails.getOrgMember() != null
            && "ORG_ADMIN".equals(userDetails.getOrgMember().getOrgRole()));
  }
}
