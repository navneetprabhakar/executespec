package com.navneet.executespec.contracts.detection;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.ContractVersion;
import com.navneet.executespec.contracts.ContractVersionRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * E2.2 — drives the full file-based detection pipeline for a single
 * {@link ProjectRepo}. Orchestration only — every format-specific
 * decision lives in its {@link FileBasedContractDetector} implementation.
 *
 * <h2>Flow</h2>
 *
 * <ol>
 *   <li>List the repo's files via the supplied {@code FileLister} callback
 *       (in production this is {@code GitHubRepoService.listRepoFiles}).</li>
 *   <li>Run each detector against the file list with a content loader that
 *       fetches per-file bytes lazily.</li>
 *   <li>For each {@link DetectedContract}: upsert into
 *       {@code contract_registry} keyed by {@code (repo_id, name)}, then
 *       insert a new {@code contract_version} row when the content hash
 *       differs from the previously stored hash.</li>
 *   <li>Emit an activity event per first-detection ({@code CONTRACT_DETECTED})
 *       and per version change ({@code CONTRACT_VERSION_CHANGED}).</li>
 * </ol>
 *
 * <p>The actual GitHub HTTP calls are injected via callbacks ({@code
 * FileLister} + {@link FileBasedContractDetector.ContentLoader}) so this
 * service can be tested without network.
 */
@Service
@Slf4j
public class ContractDetectionService {

    private final List<FileBasedContractDetector> detectors;
    private final ContractRegistryRepository registryRepo;
    private final ContractVersionRepository versionRepo;
    private final ProjectRepoRepository projectRepoRepo;
    private final org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter> activityEmitterProvider;

    public ContractDetectionService(List<FileBasedContractDetector> detectors,
                                    ContractRegistryRepository registryRepo,
                                    ContractVersionRepository versionRepo,
                                    ProjectRepoRepository projectRepoRepo,
                                    org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter> activityEmitterProvider) {
        this.detectors = detectors;
        this.registryRepo = registryRepo;
        this.versionRepo = versionRepo;
        this.projectRepoRepo = projectRepoRepo;
        this.activityEmitterProvider = activityEmitterProvider;
    }

    /**
     * Run all configured detectors against a repo + persist results.
     * Returns a per-detector summary so {@code E2.3} coverage backend can
     * surface "what was scanned, what was found, what was skipped".
     *
     * @param repoId       target {@link ProjectRepo}
     * @param projectId    project context (used for activity feed scoping)
     * @param fileLister   callback returning the repo's full file path list
     * @param loader       per-file content loader
     */
    public List<DetectionResult> runForRepo(UUID repoId,
                                            UUID projectId,
                                            FileLister fileLister,
                                            FileBasedContractDetector.ContentLoader loader) {
        ProjectRepo repo = projectRepoRepo.findById(repoId).orElse(null);
        if (repo == null) {
            log.warn("[ContractDetectionService] repo {} not found", repoId);
            return List.of();
        }
        List<String> files;
        try {
            files = fileLister.list();
            if (files == null) files = List.of();
        } catch (Exception ex) {
            log.warn("[ContractDetectionService] file list failed for {}: {}",
                    repo.getGithubRepoFullName(), ex.getMessage());
            return List.of();
        }

        List<DetectionResult> summary = new ArrayList<>();
        for (FileBasedContractDetector detector : detectors) {
            try {
                List<DetectedContract> found = detector.detect(files, loader);
                List<UUID> contractIds = new ArrayList<>();
                for (DetectedContract dc : found) {
                    UUID cid = upsertContract(repo, projectId, dc);
                    if (cid != null) contractIds.add(cid);
                }
                summary.add(new DetectionResult(
                        detector.detectorName(),
                        detector.type(),
                        detector.filePatterns(),
                        DetectionResult.Status.SCANNED,
                        contractIds,
                        null));
            } catch (Exception ex) {
                log.warn("[ContractDetectionService] detector {} failed: {}",
                        detector.detectorName(), ex.getMessage());
                summary.add(new DetectionResult(
                        detector.detectorName(),
                        detector.type(),
                        detector.filePatterns(),
                        DetectionResult.Status.FAILED,
                        List.of(),
                        ex.getMessage()));
            }
        }
        return summary;
    }

    /** Upsert a single detected contract: registry row + version row. Returns
     *  the contract id. Returns null on persistence failure (logged). */
    UUID upsertContract(ProjectRepo repo, UUID projectId, DetectedContract dc) {
        try {
            Optional<ContractRegistry> existing = registryRepo
                    .findByRepoIdAndName(repo.getId(), dc.name());
            ContractRegistry contract;
            boolean firstDetection = false;
            boolean versionBumped = false;
            if (existing.isPresent()) {
                contract = existing.get();
                if (contract.getCurrentContentHash() != null
                        && contract.getCurrentContentHash().equals(dc.contentHash())) {
                    // No change — same hash, skip.
                    return contract.getId();
                }
                contract.setCurrentRegistryVersion(contract.getCurrentRegistryVersion() + 1);
                contract.setCurrentContentHash(dc.contentHash());
                contract.setDeclaredSemver(dc.declaredSemver());
                contract.setDetectedPath(dc.sourcePath());
                contract.setUpdatedAt(Instant.now());
                versionBumped = true;
            } else {
                contract = ContractRegistry.builder()
                        .repoId(repo.getId())
                        .name(dc.name())
                        .type(dc.type().name())
                        .currentRegistryVersion(1)
                        .currentContentHash(dc.contentHash())
                        .declaredSemver(dc.declaredSemver())
                        .detectedPath(dc.sourcePath())
                        .detectionMethod(ContractRegistry.DetectionMethod.AUTO_FILE.name())
                        .build();
                firstDetection = true;
            }
            contract = registryRepo.save(contract);

            ContractVersion version = ContractVersion.builder()
                    .contractId(contract.getId())
                    .registryVersion(contract.getCurrentRegistryVersion())
                    .contentHash(dc.contentHash())
                    .inlineContent(dc.content() == null ? null
                            : "\"" + dc.content().replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                    .build();
            versionRepo.save(version);

            ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
            if (emitter != null) {
                if (firstDetection) {
                    emitter.emit(projectId, ActivityEventType.CONTRACT_DETECTED,
                            null, "CONTRACT", contract.getId().toString(),
                            "Detected " + dc.type() + " contract " + dc.name()
                                    + " in " + repo.getGithubRepoFullName(),
                            Map.of("repoFullName", repo.getGithubRepoFullName(),
                                    "type", dc.type().name(),
                                    "sourcePath", dc.sourcePath()));
                } else if (versionBumped) {
                    emitter.emit(projectId, ActivityEventType.CONTRACT_VERSION_CHANGED,
                            null, "CONTRACT", contract.getId().toString(),
                            "Contract " + dc.name() + " bumped to v"
                                    + contract.getCurrentRegistryVersion(),
                            Map.of("repoFullName", repo.getGithubRepoFullName(),
                                    "newVersion", contract.getCurrentRegistryVersion(),
                                    "contentHash", dc.contentHash()));
                }
            }
            return contract.getId();
        } catch (Exception ex) {
            log.warn("[ContractDetectionService] upsert failed for {}/{}: {}",
                    repo.getGithubRepoFullName(), dc.name(), ex.getMessage());
            return null;
        }
    }

    /** Test seam — replaces {@code GitHubRepoService.listRepoFiles}. */
    @FunctionalInterface
    public interface FileLister {
        List<String> list();
    }

    /**
     * Per-detector outcome summary. Returned by {@link #runForRepo}; consumed
     * by E2.3 coverage backend.
     */
    public record DetectionResult(
            String detectorName,
            ContractRegistry.ContractType type,
            List<String> patterns,
            Status status,
            List<UUID> foundContractIds,
            String failureReason) {
        public enum Status { SCANNED, FAILED }
    }
}
