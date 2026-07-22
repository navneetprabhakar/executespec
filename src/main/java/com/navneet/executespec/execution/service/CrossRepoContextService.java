package com.navneet.executespec.execution.service;

import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionContextHandover;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionContextHandoverRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Three-layer handover between solutions: 1. Interface contracts (REST endpoints, public types,
 * config changes) 2. Diff codemap (symbols added/modified by the completed solution) 3. Test
 * anchors (assertions that encode expected behaviour)
 *
 * <p>This initial cut focuses on (1) — stored as free-form markdown derived from the solution's
 * title + description + PR body. Layers (2) and (3) arrive when the diff inspector lands.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CrossRepoContextService {

  public static final String CONTRACT_INTERFACE = "INTERFACE";
  public static final String CONTRACT_DIFF_CODEMAP = "DIFF_CODEMAP";
  public static final String CONTRACT_TEST_ANCHOR = "TEST_ANCHOR";

  private final ExecutionContextHandoverRepository handoverRepo;

  /** RR-996 — per-contract content cap so one upstream solution cannot dominate the prompt. */
  private static final int MAX_CONTRACT_CHARS = 8_000;

  /** RR-996 — cap on the number of upstream contracts assembled into one downstream prompt. */
  private static final int MAX_CONTRACTS_PER_TARGET = 20;

  /** RR-996 — hard ceiling on the assembled handover text injected into a downstream prompt. */
  private static final int MAX_HANDOVER_CHARS = 24_000;

  /**
   * Persist a simple interface-style contract record for the completed solution, targeted at every
   * direct dependent.
   *
   * <p>RR-996 — idempotent per {@code (from, to, INTERFACE)}: a completion retry or crash-recovery
   * pass replaces the prior contract instead of appending a duplicate row, so downstream prompts do
   * not grow without bound across attempts. The content is also size-capped.
   */
  public void recordContracts(ExecutionSolution from, List<ExecutionSolution> directDependents) {
    if (directDependents == null || directDependents.isEmpty()) return;
    String contract =
        truncate(
            "## "
                + from.getTitle()
                + "\n\nDescription: "
                + (from.getDescription() == null ? "" : from.getDescription())
                + (from.getPrUrl() == null ? "" : "\n\nPR: " + from.getPrUrl()),
            MAX_CONTRACT_CHARS);
    for (ExecutionSolution to : directDependents) {
      // Replace any existing INTERFACE contract for this exact edge (no unique constraint exists;
      // dedup here so retries do not accumulate).
      var existing =
          handoverRepo.findByFromSolutionIdAndToSolutionIdAndContractType(
              from.getId(), to.getId(), CONTRACT_INTERFACE);
      if (!existing.isEmpty()) handoverRepo.deleteAll(existing);
      ExecutionContextHandover h =
          ExecutionContextHandover.builder()
              .fromSolutionId(from.getId())
              .toSolutionId(to.getId())
              .contractType(CONTRACT_INTERFACE)
              .contractJson(contract)
              .build();
      handoverRepo.save(h);
    }
  }

  /**
   * Assemble the handover text injected into a downstream solution's worker prompt.
   *
   * <p>RR-996 — deterministic order, bounded count and bounded total size so downstream prompts are
   * reproducible across retries and cannot burn/overflow the provider context window.
   */
  public String buildHandoverContext(ExecutionSolution target) {
    List<ExecutionContextHandover> handovers =
        handoverRepo.findByToSolutionIdOrderByCreatedAtAscIdAsc(target.getId());
    if (handovers.isEmpty()) return "";
    StringBuilder sb = new StringBuilder("## Upstream contracts\n\n");
    int included = 0;
    for (ExecutionContextHandover h : handovers) {
      if (included >= MAX_CONTRACTS_PER_TARGET) break;
      String body = h.getContractJson() == null ? "" : h.getContractJson();
      if (sb.length() + body.length() > MAX_HANDOVER_CHARS) break;
      if (included > 0) sb.append("\n\n");
      sb.append(body);
      included++;
    }
    return sb.toString();
  }

  private static String truncate(String s, int max) {
    if (s == null) return "";
    return s.length() <= max ? s : s.substring(0, max);
  }
}
