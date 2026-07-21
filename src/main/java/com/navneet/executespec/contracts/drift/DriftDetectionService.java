package com.navneet.executespec.contracts.drift;

import java.time.Instant;
import java.util.ArrayList;
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
import com.navneet.executespec.contracts.detection.DetectorUtils;
import com.navneet.executespec.folder.ExecutespecFolderWriter;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.8 — drift between {@code .executespec/contracts/produced/&lt;name&gt;.&lt;ext&gt;} (what
 * customer git currently contains) and the source path the contract was originally auto-detected
 * from.
 *
 * <p>Three entry points:
 *
 * <ul>
 *   <li>{@link #checkContract} — single contract check; persists a {@link ContractDriftEvent} when
 *       content hashes differ
 *   <li>{@link #checkAllForRepo} — fan-out across every contract owned by a repo (drives the "Check
 *       drift on this repo" coverage-panel button)
 *   <li>{@link #checkAllForProject} — fan-out across every linked repo (drives the "Check drift
 *       across all repos" Overview button)
 * </ul>
 *
 * <p>Resolution endpoints (refresh / dismiss) are handled by {@link
 * com.navneet.executespec.contracts.drift.ContractDriftController}.
 *
 * <p>Best-effort throughout — failure on a single contract logs + continues so the drift sweep
 * doesn't abort on the first GitHub blip.
 */
@Service
@Slf4j
public class DriftDetectionService {

  private final ContractRegistryRepository registryRepo;
  private final ContractDriftEventRepository driftRepo;
  private final ProjectRepoRepository projectRepoRepo;
  private final ExecutespecFolderWriter folderWriter;
  private final GitHubRepoService gitHub;
  private final org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter>
      activityEmitterProvider;

  public DriftDetectionService(
      ContractRegistryRepository registryRepo,
      ContractDriftEventRepository driftRepo,
      ProjectRepoRepository projectRepoRepo,
      ExecutespecFolderWriter folderWriter,
      GitHubRepoService gitHub,
      org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter>
          activityEmitterProvider) {
    this.registryRepo = registryRepo;
    this.driftRepo = driftRepo;
    this.projectRepoRepo = projectRepoRepo;
    this.folderWriter = folderWriter;
    this.gitHub = gitHub;
    this.activityEmitterProvider = activityEmitterProvider;
  }

  public DriftCheckResult checkContract(UUID contractId, UUID projectId, String actorPat) {
    ContractRegistry contract = registryRepo.findById(contractId).orElse(null);
    if (contract == null) {
      return DriftCheckResult.unavailable(contractId, "contract not found");
    }
    return runCheck(contract, projectId, actorPat);
  }

  public List<DriftCheckResult> checkAllForRepo(UUID repoId, UUID projectId, String actorPat) {
    List<ContractRegistry> contracts = registryRepo.findByRepoId(repoId);
    List<DriftCheckResult> out = new ArrayList<>();
    for (ContractRegistry c : contracts) {
      try {
        out.add(runCheck(c, projectId, actorPat));
      } catch (Exception ex) {
        log.warn(
            "[DriftDetectionService] check failed for contract {}: {}", c.getId(), ex.getMessage());
        out.add(DriftCheckResult.unavailable(c.getId(), "check failed: " + ex.getMessage()));
      }
    }
    return out;
  }

  public List<DriftCheckResult> checkAllForProject(
      UUID projectId, List<UUID> repoIds, String actorPat) {
    List<DriftCheckResult> all = new ArrayList<>();
    for (UUID repoId : repoIds) {
      all.addAll(checkAllForRepo(repoId, projectId, actorPat));
    }
    return all;
  }

  DriftCheckResult runCheck(ContractRegistry contract, UUID projectId, String actorPat) {
    ProjectRepo repo = projectRepoRepo.findById(contract.getRepoId()).orElse(null);
    if (repo == null) {
      return DriftCheckResult.unavailable(contract.getId(), "repo not found");
    }
    // Read .executespec/contracts/produced/<name>.<ext> from customer git
    String folderPath =
        ".executespec/contracts/produced/" + contract.getName() + extensionFor(contract.typeEnum());
    Optional<ExecutespecFolderWriter.FileSnapshot> folderContent =
        folderWriter.readFile(repo, folderPath, actorPat);
    if (folderContent.isEmpty()) {
      // .executespec/ not yet bootstrapped — there is nothing to compare against, so we cannot
      // claim "no drift"; this is an unverified state (RR-1631).
      return DriftCheckResult.unavailable(
          contract.getId(), "no .executespec/ snapshot yet (bootstrap pending?)");
    }
    // Read the live source file from the customer repo
    if (contract.getDetectedPath() == null) {
      return DriftCheckResult.unavailable(contract.getId(), "no detected source path on contract");
    }
    String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();
    GitHubRepoService.RepoFileResult source =
        gitHub.readRepoFile(repoUrl, contract.getDetectedPath(), actorPat);
    if (!source.successful()) {
      // RR-1631 — a GitHub outage / expired PAT / rate limit is NOT "no drift". Report it as an
      // explicitly unverified check so callers can distinguish it from a verified clean result.
      return DriftCheckResult.unavailable(
          contract.getId(), "could not read source path: " + describe(source));
    }
    String sourceContent = source.content();
    String folderHash = folderContent.get().contentHash();
    String sourceHash = DetectorUtils.sha256(sourceContent);
    if (folderHash != null && folderHash.equals(sourceHash)) {
      // No drift — quiet success per plan §2.8.
      return new DriftCheckResult(contract.getId(), false, "no drift", null, Status.NO_DRIFT);
    }

    // Drift detected — persist event, fire activity feed, return id.
    String diffSummary =
        "content hash differs: snapshot="
            + truncateHash(folderHash)
            + " vs source="
            + truncateHash(sourceHash);
    Optional<ContractDriftEvent> existingOpenEvent =
        driftRepo.findUnresolvedByContractId(contract.getId()).stream()
            .filter(event -> diffSummary.equals(event.getSourceDiff()))
            .findFirst();
    if (existingOpenEvent.isPresent()) {
      return new DriftCheckResult(
          contract.getId(), true, diffSummary, existingOpenEvent.get().getId(), Status.DRIFT);
    }
    ContractDriftEvent event =
        ContractDriftEvent.builder()
            .id(UUID.randomUUID())
            .contractId(contract.getId())
            .detectedAt(Instant.now())
            .sourceDiff(diffSummary)
            .build();
    ContractDriftEvent saved = driftRepo.save(event);
    ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
    if (emitter != null) {
      emitter.emit(
          projectId,
          ActivityEventType.CONTRACT_DRIFT_DETECTED,
          null,
          "CONTRACT",
          contract.getId().toString(),
          "Drift detected on " + contract.getName() + " in " + repo.getGithubRepoFullName(),
          Map.of(
              "repoFullName",
              repo.getGithubRepoFullName(),
              "contractName",
              contract.getName(),
              "sourcePath",
              contract.getDetectedPath(),
              "diff",
              diffSummary));
    }
    return new DriftCheckResult(contract.getId(), true, diffSummary, saved.getId(), Status.DRIFT);
  }

  /**
   * RR-1631 — the string-sentinel matching this method used to perform is GONE. {@code
   * GitHubRepoService.readRepoFile} now signals failure out-of-band via {@code
   * RepoFileResult.successful()}, so a valid proto / GraphQL / C-style source file whose first line
   * is {@code // Copyright …} can no longer be misread as an unreadable one (which permanently
   * reported "no drift" and permanently failed refresh with {@code BAD_GATEWAY}).
   *
   * <p>This renders the failure code for operator-facing messages; it never inspects content.
   */
  static String describe(GitHubRepoService.RepoFileResult result) {
    if (result == null) return "(no result)";
    if (result.successful()) return "ok";
    return result.errorCode()
        + (result.upstreamStatus() == null ? "" : " (status " + result.upstreamStatus() + ")");
  }

  static String extensionFor(ContractRegistry.ContractType type) {
    return switch (type) {
      case OPENAPI, ASYNCAPI -> ".yaml";
      case PROTO -> ".proto";
      case GRAPHQL -> ".graphql";
      case JSON_SCHEMA -> ".schema.json";
      case EVENT_SCHEMA -> ".json";
    };
  }

  private static String truncate(String s) {
    if (s == null) return "(null)";
    return s.length() > 80 ? s.substring(0, 80) + "…" : s;
  }

  private static String truncateHash(String hash) {
    if (hash == null) return "(null)";
    return hash.length() > 12 ? hash.substring(0, 12) : hash;
  }

  /**
   * Outcome of a drift check.
   *
   * <p>RR-1631 — {@code hasDrift=false} used to mean BOTH "verified identical" and "we could not
   * check", so a GitHub outage, an expired PAT or a missing snapshot all rendered as a confident
   * clean result. {@link #UNAVAILABLE} is the third, distinct state.
   */
  public enum Status {
    /** Snapshot and source were both read and their hashes match. */
    NO_DRIFT,
    /** Snapshot and source were both read and they differ. */
    DRIFT,
    /** The check could not be performed — nothing may be concluded about drift. */
    UNAVAILABLE
  }

  /**
   * Result of a single contract drift check.
   *
   * @param contractId the contract that was checked
   * @param hasDrift true when the snapshot diverges from the source. Retained for wire
   *     compatibility; it is false for BOTH {@link Status#NO_DRIFT} and {@link Status#UNAVAILABLE},
   *     so consult {@link #status} before treating it as a clean verdict.
   * @param summary human-readable summary of the result
   * @param eventId {@code contract_drift_event.id} when an event was persisted; null when there was
   *     no drift OR the check couldn't run (no source path, no snapshot)
   * @param status the tri-state verdict — the authoritative field
   */
  public record DriftCheckResult(
      UUID contractId, boolean hasDrift, String summary, UUID eventId, Status status) {

    /** The check could not be performed; nothing may be concluded about drift. */
    static DriftCheckResult unavailable(UUID contractId, String summary) {
      return new DriftCheckResult(contractId, false, summary, null, Status.UNAVAILABLE);
    }
  }
}
