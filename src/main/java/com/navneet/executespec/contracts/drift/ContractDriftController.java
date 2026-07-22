package com.navneet.executespec.contracts.drift;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
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
  private final com.navneet.executespec.project.pr.PullRequestRecordService prRecordService;
  private final com.navneet.executespec.project.security.ProjectAccessGuard projectAccessGuard;
  private final org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter>
      activityEmitterProvider;

  @PostMapping("/contracts/{contractId}/check-drift")
  public ResponseEntity<DriftDetectionService.DriftCheckResult> checkContract(
      @PathVariable String projectId, @PathVariable UUID contractId, Authentication auth) {
    projectAccessGuard.assertCanWrite(projectId, principal(auth));
    ensureContractLinked(projectId, contractId);
    return ResponseEntity.ok(
        driftService.checkContract(contractId, parseUuidOrNull(projectId), patFor(auth)));
  }

  @PostMapping("/repos/{repoId}/check-drift")
  public ResponseEntity<List<DriftDetectionService.DriftCheckResult>> checkRepo(
      @PathVariable String projectId, @PathVariable UUID repoId, Authentication auth) {
    projectAccessGuard.assertCanWrite(projectId, principal(auth));
    ensureLinked(projectId, repoId);
    return ResponseEntity.ok(
        driftService.checkAllForRepo(repoId, parseUuidOrNull(projectId), patFor(auth)));
  }

  @PostMapping("/check-drift")
  public ResponseEntity<List<DriftDetectionService.DriftCheckResult>> checkProject(
      @PathVariable String projectId, Authentication auth) {
    projectAccessGuard.assertCanWrite(projectId, principal(auth));
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
    ensureLinked(projectId, contract.getRepoId());
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
    var sourceRead = gitHub.readRepoFile(repoUrl, contract.getDetectedPath(), pat);
    // RR-1631 — failure is out-of-band now. A proto/GraphQL source file whose first line is
    // `// Copyright …` is a successful read and stays refreshable; only a real transport/auth/
    // missing-file failure raises here, and the response carries a code, never file bytes.
    if (!sourceRead.successful()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_GATEWAY,
          "could not read source: " + DriftDetectionService.describe(sourceRead));
    }
    String sourceContent = sourceRead.content();
    // RR-525 — contract names are source/customer-derived strings, not a constrained filename
    // type. A name such as `../../../.github/workflows/release` would give this narrow "refresh
    // one contract" action repository-wide write authority. Derive a bounded slug instead of
    // interpolating the raw name, and let the folder writer re-validate the final path.
    String folderPath =
        ".executespec/contracts/produced/"
            + safeContractFileSlug(contract.getName(), contract.getId().toString())
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
    // RR-524 — attribute resolution + activity to the immutable actor id, not the email.
    String actorId = resolvedUserId(auth);
    UUID actorUserId = parseUuidOrNull(actorId);
    for (ContractDriftEvent open : driftRepo.findUnresolvedByContractId(contractId)) {
      open.setResolvedAction(ContractDriftEvent.ResolvedAction.REFRESHED.name());
      open.setResolvedAt(now);
      open.setResolvedByUserId(actorId);
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
    ensureContractLinked(projectId, contractId);
    if (body == null || body.rationale() == null || body.rationale().trim().length() < 10) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rationale required (≥10 chars)");
    }
    Instant now = Instant.now();
    // RR-524 — attribute resolution + activity to the immutable actor id, not the email.
    String actorId = resolvedUserId(auth);
    UUID actorUserId = parseUuidOrNull(actorId);
    int dismissed = 0;
    for (ContractDriftEvent open : driftRepo.findUnresolvedByContractId(contractId)) {
      open.setResolvedAction(ContractDriftEvent.ResolvedAction.DISMISSED.name());
      open.setResolvedAt(now);
      open.setResolvedByUserId(actorId);
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
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "contract or repo not found");
    }
  }

  private void ensureContractLinked(String projectId, UUID contractId) {
    ContractRegistry contract =
        registryRepo
            .findById(contractId)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "contract or repo not found"));
    ensureLinked(projectId, contract.getRepoId());
  }

  private String patFor(Authentication auth) {
    // RR-524 — auth.getName() is AppUserDetails.getUsername(), i.e. the user's EMAIL, not the id
    // used as the AppUser primary key. userRepo.findById(email) therefore always returned null, so
    // every private-repository drift check / refresh silently passed a null PAT to SCM and failed
    // (a public-repo smoke hid it). Resolve the decrypted PAT from the already-authenticated
    // principal instead.
    return principal(auth).getUser().getGithubPat();
  }

  /**
   * RR-524 — the immutable actor id (UUID string), never the email, for resolution/audit writes.
   */
  private String resolvedUserId(Authentication auth) {
    return principal(auth).getUser().getId();
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

  /**
   * RR-525 — bound a customer/source-derived contract name to a single safe path segment. Anything
   * outside {@code [A-Za-z0-9._-]} is collapsed; leading dots, empty results and over-long names
   * fall back to the contract's own immutable id, so the produced filename can never contain a
   * separator, dot segment or control character.
   */
  static String safeContractFileSlug(String contractName, String contractId) {
    String slug = contractName == null ? "" : contractName.replaceAll("[^A-Za-z0-9._-]", "-");
    slug = slug.replaceAll("^[.-]+", "");
    if (slug.length() > 100) slug = slug.substring(0, 100);
    slug = slug.replaceAll("[.-]+$", "");
    if (slug.isBlank()) return "contract-" + contractId;
    return slug;
  }
}
