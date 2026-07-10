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

  @Test
  void readFileAlias_maps_offset_and_limit_to_line_range_and_records_read() throws Exception {
    Files.writeString(root.resolve("a.txt"), "one\ntwo\nthree\n");
    ReadFileStateStore readState = new ReadFileStateStore();
    Tool tool = new ReadFileCompatibilityTool(readFileTool(readState));

    ToolResult result = tool.execute(json("{\"path\":\"a.txt\",\"offset\":1,\"limit\":1}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content()).isEqualTo("two");
  }

  @Test
  void writeFileAlias_returns_alreadyExists_for_existing_file() throws Exception {
    Files.writeString(root.resolve("a.txt"), "old\n");
    Tool tool =
        new WriteFileCompatibilityTool(
            new WriteFileTool(
                new FileSandboxPolicy(),
                new com.navneet.executespec.security.CodeAbuseScanner(),
                mock(com.navneet.executespec.sse.SseBroadcaster.class),
                new ReadFileStateStore(),
                null));

    ToolResult result = tool.execute(json("{\"path\":\"a.txt\",\"content\":\"new\\n\"}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.ALREADY_EXISTS);
  }

  @Test
  void editFileAlias_applies_after_read_and_honors_expectedCount() throws Exception {
    Files.writeString(root.resolve("a.txt"), "one\ntwo\ntwo\n");
    ReadFileStateStore readState = new ReadFileStateStore();
    ExecutionContext ctx = ctx();
    new ReadFileCompatibilityTool(readFileTool(readState))
        .execute(json("{\"path\":\"a.txt\"}"), ctx);
    Tool tool = new EditFileCompatibilityTool(editFileTool(readState));

    ToolResult mismatch =
        tool.execute(
            json(
                "{\"path\":\"a.txt\",\"old_str\":\"two\",\"new_str\":\"TWO\","
                    + "\"expected_count\":1}"),
            ctx);
    ToolResult result =
        tool.execute(
            json(
                "{\"path\":\"a.txt\",\"old_str\":\"two\",\"new_str\":\"TWO\","
                    + "\"expected_count\":2,\"replaceAll\":true}"),
            ctx);

    assertThat(mismatch).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) mismatch).kind()).isEqualTo(ToolResult.ErrorKind.INVALID_ARGS);
    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("one\nTWO\nTWO\n");
  }

  @Test
  void editFileAlias_returns_staleRead_when_file_was_not_read() throws Exception {
    Files.writeString(root.resolve("a.txt"), "one\n");
    Tool tool = new EditFileCompatibilityTool(editFileTool(new ReadFileStateStore()));

    ToolResult result =
        tool.execute(json("{\"path\":\"a.txt\",\"old_str\":\"one\",\"new_str\":\"two\"}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.STALE_READ);
  }

  @Test
  void astEditAlias_applies_through_readBeforeEdit_path() throws Exception {
    Files.writeString(root.resolve("a.txt"), "alpha\nbeta\n");
    ReadFileStateStore readState = new ReadFileStateStore();
    ExecutionContext ctx = ctx();
    new ReadFileCompatibilityTool(readFileTool(readState))
        .execute(json("{\"path\":\"a.txt\"}"), ctx);
    Tool tool = new AstEditCompatibilityTool("ast_edit", editFileTool(readState));

    ToolResult result =
        tool.execute(
            json("{\"path\":\"a.txt\",\"pattern\":\"beta\",\"replacement\":\"gamma\"}"), ctx);

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.readString(root.resolve("a.txt"))).isEqualTo("alpha\ngamma\n");
  }

  @Test
  void lspFallback_returns_symbols_and_references() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.writeString(
        root.resolve("src/app.js"),
        "export function buildThing() { return 1; }\nconst value = buildThing();\n");
    Tool tool = new LspFallbackTool();

    ToolResult symbols =
        tool.execute(json("{\"action\":\"document_symbols\",\"path\":\"src/app.js\"}"), ctx());
    ToolResult refs =
        tool.execute(json("{\"action\":\"references\",\"symbol\":\"buildThing\"}"), ctx());

    assertThat(symbols).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) symbols).content()).contains("buildThing");
    assertThat(refs).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) refs).content()).contains("src/app.js", "buildThing");
  }

  @Test
  void planAndMcpFallbackTools_are_operational() {
    Tool plan = new PlanStateTool("update_plan");
    Tool mcp = new McpClientFallbackTool("mcp_client");

    ToolResult planResult =
        plan.execute(json("{\"plan\":[{\"step\":\"inspect\",\"status\":\"completed\"}]}"), ctx());
    ToolResult mcpResult =
        mcp.execute(json("{\"method\":\"tools/list\",\"command\":\"node fake.js\"}"), ctx());

    assertThat(planResult).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) planResult).content()).contains("\"count\":1", "inspect");
    assertThat(mcpResult).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) mcpResult).message()).contains("allowlisted");
  }

  @Test
  void mcpClientFallback_calls_allowlisted_stdio_server() throws Exception {
    String payload =
        "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"backend_tool\"}]}}";
    Path script = root.resolve("mcp.sh");
    Files.writeString(
        script,
        "#!/bin/sh\n"
            + "cat >/dev/null\n"
            + "printf 'Content-Length: "
            + payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
            + "\\r\\n\\r\\n"
            + payload.replace("'", "'\"'\"'")
            + "'\n");
    script.toFile().setExecutable(true);
    String command = script.toAbsolutePath().toString();
    System.setProperty("executespec.mcp.allowlist", command);
    try {
      Tool mcp = new McpClientFallbackTool("mcp_client");

      ToolResult result =
          mcp.execute(json("{\"method\":\"tools/list\",\"command\":\"" + command + "\"}"), ctx());

      assertThat(result).isInstanceOf(ToolResult.Ok.class);
      assertThat(((ToolResult.Ok) result).content())
          .contains("server_allowlisted_stdio", "backend_tool");
    } finally {
      System.clearProperty("executespec.mcp.allowlist");
    }
  }

  @Test
  void glob_respects_basic_gitignore_and_skips_build_dirs() throws Exception {
    Files.createDirectories(root.resolve("src"));
    Files.createDirectories(root.resolve("target"));
    Files.writeString(root.resolve(".gitignore"), "ignored/\n");
    Files.writeString(root.resolve("src/App.java"), "class App {}\n");
    Files.writeString(root.resolve("target/Generated.java"), "class Generated {}\n");
    Files.createDirectories(root.resolve("ignored"));
    Files.writeString(root.resolve("ignored/Hidden.java"), "class Hidden {}\n");
    Tool tool = new GlobTool();

    ToolResult result = tool.execute(json("{\"pattern\":\"**/*.java\"}"), ctx());

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content())
        .contains("src/App.java")
        .doesNotContain("target/Generated.java", "ignored/Hidden.java");
  }

  @Test
  void move_and_delete_are_workspace_confined_filesystem_operations() throws Exception {
    Files.writeString(root.resolve("a.txt"), "body\n");
    Tool move = new MovePathTool();
    Tool delete = new DeletePathTool();

    ToolResult moved = move.execute(json("{\"src\":\"a.txt\",\"dst\":\"dir/b.txt\"}"), ctx());
    ToolResult deleted = delete.execute(json("{\"path\":\"dir/b.txt\"}"), ctx());

    assertThat(moved).isInstanceOf(ToolResult.Ok.class);
    assertThat(deleted).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.exists(root.resolve("a.txt"))).isFalse();
    assertThat(Files.exists(root.resolve("dir/b.txt"))).isFalse();
  }

  @Test
  void bashAlias_returns_json_for_success_and_commandFailed_for_nonzero() {
    Tool tool = new BashStructuredTool(new ShellTool(new ShellSandboxPolicy(), null, null));

    ToolResult ok = tool.execute(json("{\"command\":\"echo hi\"}"), ctx());
    ToolResult failed = tool.execute(json("{\"command\":\"test -f missing-file\"}"), ctx());

    assertThat(ok).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) ok).content()).contains("\"stdout\":\"hi\\n\"", "\"exit_code\":0");
    assertThat(failed).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) failed).kind()).isEqualTo(ToolResult.ErrorKind.COMMAND_FAILED);
    assertThat(((ToolResult.Err) failed).message()).contains("\"exit_code\":1");
  }

  private static ReadFileTool readFileTool(ReadFileStateStore readState) {
    return new ReadFileTool(null, null, null, null, null, null, readState);
  }

  private static EditFileTool editFileTool(ReadFileStateStore readState) {
    return new EditFileTool(
        new FileSandboxPolicy(),
        new com.navneet.executespec.security.CodeAbuseScanner(),
        mock(com.navneet.executespec.sse.SseBroadcaster.class),
        readState,
        null);
  }
}
