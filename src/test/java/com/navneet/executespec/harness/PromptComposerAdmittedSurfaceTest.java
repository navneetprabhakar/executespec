package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.navneet.executespec.contractsurface.SpringHttpSurfaceExtractor.RouteKey;

import org.junit.jupiter.api.Test;

/**
 * P1-4 (2026-08-01): the admitted-surface worker evidence must carry the exact PUBLIC routes
 * (context-path included) and the reconciliation guidance, because the live dev failure was a
 * worker writing a naive controller mapping on a context-path-prefixed app — green code plus a
 * green self-test that could never satisfy the requested public path.
 */
class PromptComposerAdmittedSurfaceTest {

  @Test
  void admittedSurfaceListsExactPublicRoutesAndContextPathGuidance() {
    String block =
        PromptComposer.admittedHttpSurface(
            Set.of(
                new RouteKey("POST", "/simulation/casino-game"),
                new RouteKey("GET", "/simulation/health")));

    assertThat(block)
        .contains("POST /simulation/casino-game")
        .contains("GET /simulation/health")
        .contains("server.servlet.context-path")
        .contains("must remain served unchanged");
  }

  @Test
  void emptyOrNullRoutesProduceNoBlock() {
    assertThat(PromptComposer.admittedHttpSurface(null)).isEmpty();
    assertThat(PromptComposer.admittedHttpSurface(List.of())).isEmpty();
  }

  @Test
  void oversizedSurfacesAreBoundedWithAnExplicitRemainder() {
    List<RouteKey> many =
        java.util.stream.IntStream.range(0, 75)
            .mapToObj(i -> new RouteKey("GET", "/api/things/" + i))
            .toList();
    String block = PromptComposer.admittedHttpSurface(many);
    assertThat(block).contains("… 15 more routes");
  }

  @Test
  void composeWorkerEvidenceCarriesTheAdmittedSurfaceBlock() {
    com.navneet.executespec.agent.PlannedTask task =
        new com.navneet.executespec.agent.PlannedTask(
            "t1",
            "Add endpoint",
            "Add a summary endpoint",
            com.navneet.executespec.agent.PlannedTask.Complexity.LOW,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null);
    String evidence =
        PromptComposer.composeWorkerEvidence(
            task,
            null,
            null,
            null,
            null,
            null,
            null,
            "Add a summary endpoint",
            PromptComposer.admittedHttpSurface(
                Set.of(new RouteKey("POST", "/simulation/casino-game"))));
    assertThat(evidence).contains("ADMITTED_HTTP_SURFACE").contains("POST /simulation/casino-game");
  }
}
