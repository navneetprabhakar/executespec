package com.navneet.executespec.e2e;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Deterministic guard for the opt-in live pipeline E2E.
 *
 * <p>This test runs in the normal unit suite and prevents the expensive fixture from silently
 * drifting back to the retired V1 merge gate, fake keys, or an undocumented unreserved command.
 */
class PipelineE2EContractTest {

  private static final Path PIPELINE_E2E =
      Path.of("src/test/java/com/navneet/executespec/e2e/PipelineE2ETest.java");

  @Test
  void liveFixtureUsesCurrentV2LifecycleAndFailClosedReservation() throws IOException {
    String source = Files.readString(PIPELINE_E2E);

    assertThat(source)
        .doesNotContain("AWAITING_MERGE")
        .doesNotContain("test-key-e2e")
        .contains("planRejection_marksRunCancelled")
        .contains("awaitStatus(runId, \"CANCELLED\"")
        .contains("E2E_SCENARIO")
        .contains("E2E_RUN_ID")
        .contains("executespec.live-llm.enabled")
        .contains("executespec.live-llm.test-mode")
        .contains("executespec.live-llm.reservation-id")
        .contains("deepseek-v4-flash-native")
        .contains("userCostCap(COST_CAP_USD)")
        .contains(".autoMerge(false)")
        .contains("withReservedRunId")
        .contains("isEqualTo(\"COMPLETE\")")
        .contains("Files.readString(artifact)")
        .contains("awaitReport(runId)")
        .contains("@TestConfiguration")
        .contains("@org.springframework.stereotype.Controller")
        .contains("@Testcontainers")
        .contains("PostgreSQLContainer")
        .doesNotContain("@RestController");
  }

  @Test
  void reservedSubmissionSeamIsAnActualSpringMvcHandler() {
    assertThat(
            PipelineE2ETest.ReservedRunSubmissionController.class.isAnnotationPresent(
                org.springframework.stereotype.Controller.class))
        .isTrue();
  }

  @Test
  void e2eProfileHasNoOrdinaryLocalDatabaseFallback() throws IOException {
    String profile = Files.readString(Path.of("src/test/resources/application-e2e.yml"));

    assertThat(profile)
        .contains("${EXECUTESPEC_E2E_DATABASE_URL}")
        .doesNotContain("localhost:5432/executespec");
  }

  @Test
  void operatorCommandStatesExclusionExactRouteAndBudget() throws IOException {
    String readme = Files.readString(Path.of("README.md"));
    String pom = Files.readString(Path.of("pom.xml"));

    assertThat(pom).contains("<excludedGroups>e2e,integration,chaos</excludedGroups>");
    assertThat(readme)
        .contains("not part of normal `mvn verify`")
        .contains("-Pintegration,live-llm-test")
        .contains("-Dtest=PipelineE2ETest")
        .contains("--provider deepseek")
        .contains("--model deepseek-v4-flash-native")
        .contains("--safe-max-usd 0.10");
  }
}
