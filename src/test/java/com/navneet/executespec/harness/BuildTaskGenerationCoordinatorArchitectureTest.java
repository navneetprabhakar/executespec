package com.navneet.executespec.harness;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/** Pins terminal-writer ownership after a BUILD generation reaches PREPARED. */
class BuildTaskGenerationCoordinatorArchitectureTest {

  @Test
  void preparedGenerationCannotBeTerminalizedFromCoordinatorControlFlow() throws Exception {
    String source =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/harness/BuildTaskGenerationCoordinator.java"));
    int preparedBody = source.indexOf("try {\n      telemetry.state(");

    assertThat(preparedBody).isGreaterThan(0);
    assertThat(source.substring(preparedBody))
        .doesNotContain("operations.complete(")
        .doesNotContain("operations.failBuildTask(")
        .doesNotContain("taskStatus.persist(");
  }

  @Test
  void buildAcceptanceSemanticsCannotUseImplicitThreadContext() throws Exception {
    String coordinator =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/harness/BuildTaskGenerationCoordinator.java"));
    String executionContext =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/tool/ExecutionContext.java"));
    String executor =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/harness/SequentialExecutor.java"));

    assertThat(coordinator)
        .doesNotContain("ThreadLocal")
        .contains("withBuildAcceptance(")
        .contains("BuildAcceptanceExecutionContext");
    assertThat(executionContext).contains("BuildAcceptanceExecutionContext buildAcceptance");
    assertThat(executor)
        .contains("taskCtx.buildAcceptance().systemDirective()")
        .contains("taskCtx.buildAcceptance().initialFailureSnapshot()")
        .doesNotContain("acceptanceSystemDirective()")
        .doesNotContain("activeInitialFailureSnapshot()");
  }
}
