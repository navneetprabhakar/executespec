package com.navneet.executespec.contracts.coverage;

import com.navneet.executespec.auth.repository.AppUserRepository;
import com.navneet.executespec.auth.repository.ProjectRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.detection.ContractDetectionService;
import com.navneet.executespec.contracts.detection.FileBasedContractDetector;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.repo.GitHubRepoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * E2.3 — coverage panel backend. Returns the per-detector + known-gap
 * breakdown that the dashboard renders so the customer can see what was
 * scanned, what was found, and what's not auto-detectable (so they don't
 * silently miss contract types).
 *
 * <p>Two endpoints:
 * <ul>
 *   <li>GET — last cached state (fast read; detection runs asynchronously
 *       on the post-merge cascade)</li>
 *   <li>POST {@code /rescan} — on-demand re-detection (slow; opens the
 *       writer chain to repush to .executespec/contracts/produced/)</li>
 * </ul>
 *
 * <p>The "known gaps" surface is a hardcoded list at the bottom — every
 * contract category that ExecuteSpec doesn't auto-detect today is
 * surfaced here so the customer knows what to declare manually.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/repos/{repoId}/contract-coverage")
@RequiredArgsConstructor
@Slf4j
public class ContractCoverageController {

    private final ProjectRepository projectRepository;
    private final ProjectRepoRepository repoRepo;
    private final ProjectRepoLinkRepository linkRepo;
    private final ContractRegistryRepository contractRegistryRepo;
    private final ContractDetectionService detectionService;
    private final GitHubRepoService gitHub;
    private final AppUserRepository userRepo;

    @GetMapping
    public ResponseEntity<CoverageResponse> coverage(@PathVariable String projectId,
                                                     @PathVariable UUID repoId,
                                                     Authentication auth) {
        ProjectRepo repo = ensureLinkedAndAccessible(projectId, repoId, auth);

        List<ContractRegistry> existing = contractRegistryRepo.findByRepoId(repoId);
        List<DetectorSummary> perDetector = new ArrayList<>();
        // Group already-known contracts by their type so the UI can show
        // "OpenApiFileDetector — 2 contracts found" without re-scanning the repo.
        for (ContractRegistry.ContractType t : ContractRegistry.ContractType.values()) {
            List<UUID> ids = new ArrayList<>();
            for (ContractRegistry c : existing) {
                if (c.typeEnum() == t) ids.add(c.getId());
            }
            perDetector.add(new DetectorSummary(
                    detectorNameFor(t), t,
                    DetectorSummary.Status.SCANNED, ids, null));
        }
        return ResponseEntity.ok(new CoverageResponse(
                projectId, repoId.toString(),
                repo.getGithubRepoFullName(),
                perDetector,
                knownGaps()));
    }

    @PostMapping("/rescan")
    public ResponseEntity<CoverageResponse> rescan(@PathVariable String projectId,
                                                    @PathVariable UUID repoId,
                                                    Authentication auth) {
        ProjectRepo repo = ensureLinkedAndAccessible(projectId, repoId, auth);
        UUID actorUserId = parseUuid(auth == null ? null : auth.getName());
        String actorPat = actorUserId == null ? null
                : userRepo.findById(actorUserId.toString())
                        .map(u -> u.getGithubPat()).orElse(null);
        String repoUrl = "https://github.com/" + repo.getGithubRepoFullName();

        // Inject the GH-backed file lister + content loader; the detection
        // service has no GH dependency of its own, which keeps it testable.
        ContractDetectionService.FileLister lister =
                () -> gitHub.listRepoFiles(repoUrl, actorPat);
        FileBasedContractDetector.ContentLoader loader =
                p -> gitHub.readRepoFile(repoUrl, p, actorPat);

        UUID projectUuid = parseUuid(projectId);
        List<ContractDetectionService.DetectionResult> results =
                detectionService.runForRepo(repoId, projectUuid, lister, loader);

        List<DetectorSummary> perDetector = new ArrayList<>();
        for (ContractDetectionService.DetectionResult r : results) {
            perDetector.add(new DetectorSummary(
                    r.detectorName(), r.type(),
                    r.status() == ContractDetectionService.DetectionResult.Status.SCANNED
                            ? DetectorSummary.Status.SCANNED
                            : DetectorSummary.Status.FAILED,
                    r.foundContractIds(),
                    r.failureReason()));
        }
        return ResponseEntity.ok(new CoverageResponse(
                projectId, repoId.toString(),
                repo.getGithubRepoFullName(),
                perDetector,
                knownGaps()));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private ProjectRepo ensureLinkedAndAccessible(String projectId, UUID repoId, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
        }
        // Membership check is the same shape the rest of the project surface uses:
        // we treat any link from this repo to this project as proof of project access
        // (callers without project access can't see this endpoint via the controller
        // tree anyway because /api/projects/{id}/* is project-membership-gated upstream).
        ProjectRepo repo = repoRepo.findById(repoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "repo not found"));
        boolean linked = linkRepo.findByProjectIdAndRepoId(projectId, repoId).isPresent();
        if (!linked) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "repo is not linked to project");
        }
        return repo;
    }

    private static String detectorNameFor(ContractRegistry.ContractType t) {
        return switch (t) {
            case OPENAPI -> "OpenApiFileDetector";
            case PROTO -> "ProtoFileDetector";
            case GRAPHQL -> "GraphQlSdlDetector";
            case JSON_SCHEMA -> "JsonSchemaDetector";
            case ASYNCAPI -> "AsyncApiDetector";
            case EVENT_SCHEMA -> "EventSchemaDetector";
        };
    }

    /**
     * Hardcoded surface for "things ExecuteSpec doesn't auto-detect" so the
     * coverage panel never silently misses a category. Add to this list as
     * future detectors land.
     */
    private static List<KnownGap> knownGaps() {
        return List.of(
                new KnownGap(
                        "grpc-services-in-code",
                        "gRPC services declared in code without .proto files are not auto-detected",
                        "declare_manually"),
                new KnownGap(
                        "runtime-only-openapi",
                        "Runtime-served OpenAPI (e.g. Springdoc, FastAPI) is not fetched in v1",
                        "configure_runtime_fetch"),
                new KnownGap(
                        "external-schema-registries",
                        "Confluent / AWS Glue / Apicurio registries not connected in v1",
                        "connect_registry"),
                new KnownGap(
                        "monorepo-package-scoping",
                        "Monorepo package-level contract scoping not implemented in v1; "
                                + "contracts detected at repo root only",
                        "scope_aware_planning_pending"));
    }

    private static UUID parseUuid(String s) {
        try { return s == null ? null : UUID.fromString(s); }
        catch (IllegalArgumentException ex) { return null; }
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    public record CoverageResponse(
            String projectId,
            String repoId,
            String githubRepoFullName,
            List<DetectorSummary> scans,
            List<KnownGap> knownGaps) {}

    public record DetectorSummary(
            String detector,
            ContractRegistry.ContractType type,
            Status status,
            List<UUID> contractIds,
            String failureReason) {
        public enum Status { SCANNED, FAILED, SKIPPED }
    }

    public record KnownGap(String category, String explanation, String actionable) {}
}
