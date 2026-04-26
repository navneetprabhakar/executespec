package com.navneet.executespec.contracts.gate;

import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.breaking.BreakingChangeService;
import com.navneet.executespec.contracts.breaking.ContractDiff;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * E2.12 — composes the breaking-change report shown at the plan-approval
 * gate. Only contracts that satisfy ALL THREE conditions surface as gate
 * friction (per plan §2.6):
 *
 * <ol>
 *   <li>Plan intends to modify the contract</li>
 *   <li>Change is BREAKING per the deterministic classifier</li>
 *   <li>Contract has at least one declared consumer in this project</li>
 * </ol>
 *
 * <p>Other cases (breaking but no consumers / non-breaking) are recorded
 * in the run metadata but don't block approval — collapses warning
 * frequency by the ~10–50× factor called out in the source plan, so the
 * rare warnings stay readable.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlanBreakingChangeReport {

    private final BreakingChangeService breakingChangeService;
    private final ContractRegistryRepository registryRepo;
    private final ContractConsumerRepository consumerRepo;
    private final ProjectRepoRepository projectRepoRepo;

    /**
     * Compose the gate-friction report for a plan that intends to modify
     * the given contracts.
     *
     * @param projectId project context (required for consumer scoping)
     * @param intended  one entry per contract the plan touches: id +
     *                  proposed new content
     */
    public Report compose(String projectId, List<IntendedChange> intended) {
        if (intended == null || intended.isEmpty()) {
            return new Report(List.of(), false);
        }
        List<BreakingChangeRow> rows = new ArrayList<>();
        boolean requiresAck = false;
        for (IntendedChange ic : intended) {
            ContractRegistry contract = registryRepo.findById(ic.contractId()).orElse(null);
            if (contract == null) continue;

            // Classifier decides BREAKING / NON_BREAKING / UNCLASSIFIED
            ContractDiff diff = breakingChangeService.classify(
                    contract.typeEnum(),
                    ic.previousContent() == null ? "" : ic.previousContent(),
                    ic.newContent() == null ? "" : ic.newContent());
            if (!diff.hasBreaking()) continue;

            // Consumer presence decides gate friction vs metadata-only record
            List<String> consumerRepos = consumerRepo
                    .findByContractIdAndDeclaredInProjectId(ic.contractId(), projectId)
                    .stream()
                    .map(cc -> projectRepoRepo.findById(cc.getConsumerRepoId())
                            .map(ProjectRepo::getGithubRepoFullName)
                            .orElse(cc.getConsumerRepoId().toString()))
                    .toList();
            boolean isFriction = !consumerRepos.isEmpty();
            if (isFriction) requiresAck = true;
            rows.add(new BreakingChangeRow(
                    ic.contractId(),
                    contract.getName(),
                    contract.typeEnum(),
                    diff.summary(),
                    consumerRepos,
                    isFriction,
                    diff.confidence().name()));
        }
        return new Report(rows, requiresAck);
    }

    /**
     * @param contractId the contract being modified
     * @param previousContent prior version body (raw text); null OK for first version
     * @param newContent      proposed new body
     */
    public record IntendedChange(UUID contractId, String previousContent, String newContent) {}

    /**
     * @param breakingChanges per-contract findings
     * @param requiresTypedAck true when at least one contract has both
     *                         breaking change AND declared consumers
     *                         (UI must show the typed-"BREAKING"
     *                         confirmation and disable approve until typed)
     */
    public record Report(List<BreakingChangeRow> breakingChanges, boolean requiresTypedAck) {}

    /**
     * @param contractId       the contract id
     * @param contractName     human-readable name shown in the modal
     * @param type             contract format
     * @param summary          one-line summary of the breaking change
     * @param affectedConsumers full names of consumer repos declared in this project
     * @param requiresFriction true → triggers gate friction; false → metadata-only
     * @param confidence       classifier confidence (DETERMINISTIC / LLM_DERIVED)
     */
    public record BreakingChangeRow(
            UUID contractId,
            String contractName,
            ContractRegistry.ContractType type,
            String summary,
            List<String> affectedConsumers,
            boolean requiresFriction,
            String confidence) {}
}
