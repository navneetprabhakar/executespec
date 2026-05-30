package com.navneet.executespec.contracts.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.contracts.ContractDriftEvent;
import com.navneet.executespec.contracts.ContractDriftEventRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.folder.ExecutespecFolderWriter;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * E2.8 — REST surface for contract drift: check + resolve.
 *
 * <p>Three on-demand check endpoints (per-contract, per-repo, project-wide) plus two resolution
 * endpoints (refresh-from-source, dismiss).
 *
 * <p>Refresh-from-source opens a PR through {@link ExecutespecFolderWriter} to overwrite the {@code
 * .executespec/contracts/produced/<name>.<ext>} file with the current source content. Dismiss
 * requires a rationale (≥10 chars) so the audit log is meaningful.
 */
@RestController
@RequestMapping("/api/projects/{projectId}")
@RequiredArgsConstructor
@Slf4j
public class ContractDriftController {

  private final DriftDetectionService driftService;
  private final ContractRegistryRepository registryRepo;
  private final ContractDriftEventRepository driftRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ExecutespecFolderWriter folderWriter;
  private final GitHubRepoService gitHub;
  private final AppUserRepository userRepo;
  private final com.navneet.executespec.project.pr.PullRequestRecordService prRecordService;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  private final org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter>
      activityEmitterProvider;

  @PostMapping("/contracts/{contractId}/check-drift")
  public ResponseEntity<DriftDetectionService.DriftCheckResult> checkContract(
      @PathVariable String projectId, @PathVariable UUID contractId, Authentication auth) {
    projectAccessGuard.assertCanRead(projectId, principal(auth));
    return ResponseEntity.ok(
        driftService.checkContract(contractId, parseUuidOrNull(projectId), patFor(auth)));
  }

  @PostMapping("/repos/{repoId}/check-drift")
  public ResponseEntity<List<DriftDetectionService.DriftCheckResult>> checkRepo(
      @PathVariable String projectId, @PathVariable UUID repoId, Authentication auth) {
    projectAccessGuard.assertCanRead(projectId, principal(auth));
    ensureLinked(projectId, repoId);
    return ResponseEntity.ok(
        driftService.checkAllForRepo(repoId, parseUuidOrNull(projectId), patFor(auth)));
  }

  @PostMapping("/check-drift")
  public ResponseEntity<List<DriftDetectionService.DriftCheckResult>> checkProject(
      @PathVariable String projectId, Authentication auth) {
    projectAccessGuard.assertCanRead(projectId, principal(auth));
    List<UUID> repoIds =
        linkRepo.findByProjectId(projectId).stream().map(l -> l.getRepoId()).toList();
    return ResponseEntity.ok(
        driftService.checkAllForProject(parseUuidOrNull(projectId), repoIds, patFor(auth)));
  }

  @PostMapping("/contracts/{contractId}/refresh-from-source")
  public ResponseEntity<RefreshResponse> refreshFromSource(
      @PathVariable String projectId, @PathVariable UUID contractId, Authentication auth) {
    projectAccessGuard.assertCanWrite(projectId, principal(auth));
    ContractRegistry contract =
        registryRepo
            .findById(contractId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contract"));
    ProjectRepo repo =
        projectRepoRepo
            .findById(contract.getRepoId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo"));
    if (contract.getDetectedPath() == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "contract has no detected source path; cannot refresh");
    }
    String pat = patFor(auth);
    String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();
    String sourceContent = gitHub.readRepoFile(repoUrl, contract.getDetectedPath(), pat);
    if (sourceContent == null || sourceContent.startsWith("// ")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY, "could not read source: " + sourceContent);
    }
    String folderPath =
        ".executespec/contracts/produced/"
            + contract.getName()
            + DriftDetectionService.extensionFor(contract.typeEnum());
    Optional<ExecutespecFolderWriter.PullRequestInfo> pr =
        folderWriter.writeFiles(
            repo,
            null,
            Map.of(folderPath, sourceContent),
            "ExecuteSpec: refresh contract " + contract.getName() + " from source",
            "Contract drift refresh.\n\n"
                + "Source path: "
                + contract.getDetectedPath()
                + "\n"
                + "Triggered by: drift-resolution flow",
            pat);
    if (pr.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "writer returned empty");
    }

    // V169 (2026-05-02) — surface this PR in the project dashboard rollup.
    if (pr.get().openedNewPr() && pr.get().prUrl() != null) {
      try {
        prRecordService.recordCreated(
            null,
            projectId,
            repo.getId(),
            repo.getGithubRepoFullName(),
            pr.get().prUrl(),
            pr.get().branchName(),
            null,
            com.navneet.executespec.project.pr.PullRequestRecord.Kind.CONTRACT);
      } catch (Exception e) {
        log.warn(
            "[{}] could not record contract refresh PR for {}: {}",
            projectId,
            repo.getGithubRepoFullName(),
            e.getMessage());
      }
    }

    // Mark every unresolved drift event for this contract as REFRESHED
    Instant now = Instant.now();
    UUID actorUserId = parseUuidOrNull(auth.getName());
    for (ContractDriftEvent open : driftRepo.findUnresolvedByContractId(contractId)) {
      open.setResolvedAction(ContractDriftEvent.ResolvedAction.REFRESHED.name());
      open.setResolvedAt(now);
      open.setResolvedByUserId(auth.getName());
      open.setResolvedRationale("Refreshed via dashboard PR " + pr.get().prUrl());
      driftRepo.save(open);
    }
    ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
    if (emitter != null) {
      emitter.emit(
          parseUuidOrNull(projectId),
          ActivityEventType.CONTRACT_DRIFT_REFRESHED,
          actorUserId,
          "CONTRACT",
          contractId.toString(),
          "Refreshed contract " + contract.getName() + " via PR " + pr.get().prUrl(),
          Map.of("prUrl", pr.get().prUrl(), "branchName", pr.get().branchName()));
    }
    return ResponseEntity.ok(new RefreshResponse(pr.get().prUrl(), pr.get().branchName()));
  }

  @PostMapping("/contracts/{contractId}/dismiss-drift")
  public ResponseEntity<DismissResponse> dismissDrift(
      @PathVariable String projectId,
      @PathVariable UUID contractId,
      @RequestBody DismissRequest body,
      Authentication auth) {
    projectAccessGuard.assertCanWrite(projectId, principal(auth));
    if (body == null || body.rationale() == null || body.rationale().trim().length() < 10) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rationale required (≥10 chars)");
    }
    Instant now = Instant.now();
    UUID actorUserId = parseUuidOrNull(auth.getName());
    int dismissed = 0;
    for (ContractDriftEvent open : driftRepo.findUnresolvedByContractId(contractId)) {
      open.setResolvedAction(ContractDriftEvent.ResolvedAction.DISMISSED.name());
      open.setResolvedAt(now);
      open.setResolvedByUserId(auth.getName());
      open.setResolvedRationale(body.rationale().trim());
      driftRepo.save(open);
      dismissed++;
    }
    ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
    if (emitter != null) {
      emitter.emit(
          parseUuidOrNull(projectId),
          ActivityEventType.CONTRACT_DRIFT_DISMISSED,
          actorUserId,
          "CONTRACT",
          contractId.toString(),
          "Dismissed drift on contract " + contractId + " (" + dismissed + " event(s))",
          Map.of("rationale", body.rationale().trim(), "dismissedCount", dismissed));
    }
    return ResponseEntity.ok(new DismissResponse(dismissed));
  }

  private com.navneet.executespec.auth.security.AppUserDetails principal(
      org.springframework.security.core.Authentication auth) {
    if (auth != null
        && auth.getPrincipal() instanceof com.navneet.executespec.auth.security.AppUserDetails u)
      return u;
    throw new org.springframework.web.server.ResponseStatusException(
        org.springframework.http.HttpStatus.UNAUTHORIZED, "auth required");
  }

  private void ensureLinked(String projectId, UUID repoId) {
    if (linkRepo.findByProjectIdAndRepoId(projectId, repoId).isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "repo not linked to project");
    }
  }

  private String patFor(Authentication auth) {
    if (auth == null || auth.getName() == null) return null;
    return userRepo.findById(auth.getName()).map(u -> u.getGithubPat()).orElse(null);
  }

  private static UUID parseUuidOrNull(String s) {
    try {
      return s == null ? null : UUID.fromString(s);
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  public record DismissRequest(String rationale) {}

  public record DismissResponse(int dismissedCount) {}

  public record RefreshResponse(String prUrl, String branchName) {}
}
