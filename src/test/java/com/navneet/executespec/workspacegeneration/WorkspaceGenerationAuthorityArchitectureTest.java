package com.navneet.executespec.workspacegeneration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class WorkspaceGenerationAuthorityArchitectureTest {

  @Test
  void buildGenerationAuthorityIsExplicitContextCapabilityNotStaticThreadLocal() throws Exception {
    String workspaceAuthority =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/integration/RunWorkspaceAuthorityService.java"));
    String coordinator =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/harness/BuildTaskGenerationCoordinator.java"));
    String context =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/tool/ExecutionContext.java"));
    String executor =
        Files.readString(
            Path.of("src/main/java/com/navneet/executespec/harness/SequentialExecutor.java"));
    String terminalReconciler =
        Files.readString(
            Path.of(
                "src/main/java/com/navneet/executespec/workspacegeneration/WorkspaceGenerationTerminalReconciler.java"));

    assertThat(workspaceAuthority)
        .doesNotContain(
            "GENERATION_TOOL_LEASE",
            "GENERATION_DISPATCH_FENCE",
            "withWorkspaceGenerationToolLease");
    assertThat(coordinator)
        .contains("new WorkspaceGenerationDispatchAuthority")
        .contains("Function<ExecutionContext, TaskOutcome> dispatch")
        .doesNotContain(
            "withWorkspaceGenerationToolLease", "ThreadLocal<ExecutionContext>", "childContext(");
    assertThat(executor)
        .contains("Function<ExecutionContext, TaskOutcome> dispatch")
        .doesNotContain("buildTaskGenerations.childContext");
    assertThat(context)
        .contains("@JsonIgnore WorkspaceGenerationDispatchAuthority")
        .contains("assertWorkspaceGenerationDispatch");
    String preparedGenerationPath =
        coordinator.substring(coordinator.indexOf("try {\n      telemetry.state"));
    assertThat(preparedGenerationPath)
        .contains("convergence.reconcileTerminal(prepared)")
        .contains("convergence.convergeAfterFailure(prepared")
        .doesNotContain("operations.complete(", "operations.failBuildTask(");
    assertThat(terminalReconciler)
        .contains("taskTransitions.publish")
        .doesNotContain("TaskStatusPersistenceService", "taskStatuses.persist");
  }
}
