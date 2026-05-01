package com.navneet.executespec.contracts.injection;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.contracts.ContractConsumerRepository;
import com.navneet.executespec.contracts.ContractRegistry;
import com.navneet.executespec.contracts.ContractRegistryRepository;
import com.navneet.executespec.contracts.ContractVersion;
import com.navneet.executespec.contracts.ContractVersionRepository;
import com.navneet.executespec.project.entity.ProjectRepo;
import com.navneet.executespec.project.entity.ProjectRepoLink;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * E2.11 — composes the {@code {{CONTRACTS}}} or {@code {{CONTRACTS_INDEX}}} block injected into
 * planner / worker / reviewer prompts.
 *
 * <p>Decision rule from plan §2.7:
 *
 * <ul>
 *   <li>≤ {@code contracts.injection.full-body-threshold} contracts (default 15): pre-fetch every
 *       contract body inline as {@link Mode#FULL_BODY}
 *   <li>&gt; threshold: emit {@link Mode#INDEX_ONLY} — one compact line per contract; planner uses
 *       the {@code getContract} tool to fetch bodies on demand (tool registration deferred to a
 *       follow-up)
 * </ul>
 *
 * <p>Service is read-only; per-call cost is bounded by the threshold so the planner cold start
 * stays predictable. Threshold is tunable via {@code application.yml} → {@code
 * contracts.injection.full-body-threshold}.
 */
@Service
@Slf4j
public class ContractInjectionService {

  private final ContractRegistryRepository registryRepo;
  private final ContractVersionRepository versionRepo;
  private final ContractConsumerRepository consumerRepo;
  private final ProjectRepoLinkRepository linkRepo;
  private final ProjectRepoRepository projectRepoRepo;

  @Value("${contracts.injection.full-body-threshold:15}")
  private int fullBodyThreshold;

  public ContractInjectionService(
      ContractRegistryRepository registryRepo,
      ContractVersionRepository versionRepo,
      ContractConsumerRepository consumerRepo,
      ProjectRepoLinkRepository linkRepo,
      ProjectRepoRepository projectRepoRepo) {
    this.registryRepo = registryRepo;
    this.versionRepo = versionRepo;
    this.consumerRepo = consumerRepo;
    this.linkRepo = linkRepo;
    this.projectRepoRepo = projectRepoRepo;
  }

  /** Compose the {@code {{CONTRACTS}}} block for a project. */
  public InjectionResult compose(String projectId) {
    List<ContractRegistry> contracts = listContractsForProject(projectId);
    if (contracts.isEmpty()) {
      return new InjectionResult(Mode.EMPTY, "", List.of(), 0);
    }
    if (contracts.size() <= fullBodyThreshold) {
      return composeFullBody(projectId, contracts);
    }
    return composeIndexOnly(projectId, contracts);
  }

  /** Test seam — exposes the threshold so callers can decide pre-emptively. */
  public int fullBodyThreshold() {
    return fullBodyThreshold;
  }

  // ── Internals ───────────────────────────────────────────────────────────

  List<ContractRegistry> listContractsForProject(String projectId) {
    // Project's contracts = (a) every contract whose producing repo is
    // linked to the project + (b) every contract that has a declared
    // consumer in this project. Union deduplicated by id.
    List<ContractRegistry> out = new ArrayList<>();
    Map<UUID, ContractRegistry> seen = new HashMap<>();
    for (ProjectRepoLink link : linkRepo.findByProjectId(projectId)) {
      for (ContractRegistry c : registryRepo.findByRepoId(link.getRepoId())) {
        if (seen.put(c.getId(), c) == null) out.add(c);
      }
    }
    consumerRepo
        .findByDeclaredInProjectId(projectId)
        .forEach(
            cc -> {
              registryRepo
                  .findById(cc.getContractId())
                  .ifPresent(
                      c -> {
                        if (seen.put(c.getId(), c) == null) out.add(c);
                      });
            });
    return out;
  }

  InjectionResult composeFullBody(String projectId, List<ContractRegistry> contracts) {
    StringBuilder sb = new StringBuilder();
    sb.append("=== CONTRACTS ===\n");
    sb.append("Cross-repo contracts your plan must respect. ")
        .append(contracts.size())
        .append(" contract(s) inlined below.\n\n");
    List<UUID> ids = new ArrayList<>();
    for (ContractRegistry c : contracts) {
      ids.add(c.getId());
      sb.append("--- contract: ")
          .append(c.getName())
          .append(" (")
          .append(c.typeEnum())
          .append(", v")
          .append(c.getCurrentRegistryVersion())
          .append(") ---\n");
      sb.append(consumerSummary(projectId, c)).append("\n");
      Optional<ContractVersion> latest =
          versionRepo.findFirstByContractIdOrderByRegistryVersionDesc(c.getId());
      if (latest.isPresent() && latest.get().getInlineContent() != null) {
        sb.append(latest.get().getInlineContent()).append("\n");
      } else {
        sb.append("(content not inlined — use getContract tool to fetch)\n");
      }
      sb.append("\n");
    }
    return new InjectionResult(Mode.FULL_BODY, sb.toString(), ids, contracts.size());
  }

  InjectionResult composeIndexOnly(String projectId, List<ContractRegistry> contracts) {
    StringBuilder sb = new StringBuilder();
    sb.append("=== CONTRACTS_INDEX ===\n");
    sb.append("Project has ")
        .append(contracts.size())
        .append(" contracts. Use the getContract(id, version) tool to fetch ")
        .append("specific bodies on demand.\n\n");
    List<UUID> ids = new ArrayList<>();
    for (ContractRegistry c : contracts) {
      ids.add(c.getId());
      sb.append("[")
          .append(c.getId())
          .append("] ")
          .append(c.getName())
          .append(" v")
          .append(c.getCurrentRegistryVersion())
          .append("\n");
      sb.append("  type: ")
          .append(c.typeEnum())
          .append("; producer: ")
          .append(producerNameFor(c))
          .append("; ")
          .append(consumerSummary(projectId, c))
          .append("\n");
    }
    return new InjectionResult(Mode.INDEX_ONLY, sb.toString(), ids, contracts.size());
  }

  private String producerNameFor(ContractRegistry c) {
    return projectRepoRepo
        .findById(c.getRepoId())
        .map(ProjectRepo::getGithubRepoFullName)
        .orElse("(unknown repo)");
  }

  private String consumerSummary(String projectId, ContractRegistry c) {
    List<String> consumers =
        consumerRepo.findByContractIdAndDeclaredInProjectId(c.getId(), projectId).stream()
            .map(
                cc ->
                    projectRepoRepo
                        .findById(cc.getConsumerRepoId())
                        .map(ProjectRepo::getGithubRepoFullName)
                        .orElse(cc.getConsumerRepoId().toString()))
            .toList();
    return consumers.isEmpty()
        ? "consumers: (none declared)"
        : "consumers: " + String.join(", ", consumers);
  }

  public enum Mode {
    EMPTY,
    FULL_BODY,
    INDEX_ONLY
  }

  /**
   * @param mode what the planner should expect
   * @param block the rendered text to substitute into the {@code {{CONTRACTS}}} placeholder
   * @param contractIds ids included in this composition
   * @param totalContracts total count for telemetry
   */
  public record InjectionResult(
      Mode mode, String block, List<UUID> contractIds, int totalContracts) {}
}
