package com.navneet.executespec.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.navneet.executespec.tool.ToolCapabilities;

import org.junit.jupiter.api.Test;

/**
 * RR-1599 — the worker's safety and evidence gates must classify by capability, not by the literal
 * tool NAME the model happened to use.
 *
 * <p>The registry mints several names over one implementation for client-vocabulary parity. Before
 * this fix, every alias executed the privileged action while being invisible to the guard that was
 * supposed to police it: the verified-pass guard failed OPEN for {@code executeCommand} / {@code
 * build} / {@code move} / {@code delete} / {@code backgroundProcess}, and the build-verification
 * gate was unsatisfiable through {@code build} / {@code runBuild}.
 */
class WorkerAgentToolCapabilityGuardTest {

  /** Every registered spelling that runs an arbitrary command. */
  private static final List<String> SHELL_ALIASES =
      List.of(
          "shell",
          "bash",
          "executeCommand",
          "runCommand",
          "terminalCommand",
          "backgroundProcess",
          "background_process");

  /** Every registered spelling that changes files on disk. */
  private static final List<String> WRITE_ALIASES =
      List.of(
          "writeFile",
          "write_file",
          "editFile",
          "edit_file",
          "multiEdit",
          "applyPatch",
          "apply_patch",
          "ast_edit",
          "move",
          "rename",
          "delete",
          "deletePath");

  @Test
  void verifiedPassGuardRefusesEveryMutatingAlias() {
    for (String name : WRITE_ALIASES) {
      assertThat(WorkerAgent.terminalRiskyAfterVerifiedPass(name))
          .as("mutating alias '%s' must be refused after a verified pass", name)
          .isTrue();
    }
    for (String name : SHELL_ALIASES) {
      assertThat(WorkerAgent.terminalRiskyAfterVerifiedPass(name))
          .as("command alias '%s' must be refused after a verified pass", name)
          .isTrue();
    }
    for (String name : List.of("buildProject", "build", "runBuild", "runTests")) {
      assertThat(WorkerAgent.terminalRiskyAfterVerifiedPass(name))
          .as("rerun alias '%s' must be refused after a verified pass", name)
          .isTrue();
    }
  }

  @Test
  void verifiedPassGuardStillPermitsPureReads() {
    for (String name : List.of("readFile", "listFiles", "searchFiles", "gitStatus", "gitDiff")) {
      assertThat(WorkerAgent.terminalRiskyAfterVerifiedPass(name)).as(name).isFalse();
    }
  }

  @Test
  void verifiedPassGuardFailsClosedOnAnUnclassifiedName() {
    // A tool registered tomorrow without a capability entry must NOT slip past the guard.
    assertThat(ToolCapabilities.isClassified("someBrandNewTool")).isFalse();
    assertThat(WorkerAgent.terminalRiskyAfterVerifiedPass("someBrandNewTool")).isTrue();
  }

  @Test
  void writeAndShellClassificationCoversEveryAlias() {
    for (String name : WRITE_ALIASES) {
      assertThat(WorkerAgent.isWriteTool(name)).as("isWriteTool(%s)", name).isTrue();
    }
    for (String name : SHELL_ALIASES) {
      assertThat(WorkerAgent.isShellTool(name)).as("isShellTool(%s)", name).isTrue();
    }
  }

  @Test
  void buildAliasesCanSatisfyTheBuildVerificationGate() {
    for (String name : List.of("buildProject", "build", "runBuild")) {
      assertThat(WorkerAgent.isBuildTool(name)).as("isBuildTool(%s)", name).isTrue();
    }
    assertThat(WorkerAgent.isBuildTool("shell")).isFalse();
    assertThat(WorkerAgent.isBuildTool("someBrandNewTool")).isFalse();
  }

  @Test
  void terminalVerifierToolAcceptsCommandBuildAndTestAliases() {
    for (String name :
        List.of("shell", "bash", "executeCommand", "build", "runBuild", "runTests")) {
      assertThat(WorkerAgent.terminalVerifierTool(name)).as(name).isTrue();
    }
    assertThat(WorkerAgent.terminalVerifierTool("readFile")).isFalse();
  }

  @Test
  void progressClassificationFailsClosedOnAnUnclassifiedName() {
    // Opposite direction from the safety predicates on purpose: an unknown tool must not be able
    // to hold the stagnation guard open forever by counting as "progress".
    assertThat(WorkerAgent.isProgressTool("someBrandNewTool", false)).isFalse();
    assertThat(WorkerAgent.isProgressTool("someBrandNewTool", true)).isFalse();

    assertThat(WorkerAgent.isProgressTool("applyPatch", false)).isTrue();
    assertThat(WorkerAgent.isProgressTool("ast_edit", false)).isTrue();
    assertThat(WorkerAgent.isProgressTool("delete", false)).isTrue();
    assertThat(WorkerAgent.isProgressTool("runBuild", false)).isTrue();
    assertThat(WorkerAgent.isProgressTool("readFile", false)).isFalse();

    // Terminal-Bench counts shell as progress — under every spelling.
    assertThat(WorkerAgent.isProgressTool("executeCommand", false)).isFalse();
    assertThat(WorkerAgent.isProgressTool("executeCommand", true)).isTrue();
  }

  // RR-622 — a model-supplied tool name must collapse to a bounded metric tag. Registered aliases
  // keep their name; anything unregistered (an adversarial per-iteration string) becomes "unknown".
  @Test
  void boundedToolMetricNameCollapsesUnknownNamesToASingleSeries() {
    assertThat(WorkerAgentSupport.boundedToolMetricName("writeFile")).isEqualTo("writeFile");
    assertThat(WorkerAgentSupport.boundedToolMetricName("executeCommand"))
        .isEqualTo("executeCommand");
    assertThat(WorkerAgentSupport.boundedToolMetricName("completeTask")).isEqualTo("completeTask");
    assertThat(WorkerAgentSupport.boundedToolMetricName("evil-tool-0001")).isEqualTo("unknown");
    assertThat(WorkerAgentSupport.boundedToolMetricName("evil-tool-0002")).isEqualTo("unknown");
    assertThat(WorkerAgentSupport.boundedToolMetricName(null)).isEqualTo("unknown");
    assertThat(WorkerAgentSupport.boundedToolMetricName("  ")).isEqualTo("unknown");
  }

  // RR-1562 — the effective per-solve dollar cap is the tighter of two nullable caps (null =
  // unbounded); a non-positive value is treated as unset so it cannot disable enforcement.
  @Test
  void minNullableCapPicksTheTighterEnforceableCeiling() {
    assertThat(WorkerAgentSupport.minNullableCap(5.0, 2.0)).isEqualTo(2.0);
    assertThat(WorkerAgentSupport.minNullableCap(2.0, 5.0)).isEqualTo(2.0);
    assertThat(WorkerAgentSupport.minNullableCap(null, 3.0)).isEqualTo(3.0);
    assertThat(WorkerAgentSupport.minNullableCap(3.0, null)).isEqualTo(3.0);
    assertThat(WorkerAgentSupport.minNullableCap(null, null)).isNull();
    assertThat(WorkerAgentSupport.minNullableCap(0.0, 4.0)).isEqualTo(4.0);
    assertThat(WorkerAgentSupport.minNullableCap(-1.0, null)).isNull();
  }
}
