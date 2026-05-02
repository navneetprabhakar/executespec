package com.navneet.executespec.execution.controller;

import java.util.*;

import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.entity.Org;
import com.navneet.executespec.auth.entity.Project;
import com.navneet.executespec.auth.entity.TierConfig;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.OrgRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.auth.repository.TierConfigRepository;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;
import com.navneet.executespec.execution.dto.ExecutionRunDto;
import com.navneet.executespec.execution.entity.ExecutionRun;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionRunRepository;
import com.navneet.executespec.execution.repository.ExecutionSolutionRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Project-level aggregation endpoints for the v2.4.0 Project Shell. All routes gated by the {@code
 * execution.view.enabled} feature flag + ENTERPRISE/MAX tier ({@link
 * TierConfig#isExecutionViewEnabled()}).
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ProjectLaunchController {

  private static final String FEATURE_FLAG_KEY = "execution.view.enabled";

  private final FeatureFlagService featureFlags;
  private final ExecutionRunRepository execRunRepo;
  private final ExecutionSolutionRepository solutionRepo;
  private final ProjectRepository projectRepo;
  private final OrgRepository orgRepo;
  private final AppUserRepository appUserRepo;
  private final TierConfigRepository tierConfigRepo;
  private final RestTemplate integrationRestTemplate;
  private final com.navneet.executespec.state.repository.PipelineRunRepository pipelineRunRepo;
  private final com.navneet.executespec.project.pr.PullRequestRecordRepository prRepo;

  // ── Launches list ─────────────────────────────────────────────────────

  @GetMapping("/api/projects/{projectId}/launches")
  @Transactional(readOnly = true)
  public ResponseEntity<Page<ExecutionRunDto>> listLaunches(
      @PathVariable String projectId,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireProjectAccess(projectId, user);
    // v2.5.2 — use @EntityGraph-backed finder so solutions + repoMappings
    // come back in the same query chain, avoiding the per-row solution
    // fetch that caused N+1 on the Launches list.
    Page<ExecutionRun> runs =
        execRunRepo.findListByProjectId(projectId, PageRequest.of(page, size));
    return ResponseEntity.ok(runs.map(r -> ExecutionRunDto.from(r, r.getSolutions())));
  }

  // ── PR rollup (cross-launch) ──────────────────────────────────────────

  /**
   * Wire format for the project PR rollup.
   *
   * @param launchId pipeline-run id when {@code kind=RUN}; null for system PRs (kind != RUN)
   * @param kind PR origin classifier — {@code RUN}, {@code BOOTSTRAP}, {@code ARCHITECTURE},
   *     {@code CONTRACT}, {@code CHAT_TOOL}. Frontend filters / groups by this. Added in V169
   *     (2026-05-02) so system PRs are visible alongside run/launch PRs.
   */
  public record PrRollupEntry(
      String prUrl,
      Integer prNumber,
      String repoFullName,
      String status,
      String launchId,
      String createdAt,
      String kind) {}

  @GetMapping("/api/projects/{projectId}/prs")
  @Transactional(readOnly = true)
  public ResponseEntity<List<PrRollupEntry>> projectPrs(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireProjectAccess(projectId, user);

    List<PrRollupEntry> out = new ArrayList<>();

    // Launches (ExecutionRun → ExecutionSolution.prUrl)
    Page<ExecutionRun> allRuns = execRunRepo.findListByProjectId(projectId, PageRequest.of(0, 500));
    for (ExecutionRun r : allRuns.getContent()) {
      List<ExecutionSolution> sols = r.getSolutions() == null ? List.of() : r.getSolutions();
      for (ExecutionSolution s : sols) {
        if (s.getPrUrl() == null) continue;
        out.add(
            new PrRollupEntry(
                s.getPrUrl(),
                s.getPrNumber(),
                null,
                s.getPrStatus() == null ? "OPEN" : s.getPrStatus(),
                r.getId(),
                s.getCompletedAt() == null ? null : s.getCompletedAt().toString(),
                "RUN"));
      }
    }

    // Regular project-scoped runs (PipelineRun → PullRequestRecord)
    var pipelineRuns =
        pipelineRunRepo.findByProjectIdOrderByStartedAtDesc(projectId, PageRequest.of(0, 500));
    for (var pr : pipelineRuns) {
      var records = prRepo.findByRunIdOrderByCreatedAtAsc(pr.getRunId());
      for (var rec : records) {
        out.add(
            new PrRollupEntry(
                rec.getPrUrl(),
                rec.getPrNumber(),
                rec.getRepoFullName(),
                rec.getStatus() == null ? "OPEN" : rec.getStatus().name(),
                pr.getRunId(),
                rec.getCreatedAt() == null ? null : rec.getCreatedAt().toString(),
                rec.getKind() == null ? "RUN" : rec.getKind().name()));
      }
    }

    // V169 (2026-05-02) — system PRs (BOOTSTRAP / ARCHITECTURE / CONTRACT /
    // CHAT_TOOL) aren't tied to a PipelineRun. Query directly by project_id
    // + kind != RUN. These show up next to run-scoped PRs in the dashboard
    // rollup so users can see every PR ExecuteSpec opened on their behalf.
    var systemPrs =
        prRepo.findByProjectIdAndKindNotOrderByCreatedAtDesc(
            projectId, com.navneet.executespec.project.pr.PullRequestRecord.Kind.RUN);
    for (var rec : systemPrs) {
      out.add(
          new PrRollupEntry(
              rec.getPrUrl(),
              rec.getPrNumber(),
              rec.getRepoFullName(),
              rec.getStatus() == null ? "OPEN" : rec.getStatus().name(),
              null,
              rec.getCreatedAt() == null ? null : rec.getCreatedAt().toString(),
              rec.getKind() == null ? "SYSTEM" : rec.getKind().name()));
    }

    return ResponseEntity.ok(out);
  }

  // ── Project overview rollup ───────────────────────────────────────────

  public record ProjectOverview(
      int activeLaunches,
      int completedLaunches,
      int failedLaunches,
      long totalActualTokens,
      double totalCostUsd,
      int prCount,
      int recentPrOpen,
      int recentPrMerged) {}

  @GetMapping("/api/projects/{projectId}/overview")
  @Transactional(readOnly = true)
  public ResponseEntity<ProjectOverview> overview(
      @PathVariable String projectId, @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireProjectAccess(projectId, user);

    Page<ExecutionRun> allRuns =
        execRunRepo.findByProjectIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            projectId, PageRequest.of(0, 500));
    int active = 0, complete = 0, failed = 0;
    long tokens = 0;
    double cost = 0;
    int prOpen = 0, prMerged = 0, prCount = 0;
    for (ExecutionRun r : allRuns.getContent()) {
      switch (r.getStatus() == null ? "" : r.getStatus()) {
        case "COMPLETE" -> complete++;
        case "FAILED", "ABORTED" -> failed++;
        default -> active++;
      }
      if (r.getTotalActualTokens() != null) tokens += r.getTotalActualTokens();
      if (r.getTotalCostUsd() != null) cost += r.getTotalCostUsd().doubleValue();

      List<ExecutionSolution> sols =
          solutionRepo.findByExecutionRun_IdOrderBySequenceNumberAsc(r.getId());
      for (ExecutionSolution s : sols) {
        if (s.getPrUrl() == null) continue;
        prCount++;
        if ("MERGED".equalsIgnoreCase(s.getPrStatus())) prMerged++;
        else prOpen++;
      }
    }
    return ResponseEntity.ok(
        new ProjectOverview(active, complete, failed, tokens, cost, prCount, prOpen, prMerged));
  }

  // ── Pre-flight GitHub owner access check ──────────────────────────────

  public record ValidateOwnerRequest(String githubOwner) {}

  public record ValidateOwnerResult(boolean canCreate, String reason) {}

  @PostMapping("/api/projects/{projectId}/launches/validate-repo-access")
  public ResponseEntity<ValidateOwnerResult> validateOwner(
      @PathVariable String projectId,
      @RequestBody ValidateOwnerRequest body,
      @AuthenticationPrincipal AppUserDetails principal) {
    AppUser user = requireFeatureEnabled(principal);
    requireProjectAccess(projectId, user);

    if (body == null || body.githubOwner() == null || body.githubOwner().isBlank()) {
      return ResponseEntity.ok(new ValidateOwnerResult(false, "Owner is required"));
    }
    String pat = appUserRepo.findById(user.getId()).map(AppUser::getGithubPat).orElse(null);
    if (pat == null || pat.isBlank()) {
      return ResponseEntity.ok(
          new ValidateOwnerResult(
              false, "Configure a GitHub PAT in your user settings before creating new repos"));
    }

    try {
      HttpHeaders h = new HttpHeaders();
      h.set("Authorization", "Bearer " + pat);
      h.set("Accept", "application/vnd.github+json");
      // Try org scope first, fall back to user scope on 404.
      var orgResp =
          integrationRestTemplate.exchange(
              "https://api.github.com/orgs/" + body.githubOwner(),
              HttpMethod.GET,
              new org.springframework.http.HttpEntity<>(h),
              Map.class);
      if (orgResp.getStatusCode().is2xxSuccessful()) {
        return ResponseEntity.ok(new ValidateOwnerResult(true, "Org access verified"));
      }
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound nf) {
      // Not an org — try user scope.
    } catch (Exception e) {
      return ResponseEntity.ok(
          new ValidateOwnerResult(false, "GitHub error: " + e.getClass().getSimpleName()));
    }

    try {
      HttpHeaders h = new HttpHeaders();
      h.set("Authorization", "Bearer " + pat);
      h.set("Accept", "application/vnd.github+json");
      var userResp =
          integrationRestTemplate.exchange(
              "https://api.github.com/users/" + body.githubOwner(),
              HttpMethod.GET,
              new org.springframework.http.HttpEntity<>(h),
              Map.class);
      if (userResp.getStatusCode().is2xxSuccessful()) {
        // The account exists, but if it's someone else's the PAT won't let us create.
        // We defer that check until actual create; surface an informational pass here.
        return ResponseEntity.ok(
            new ValidateOwnerResult(
                true, "User account exists — PAT permissions will be checked on create"));
      }
    } catch (Exception e) {
      return ResponseEntity.ok(
          new ValidateOwnerResult(false, "Owner not found under org or user scope"));
    }
    return ResponseEntity.ok(new ValidateOwnerResult(false, "Owner not found"));
  }

  // ── Access + feature gating (mirror of ExecutionController) ───────────

  private AppUser requireFeatureEnabled(AppUserDetails principal) {
    if (principal == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    if (!featureFlags.isEnabled(FEATURE_FLAG_KEY)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    AppUser user = principal.getUser();
    String orgId = user.getOrgId();
    String tier =
        orgId != null
            ? orgRepo.findById(orgId).map(Org::getTier).orElse(user.getTier())
            : user.getTier();
    if (tier == null) tier = "FREE";
    boolean allowed =
        tierConfigRepo.findById(tier).map(TierConfig::isExecutionViewEnabled).orElse(false);
    if (!allowed) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN, "Project Shell requires an eligible tier (ENTERPRISE or MAX)");
    }
    return user;
  }

  private Project requireProjectAccess(String projectId, AppUser user) {
    Project p =
        projectRepo
            .findById(projectId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "project"));
    if (!Objects.equals(p.getOrgId(), user.getOrgId())) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "project not in caller org");
    }
    return p;
  }
}
