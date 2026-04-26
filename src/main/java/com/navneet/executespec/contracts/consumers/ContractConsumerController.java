package com.navneet.executespec.contracts.consumers;

import com.navneet.executespec.activity.ActivityEventEmitter;
import com.navneet.executespec.activity.ActivityEventType;
import com.navneet.executespec.contracts.ContractConsumer;
import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.folder.ExecutespecFolderWriter;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * E2.10 — REST surface for consumer declaration. Two endpoints + side-effect:
 *
 * <ul>
 *   <li>POST {@code /declare} — persists a {@link ContractConsumer} row +
 *       opens a PR adding {@code .executespec/contracts/consumed/<name>.consumer.yaml}
 *       to the consumer repo (so the declaration is git-authoritative
 *       per plan §1.3, not just a DB cache).</li>
 *   <li>DELETE — removes the row + opens a PR removing the file.</li>
 * </ul>
 *
 * <p>Activity events emitted on both: {@code CONTRACT_CONSUMER_DECLARED}
 * and {@code CONTRACT_CONSUMER_REMOVED}. PR-removal-via-writer is a
 * follow-up — for now the DELETE path just removes the DB row and emits
 * the event; the customer can clean up the {@code .executespec/contracts/consumed/}
 * file manually (logged in §13).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/contracts/{contractId}/consumers")
@RequiredArgsConstructor
@Slf4j
public class ContractConsumerController {

    private final ContractRegistryRepository registryRepo;
    private final ContractConsumerRepository consumerRepo;
    private final ProjectRepoRepository projectRepoRepo;
    private final ExecutespecFolderWriter folderWriter;
    private final com.navneet.executespec.auth.repository.AppUserRepository userRepo;
    private final org.springframework.beans.factory.ObjectProvider<ActivityEventEmitter> activityEmitterProvider;

    @GetMapping
    public ResponseEntity<List<ConsumerDto>> list(
            @PathVariable String projectId,
            @PathVariable UUID contractId,
            Authentication auth) {
        requireAuth(auth);
        List<ConsumerDto> out = consumerRepo
                .findByContractIdAndDeclaredInProjectId(contractId, projectId)
                .stream().map(ConsumerDto::from).toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping
    public ResponseEntity<ConsumerDto> declare(
            @PathVariable String projectId,
            @PathVariable UUID contractId,
            @RequestBody DeclareRequest body,
            Authentication auth) {
        requireAuth(auth);
        if (body == null || body.consumerRepoId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "consumerRepoId required");
        }
        ContractRegistry contract = registryRepo.findById(contractId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "contract"));
        ProjectRepo consumerRepo2 = projectRepoRepo.findById(body.consumerRepoId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "consumer repo"));

        // Idempotent: if the same (contract, repo, project) tuple is already
        // declared, return the existing row instead of creating a duplicate.
        Optional<ContractConsumer> existing = consumerRepo
                .findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(
                        contractId, body.consumerRepoId(), projectId);
        if (existing.isPresent()) {
            return ResponseEntity.ok(ConsumerDto.from(existing.get()));
        }

        ContractConsumer entity = ContractConsumer.builder()
                .contractId(contractId)
                .consumerRepoId(body.consumerRepoId())
                .declaredVersionConstraint(body.versionConstraint())
                .declaredInProjectId(projectId)
                .declaredByUserId(auth.getName())
                .build();
        ContractConsumer saved = consumerRepo.save(entity);

        // Open the consumer-side declaration PR (best-effort; persist
        // the DB row regardless because the planner-injection path keys
        // off the row, not the file).
        String pat = userRepo.findById(auth.getName())
                .map(u -> u.getGithubPat()).orElse(null);
        String yaml = composeConsumerYaml(contract, body.versionConstraint());
        String path = ".executespec/contracts/consumed/" + contract.getName() + ".consumer.yaml";
        try {
            folderWriter.writeFiles(consumerRepo2, null,
                    java.util.Map.of(path, yaml),
                    "ExecuteSpec: declare " + contract.getName() + " consumer in "
                            + consumerRepo2.getGithubRepoFullName(),
                    "Auto-declared consumer relationship.\n\n"
                            + "Contract: " + contract.getName() + "\n"
                            + "Producer repo: contract row " + contract.getId() + "\n"
                            + "Consumer repo: " + consumerRepo2.getGithubRepoFullName() + "\n"
                            + "Version constraint: " + (body.versionConstraint() == null
                                    ? "(any)" : body.versionConstraint()),
                    pat);
        } catch (Exception ex) {
            log.warn("[ContractConsumerController] consumer PR open failed (DB row persisted): {}",
                    ex.getMessage());
        }

        ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
        if (emitter != null) {
            emitter.emit(parseUuidOrNull(projectId),
                    ActivityEventType.CONTRACT_CONSUMER_DECLARED,
                    parseUuidOrNull(auth.getName()),
                    "CONTRACT", contractId.toString(),
                    "Declared consumer " + consumerRepo2.getGithubRepoFullName()
                            + " for contract " + contract.getName(),
                    Map.of("consumerRepo", consumerRepo2.getGithubRepoFullName(),
                            "versionConstraint",
                            body.versionConstraint() == null ? "" : body.versionConstraint()));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ConsumerDto.from(saved));
    }

    @DeleteMapping("/{consumerRepoId}")
    public ResponseEntity<Void> remove(
            @PathVariable String projectId,
            @PathVariable UUID contractId,
            @PathVariable UUID consumerRepoId,
            Authentication auth) {
        requireAuth(auth);
        ContractConsumer existing = consumerRepo
                .findByContractIdAndConsumerRepoIdAndDeclaredInProjectId(
                        contractId, consumerRepoId, projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "consumer not declared"));
        consumerRepo.delete(existing);

        ActivityEventEmitter emitter = activityEmitterProvider.getIfAvailable();
        if (emitter != null) {
            emitter.emit(parseUuidOrNull(projectId),
                    ActivityEventType.CONTRACT_CONSUMER_REMOVED,
                    parseUuidOrNull(auth.getName()),
                    "CONTRACT", contractId.toString(),
                    "Removed consumer declaration for repo " + consumerRepoId,
                    Map.of("consumerRepoId", consumerRepoId.toString()));
        }
        return ResponseEntity.noContent().build();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static String composeConsumerYaml(ContractRegistry contract, String versionConstraint) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Auto-generated by ExecuteSpec at consumer-declaration time.\n");
        sb.append("# Edit the version constraint below; the contract identity is fixed.\n");
        sb.append("contract:\n");
        sb.append("  id: ").append(contract.getId()).append("\n");
        sb.append("  name: ").append(contract.getName()).append("\n");
        sb.append("  type: ").append(contract.typeEnum().name()).append("\n");
        sb.append("versionConstraint: ").append(versionConstraint == null
                ? "any"
                : versionConstraint).append("\n");
        return sb.toString();
    }

    private void requireAuth(Authentication auth) {
        if (auth == null || auth.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "auth required");
        }
    }

    private static UUID parseUuidOrNull(String s) {
        try { return s == null ? null : UUID.fromString(s); }
        catch (IllegalArgumentException ex) { return null; }
    }

    public record DeclareRequest(UUID consumerRepoId, String versionConstraint) {}

    public record ConsumerDto(String id, String contractId, String consumerRepoId,
                              String versionConstraint, String declaredAt,
                              String declaredByUserId) {
        public static ConsumerDto from(ContractConsumer c) {
            return new ConsumerDto(
                    c.getId() == null ? null : c.getId().toString(),
                    c.getContractId() == null ? null : c.getContractId().toString(),
                    c.getConsumerRepoId() == null ? null : c.getConsumerRepoId().toString(),
                    c.getDeclaredVersionConstraint(),
                    c.getDeclaredAt() == null ? null : c.getDeclaredAt().toString(),
                    c.getDeclaredByUserId());
        }
    }
}
