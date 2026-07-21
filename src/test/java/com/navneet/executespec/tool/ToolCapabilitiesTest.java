package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * RR-1599 — every tool NAME the registry can advertise must be explicitly classified, and every
 * alias must carry its delegate's authority rather than defaulting to "harmless".
 */
class ToolCapabilitiesTest {

  /**
   * The alias names that previously escaped every name-keyed worker guard. Each one executes a
   * privileged capability; none may be unclassified or classified read-only.
   */
  private static final List<String> ESCAPING_ALIASES =
      List.of(
          "executeCommand",
          "runCommand",
          "terminalCommand",
          "build",
          "runBuild",
          "rename",
          "deletePath",
          "backgroundProcess",
          "move",
          "delete",
          "multiEdit",
          "applyPatch",
          "apply_patch",
          "ast_edit");

  @Test
  void everyEscapingAliasIsClassifiedAsMutating() {
    for (String alias : ESCAPING_ALIASES) {
      assertThat(ToolCapabilities.isClassified(alias))
          .as("alias '%s' must be explicitly classified", alias)
          .isTrue();
      assertThat(ToolCapabilities.mutating(alias))
          .as("alias '%s' must count as mutating", alias)
          .isTrue();
      assertThat(ToolCapabilities.readOnly(alias))
          .as("alias '%s' must never be treated as read-only", alias)
          .isFalse();
    }
  }

  @Test
  void shellAliasesShareTheShellCapability() {
    for (String alias :
        List.of("shell", "bash", "executeCommand", "runCommand", "terminalCommand")) {
      assertThat(ToolCapabilities.of(alias)).contains(ToolCapability.SHELL);
      assertThat(ToolCapabilities.executesCommands(alias)).isTrue();
    }
  }

  @Test
  void buildAliasesShareTheBuildCapability() {
    for (String alias : List.of("buildProject", "build", "runBuild")) {
      assertThat(ToolCapabilities.of(alias)).contains(ToolCapability.BUILD_PROJECT);
    }
  }

  @Test
  void writeAliasesAreFileMutations() {
    assertThat(ToolCapabilities.of("rename")).contains(ToolCapability.MOVE_PATH);
    assertThat(ToolCapabilities.of("deletePath")).contains(ToolCapability.DELETE_PATH);
    assertThat(ToolCapabilities.of("multiEdit")).contains(ToolCapability.EDIT_FILE);
    assertThat(ToolCapabilities.mutatesFiles("rename")).isTrue();
    assertThat(ToolCapabilities.mutatesFiles("deletePath")).isTrue();
  }

  @Test
  void readsAreClassifiedReadOnly() {
    for (String name : List.of("readFile", "listFiles", "searchFiles", "gitStatus", "gitDiff")) {
      assertThat(ToolCapabilities.readOnly(name)).as(name).isTrue();
      assertThat(ToolCapabilities.mutating(name)).as(name).isFalse();
    }
  }

  @Test
  void unclassifiedNameFailsClosed() {
    assertThat(ToolCapabilities.isClassified("someFutureWriteTool")).isFalse();
    assertThat(ToolCapabilities.mutating("someFutureWriteTool")).isTrue();
    assertThat(ToolCapabilities.mutatesFiles("someFutureWriteTool")).isTrue();
    assertThat(ToolCapabilities.readOnly("someFutureWriteTool")).isFalse();
  }

  @Test
  void aliasToolDerivesCapabilityFromDelegateEvenWhenUnlisted() {
    Tool delegate =
        new Tool() {
          @Override
          public String name() {
            return "delete";
          }

          @Override
          public String description() {
            return "stub";
          }

          @Override
          public String inputSchema() {
            return "{}";
          }

          @Override
          public ToolResult execute(
              com.fasterxml.jackson.databind.JsonNode args, ExecutionContext ctx) {
            return new ToolResult.Ok("ok");
          }
        };
    Tool alias = new CommandAliasTool("purgeEverything", "unlisted future alias", delegate);

    assertThat(ToolCapabilities.isClassified("purgeEverything")).isFalse();
    assertThat(alias.capability()).isEqualTo(ToolCapability.DELETE_PATH);
  }
}
