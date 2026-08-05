package com.navneet.executespec.project.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.project.dto.CreateRepoRequest;
import com.navneet.executespec.project.dto.LocalProjectSourceDto;
import com.navneet.executespec.project.dto.LocalSourceHeartbeatRequest;
import com.navneet.executespec.project.dto.LocalSourceHeartbeatResponse;
import com.navneet.executespec.project.dto.ProjectRepoDto;
import com.navneet.executespec.project.dto.ProjectRepoLinkDto;
import com.navneet.executespec.project.dto.RegisterLocalSourceRequest;
import com.navneet.executespec.project.dto.UpdateRelationshipsRequest;
import com.navneet.executespec.project.security.ProjectAccessGuard;
import com.navneet.executespec.project.service.LocalProjectSourceService;
import com.navneet.executespec.project.service.ProjectRepoService;
import com.navneet.executespec.repo.GitHubRepoService;
import com.navneet.executespec.repo.GitHubRepoService.RemoteRepo;
import com.navneet.executespec.repo.ScmCapability;
import com.navneet.executespec.repo.ScmCredentialResolver;
import com.navneet.executespec.repo.ScmProvider;
import com.navneet.executespec.repo.ScmProviderException;
import com.navneet.executespec.repo.ScmRemoteRepository;
import com.navneet.executespec.repo.ScmRepositoryClient;
import com.navneet.executespec.repo.ScmRepositoryClientRegistry;

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

  public record RepoAccessError(
      String errorCode, String message, boolean retryable, String action) {}

  public record WebhookInstallResponse(
      boolean installed,
      String eligibilityState,
      String blockingGate,
      String errorCode,
      String reason,
      String message,
      String remediation,
      boolean retryable,
      Integer upstreamStatus) {

    static WebhookInstallResponse from(GitHubRepoService.WebhookInstallResult result) {
      return new WebhookInstallResponse(
          result.installed(),
          result.eligibilityState(),
          result.blockingGate(),
          result.errorCode(),
          result.reason(),
          result.reason(),
          result.remediation(),
          result.retryable(),
          result.upstreamStatus());
    }
  }

  private final ProjectRepoService service;
  private final LocalProjectSourceService localProjectSourceService;
  private final GitHubRepoService gitHub;
  private final ScmRepositoryClientRegistry scmClients;
  private final ScmCredentialResolver scmCredentials;
  private final ProjectAccessGuard projectAccessGuard;

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
    // No owner query means the complete PAT-visible catalogue (owner + collaborator + org), not
    // merely the user's personal namespace. An explicit owner remains a server-side filter.
    String ownerFilter = (owner != null && !owner.isBlank()) ? owner : null;
    return ResponseEntity.ok(gitHub.listOwnerRepos(ownerFilter, u.getGithubPat()));
  }

  /** Provider-neutral remote catalogue. Credentials are resolved server-side and never returned. */
  @GetMapping("/api/repos/scm")
  public ResponseEntity<List<ScmRemoteRepository>> listScmRepos(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @RequestParam("provider") String provider,
      @RequestParam(value = "owner", required = false) String owner) {
    ScmProvider selected = ScmProvider.require(provider);
    ScmRepositoryClient client = scmClients.require(selected);
    if (!client.supports(ScmCapability.CATALOG)) {
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, selected + " catalogue is not supported");
    }
    return ResponseEntity.ok(
        client.listRepositories(
            owner == null || owner.isBlank() ? null : owner.trim(),
            scmCredentials.require(userDetails.getUser().getId(), selected)));
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
    // Sync changes shared source metadata, so org-scoped repos require ORG_ADMIN authority.
    service.loadRepoForWrite(
        repoId,
        userDetails.getUser().getId(),
        userDetails.getUser().getOrgId(),
        isOrgAdmin(userDetails));
    return ResponseEntity.ok(
        service.syncRepo(
            repoId,
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            isOrgAdmin(userDetails)));
  }

  /**
   * Install the PR webhook using the caller's PAT. Success and failure both use the structured
   * admission contract; failures identify the exact server/PAT/access/upstream gate and a safe
   * remediation instead of collapsing every condition into a generic gateway error.
   */
  @PostMapping("/api/repos/{repoId}/webhook")
  public ResponseEntity<WebhookInstallResponse> installRepoWebhook(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable UUID repoId) {
    // Installing a webhook mutates both GitHub and shared source state.
    service.loadRepoForWrite(
        repoId,
        userDetails.getUser().getId(),
        userDetails.getUser().getOrgId(),
        isOrgAdmin(userDetails));
    var u = userDetails.getUser();
    GitHubRepoService.WebhookInstallResult result =
        service.installWebhook(
            repoId, u.getGithubPat(), u.getId(), u.getOrgId(), isOrgAdmin(userDetails));
    WebhookInstallResponse response = WebhookInstallResponse.from(result);
    if (result.installed()) {
      return ResponseEntity.ok(response);
    }
    return ResponseEntity.status(webhookFailureStatus(result)).body(response);
  }

  private static HttpStatus webhookFailureStatus(GitHubRepoService.WebhookInstallResult result) {
    if ("WEBHOOK_CONFIGURATION_UNAVAILABLE".equals(result.errorCode())) {
      return HttpStatus.SERVICE_UNAVAILABLE;
    }
    if ("GITHUB_RATE_LIMITED".equals(result.errorCode())) return HttpStatus.TOO_MANY_REQUESTS;
    if ("GITHUB_REPOSITORY_NOT_ACCESSIBLE".equals(result.errorCode())) {
      return HttpStatus.NOT_FOUND;
    }
    if (result.upstreamStatus() != null && result.upstreamStatus() >= 500) {
      return HttpStatus.BAD_GATEWAY;
    }
    return HttpStatus.UNPROCESSABLE_ENTITY;
  }

  @DeleteMapping("/api/repos/{repoId}")
  public ResponseEntity<Void> deleteRepo(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable UUID repoId) {
    // GA-readiness P4.1-followup — assert tenant before destructive op. service.deleteRepo
    // also calls loadRepoForWrite internally as defense in depth.
    service.loadRepoForRead(
        repoId, userDetails.getUser().getId(), userDetails.getUser().getOrgId());
    service.deleteRepo(
        repoId,
        userDetails.getUser().getId(),
        userDetails.getUser().getOrgId(),
        isOrgAdmin(userDetails));
    return ResponseEntity.noContent().build();
  }

  /* ── Project linking ── */

  /**
   * Register (or renew) an opaque local-folder connector and link it to this project. The request
   * deliberately has no path field; the server stores random client IDs and bounded metadata only.
   */
  @PostMapping("/api/projects/{projectId}/sources/local")
  public ResponseEntity<LocalProjectSourceDto> registerLocalSource(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String projectId,
      @Valid @RequestBody RegisterLocalSourceRequest request) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    LocalProjectSourceDto result =
        localProjectSourceService.register(
            projectId,
            request,
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            isOrgAdmin(userDetails));
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result);
  }

  /**
   * Renew a registered local source's lease. Path-free like registration; only the connector
   * installation that registered the folder identity can keep it ONLINE.
   */
  @PostMapping("/api/projects/{projectId}/sources/local/{localFolderId}/heartbeat")
  public ResponseEntity<LocalSourceHeartbeatResponse> heartbeatLocalSource(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String projectId,
      @PathVariable UUID localFolderId,
      @Valid @RequestBody LocalSourceHeartbeatRequest request) {
    projectAccessGuard.assertCanWrite(projectId, userDetails);
    return ResponseEntity.ok(
        localProjectSourceService.heartbeat(
            projectId,
            localFolderId,
            request,
            userDetails.getUser().getId(),
            userDetails.getUser().getOrgId(),
            isOrgAdmin(userDetails)));
  }

  @GetMapping("/api/projects/{projectId}/repos")
  public ResponseEntity<List<ProjectRepoLinkDto>> listProjectRepos(
      @AuthenticationPrincipal AppUserDetails userDetails, @PathVariable String projectId) {
    // GA-readiness P4.1-followup — tenant guard before service-level access
    // logic so the call site visibly asserts org-boundary enforcement.
    projectAccessGuard.assertCanRead(projectId, userDetails);
    return ResponseEntity.ok(
        service.listProjectRepos(
            projectId, userDetails.getUser().getId(), userDetails.getUser().getOrgId()));
  }

  @PostMapping("/api/projects/{projectId}/repos/{repoId}")
  public ResponseEntity<ProjectRepoLinkDto> linkToProject(
      @AuthenticationPrincipal AppUserDetails userDetails,
      @PathVariable String projectId,
      @PathVariable UUID repoId) {
    // GA-readiness P4.1-followup — verify caller can write to the project
    // before linking a repo to it (the service's downstream check stays as
    // defense in depth). RL-2026-05-040: was assertCanRead (let a cross-org
    // read-only member link repos); linking mutates project state → write.
    projectAccessGuard.assertCanWrite(projectId, userDetails);
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
    // GA-readiness P4.1-followup — destructive op; tenant guard up front.
    // RL-2026-05-040: write guard (was assertCanRead).
    projectAccessGuard.assertCanWrite(projectId, userDetails);
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
    // GA-readiness P4.1-followup — relationship metadata mutates project state.
    // RL-2026-05-040: write guard (was assertCanRead).
    projectAccessGuard.assertCanWrite(projectId, userDetails);
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

  /**
   * Exact SCM failure projection; never exposes provider bodies, credentials, or exception text.
   */
  @ExceptionHandler(ScmProviderException.class)
  public ResponseEntity<RepoAccessError> handleScmProviderException(ScmProviderException error) {
    HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
    boolean bitbucket = error.errorCode() != null && error.errorCode().startsWith("BITBUCKET_");
    String provider = bitbucket ? "Bitbucket" : "GitHub";
    String updateAccessAction = bitbucket ? "UPDATE_BITBUCKET_ACCESS" : "UPDATE_GITHUB_ACCESS";
    RepoAccessError body;
    if (status == HttpStatus.UNPROCESSABLE_ENTITY
        || status == HttpStatus.FORBIDDEN
        || status == HttpStatus.NOT_FOUND) {
      body =
          new RepoAccessError(
              error.errorCode(),
              "ExecuteSpec's "
                  + provider
                  + " connection cannot access this repository. Grant the stored credential"
                  + " access to it, or reconnect "
                  + provider
                  + ".",
              false,
              updateAccessAction);
    } else if (status == HttpStatus.TOO_MANY_REQUESTS) {
      body =
          new RepoAccessError(
              error.errorCode(),
              provider + " is rate-limiting repository access. Wait a moment, then retry.",
              true,
              "RETRY");
    } else {
      body =
          new RepoAccessError(
              error.errorCode(),
              provider + " repository access is temporarily unavailable. Retry shortly.",
              error.retryable(),
              "RETRY");
    }
    return ResponseEntity.status(status).body(body);
  }
}
