package com.navneet.executespec.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.navneet.executespec.execution.entity.ExecutionContextHandover;
import com.navneet.executespec.execution.entity.ExecutionSolution;
import com.navneet.executespec.execution.repository.ExecutionContextHandoverRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * RR-996 — cross-repo handover contracts must be deterministic (ordered), idempotent (a retry
 * replaces the prior contract instead of appending) and bounded (count + size), so downstream
 * prompts are reproducible and cannot grow without bound across retries/recovery.
 */
@ExtendWith(MockitoExtension.class)
class CrossRepoContextServiceTest {

  @Mock ExecutionContextHandoverRepository handoverRepo;

  @InjectMocks CrossRepoContextService service;

  private static ExecutionContextHandover handover(String from, String to, String body) {
    return ExecutionContextHandover.builder()
        .fromSolutionId(from)
        .toSolutionId(to)
        .contractType(CrossRepoContextService.CONTRACT_INTERFACE)
        .contractJson(body)
        .createdAt(Instant.now())
        .build();
  }

  @Test
  void buildHandoverContext_usesDeterministicOrderedFinder() {
    ExecutionSolution target = ExecutionSolution.builder().id("t1").build();
    when(handoverRepo.findByToSolutionIdOrderByCreatedAtAscIdAsc("t1"))
        .thenReturn(List.of(handover("a", "t1", "ALPHA"), handover("b", "t1", "BETA")));

    String out = service.buildHandoverContext(target);

    // RR-996 — the ordered finder is the deterministic source; content joins in that order.
    assertThat(out).contains("ALPHA").contains("BETA");
    assertThat(out.indexOf("ALPHA")).isLessThan(out.indexOf("BETA"));
    verify(handoverRepo, never()).findByToSolutionId(anyString());
  }

  @Test
  void buildHandoverContext_capsContractCount() {
    ExecutionSolution target = ExecutionSolution.builder().id("t1").build();
    List<ExecutionContextHandover> many = new ArrayList<>();
    for (int i = 0; i < 50; i++) many.add(handover("s" + i, "t1", "CONTRACT_" + i));
    when(handoverRepo.findByToSolutionIdOrderByCreatedAtAscIdAsc("t1")).thenReturn(many);

    String out = service.buildHandoverContext(target);

    // RR-996 — at most MAX_CONTRACTS_PER_TARGET (20) contracts are assembled.
    long included = out.lines().filter(l -> l.startsWith("CONTRACT_")).count();
    assertThat(included).isLessThanOrEqualTo(20);
    assertThat(out).contains("CONTRACT_0");
    assertThat(out).doesNotContain("CONTRACT_49");
  }

  @Test
  void recordContracts_replacesExistingEdgeContractIdempotently() {
    ExecutionSolution from = ExecutionSolution.builder().id("from-1").title("Upstream").build();
    ExecutionSolution to = ExecutionSolution.builder().id("to-1").build();
    var stale = List.of(handover("from-1", "to-1", "OLD"));
    when(handoverRepo.findByFromSolutionIdAndToSolutionIdAndContractType(
            "from-1", "to-1", CrossRepoContextService.CONTRACT_INTERFACE))
        .thenReturn(stale);

    service.recordContracts(from, List.of(to));

    // RR-996 — the prior (from,to,INTERFACE) contract is deleted before the fresh one is saved, so
    // a completion retry does not accumulate duplicate rows.
    verify(handoverRepo).deleteAll(stale);
    verify(handoverRepo, times(1)).save(any(ExecutionContextHandover.class));
  }

  @Test
  void recordContracts_noDependentsIsNoOp() {
    ExecutionSolution from = ExecutionSolution.builder().id("from-1").title("Upstream").build();
    service.recordContracts(from, List.of());
    verify(handoverRepo, never()).save(any());
  }
}
