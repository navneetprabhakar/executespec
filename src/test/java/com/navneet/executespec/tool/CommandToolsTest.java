package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.smoketest.BuildCommand;
import com.navneet.executespec.smoketest.BuildCommandResolver;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandToolsTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path root;

  private ExecutionContext ctx() {
    return new ExecutionContext("run-1", "T1", root);
  }

  private static JsonNode json(String text) {
    try {
      return JSON.readTree(text);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static final class CapturingTool implements Tool {
    JsonNode lastArgs;

    @Override
    public String name() {
      return "shell";
    }

    @Override
    public String description() {
      return "fake shell";
    }

    @Override
    public String inputSchema() {
      return ProjectCommandSchemas.OPTIONAL_COMMAND_SCHEMA;
    }

    @Override
    public ToolResult execute(JsonNode args, ExecutionContext ctx) {
      lastArgs = args.deepCopy();
      return ToolResult.ok(args.path("command").asText());
    }
  }

  @Test
  void commandAlias_delegates_to_shell_with_original_args() {
    CapturingTool shell = new CapturingTool();
    Tool tool = new CommandAliasTool("executeCommand", "alias", shell);

    ToolResult result = tool.execute(json("{\"command\":\"echo hi\",\"timeoutSeconds\":3}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content()).isEqualTo("echo hi");
    assertThat(shell.lastArgs.path("timeoutSeconds").asInt()).isEqualTo(3);
  }

  @Test
  void projectCommand_explicit_command_wins_over_detection() throws Exception {
    Files.writeString(root.resolve("package.json"), "{\"scripts\":{\"lint\":\"eslint .\"}}");
    CapturingTool shell = new CapturingTool();
    Tool tool = new ProjectCommandTool("lintProject", "lint", ProjectCommandTool.Kind.LINT, shell);

    ToolResult result =
        tool.execute(json("{\"command\":\"npm run custom-lint\",\"timeoutSeconds\":9}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(shell.lastArgs.path("command").asText()).isEqualTo("npm run custom-lint");
    assertThat(shell.lastArgs.path("timeoutSeconds").asInt()).isEqualTo(9);
  }

  @Test
  void projectCommand_detects_node_scripts_and_package_manager() throws Exception {
    Files.writeString(root.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
    Files.writeString(
        root.resolve("package.json"),
        "{\"scripts\":{\"test\":\"vitest run\",\"lint\":\"eslint .\",\"format\":\"prettier -w"
            + " .\"}}");
    CapturingTool shell = new CapturingTool();

    List<Tool> tools =
        List.of(
            new ProjectCommandTool("testProject", "test", ProjectCommandTool.Kind.TEST, shell),
            new ProjectCommandTool("lintProject", "lint", ProjectCommandTool.Kind.LINT, shell),
            new ProjectCommandTool(
                "formatProject", "format", ProjectCommandTool.Kind.FORMAT, shell),
            new ProjectCommandTool(
                "installDependencies", "install", ProjectCommandTool.Kind.INSTALL, shell));

    tools.get(0).execute(JSON.createObjectNode(), ctx());
    assertThat(shell.lastArgs.path("command").asText()).isEqualTo("pnpm run test");
    tools.get(1).execute(JSON.createObjectNode(), ctx());
    assertThat(shell.lastArgs.path("command").asText()).isEqualTo("pnpm run lint");
    tools.get(2).execute(JSON.createObjectNode(), ctx());
    assertThat(shell.lastArgs.path("command").asText()).isEqualTo("pnpm run format");
    tools.get(3).execute(JSON.createObjectNode(), ctx());
    assertThat(shell.lastArgs.path("command").asText()).isEqualTo("pnpm install --frozen-lockfile");
  }

  @Test
  void projectCommand_without_command_or_detection_returns_invalidArgs() {
    CapturingTool shell = new CapturingTool();
    Tool tool = new ProjectCommandTool("lintProject", "lint", ProjectCommandTool.Kind.LINT, shell);

    ToolResult result = tool.execute(JSON.createObjectNode(), ctx());

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.INVALID_ARGS);
  }

  @Test
  void buildProject_accepts_command_alias_for_plannerCommand() {
    BuildCommandResolver resolver = mock(BuildCommandResolver.class);
    MavenDependencyValidator validator = mock(MavenDependencyValidator.class);
    when(validator.validate(root))
        .thenReturn(new MavenDependencyValidator.ValidationResult(true, List.of(), 0));
    when(resolver.resolve(eq(root), eq("printf build-ok")))
        .thenReturn(
            BuildCommand.builder()
                .command("printf build-ok")
                .timeoutSeconds(5)
                .sourceDescription("test")
                .build());
    BuildProjectTool tool = new BuildProjectTool(resolver, validator, null, null);

    ToolResult result = tool.execute(json("{\"command\":\"printf build-ok\"}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content()).contains("command=printf build-ok", "build-ok");
  }

  @Test
  void buildAlias_maps_command_to_plannerCommand_before_delegating() {
    BuildCommandResolver resolver = mock(BuildCommandResolver.class);
    MavenDependencyValidator validator = mock(MavenDependencyValidator.class);
    when(validator.validate(root))
        .thenReturn(new MavenDependencyValidator.ValidationResult(true, List.of(), 0));
    when(resolver.resolve(eq(root), eq("printf alias-ok")))
        .thenReturn(
            BuildCommand.builder()
                .command("printf alias-ok")
                .timeoutSeconds(5)
                .sourceDescription("test")
                .build());
    BuildProjectTool buildProjectTool = new BuildProjectTool(resolver, validator, null, null);
    Tool alias = new BuildAliasTool("runBuild", "alias", buildProjectTool);

    ToolResult result = alias.execute(json("{\"command\":\"printf alias-ok\"}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content()).contains("command=printf alias-ok", "alias-ok");
  }
}
