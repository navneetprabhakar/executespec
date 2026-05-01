package com.navneet.executespec.execution.service;

import java.util.List;
import java.util.stream.Collectors;

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

  /**
   * Persist a simple interface-style contract record for the completed solution, targeted at every
   * direct dependent.
   */
  public void recordContracts(ExecutionSolution from, List<ExecutionSolution> directDependents) {
    if (directDependents == null || directDependents.isEmpty()) return;
    String contract =
        "## "
            + from.getTitle()
            + "\n\nDescription: "
            + (from.getDescription() == null ? "" : from.getDescription())
            + (from.getPrUrl() == null ? "" : "\n\nPR: " + from.getPrUrl());
    for (ExecutionSolution to : directDependents) {
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

  /** Assemble the handover text injected into a downstream solution's worker prompt. */
  public String buildHandoverContext(ExecutionSolution target) {
    List<ExecutionContextHandover> handovers = handoverRepo.findByToSolutionId(target.getId());
    if (handovers.isEmpty()) return "";
    return "## Upstream contracts\n\n"
        + handovers.stream()
            .map(ExecutionContextHandover::getContractJson)
            .collect(Collectors.joining("\n\n"));
  }
}
