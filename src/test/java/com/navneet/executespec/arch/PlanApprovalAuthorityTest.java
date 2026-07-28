package com.navneet.executespec.arch;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Keeps every plan decision on the atomic decision + durable outbox authorities. */
class PlanApprovalAuthorityTest {
  private static final Path SOURCE_ROOT = Path.of("src/main/java/com/navneet/executespec");

  @Test
  void noCallerOutsideGateStoreAndDecisionServiceResolvesAPositiveGateDirectly() throws Exception {
    List<String> violations;
    try (var files = Files.walk(SOURCE_ROOT)) {
      violations =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> !path.endsWith("harness/GateStore.java"))
              .filter(path -> !path.endsWith("integration/PlanGateDecisionService.java"))
              .filter(
                  path -> {
                    try {
                      String source = Files.readString(path);
                      return source.matches(
                          "(?s).*(\\.\\s*(claimPlanGate|tryClaimPlanGate|setPlanGate)\\s*\\().*");
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .map(SOURCE_ROOT::relativize)
              .map(Path::toString)
              .sorted()
              .toList();
    }

    assertThat(violations)
        .as("positive plan-gate decisions must route through PlanGateDecisionService")
        .isEmpty();
  }

  @Test
  void noRouteOrToolBypassesSharedApprovalAdmission() throws Exception {
    List<String> violations;
    try (var files = Files.walk(SOURCE_ROOT)) {
      violations =
          files
              .filter(path -> path.toString().endsWith(".java"))
              .filter(path -> !path.endsWith("integration/PlanApprovalService.java"))
              .filter(
                  path -> {
                    try {
                      return Files.readString(path)
                          .matches("(?s).*\\.(resolvePlanGate|recoverPlanGate)\\s*\\(.*");
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  })
              .map(SOURCE_ROOT::relativize)
              .map(Path::toString)
              .sorted()
              .toList();
    }

    assertThat(violations)
        .as("retired lifecycle plan-decision and direct-recovery paths must remain absent")
        .isEmpty();
  }
}
