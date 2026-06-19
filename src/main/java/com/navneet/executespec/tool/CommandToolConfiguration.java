package com.navneet.executespec.tool;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CommandToolConfiguration {

  @Bean
  Tool readFileSnakeTool(ReadFileTool readFileTool) {
    return new ReadFileCompatibilityTool(readFileTool);
  }

  @Bean
  Tool writeFileSnakeTool(WriteFileTool writeFileTool) {
    return new WriteFileCompatibilityTool(writeFileTool);
  }

  @Bean
  Tool editFileSnakeTool(EditFileTool editFileTool) {
    return new EditFileCompatibilityTool(editFileTool);
  }

  @Bean
  Tool globTool() {
    return new GlobTool();
  }

  @Bean
  Tool grepTool(SearchFilesTool searchFilesTool) {
    return new GrepCompatibilityTool(searchFilesTool);
  }

  @Bean
  Tool multiEditSnakeTool(EditFileTool editFileTool) {
    return new MultiEditCompatibilityTool("multi_edit", editFileTool);
  }

  @Bean
  Tool multiEditTool(EditFileTool editFileTool) {
    return new MultiEditCompatibilityTool("multiEdit", editFileTool);
  }

  @Bean
  Tool applyPatchSnakeTool(EditFileTool editFileTool) {
    return new MultiEditCompatibilityTool("apply_patch", editFileTool);
  }

  @Bean
  Tool applyPatchTool(EditFileTool editFileTool) {
    return new MultiEditCompatibilityTool("applyPatch", editFileTool);
  }

  @Bean
  Tool moveTool() {
    return new MovePathTool();
  }

  @Bean
  Tool renameTool() {
    return new CommandAliasTool("rename", "Alias of move.", new MovePathTool());
  }

  @Bean
  Tool deleteTool() {
    return new DeletePathTool();
  }

  @Bean
  Tool deletePathTool() {
    return new CommandAliasTool("deletePath", "Alias of delete.", new DeletePathTool());
  }

  @Bean
  Tool bashTool(ShellTool shellTool) {
    return new BashStructuredTool(shellTool);
  }

  @Bean
  Tool backgroundProcessSnakeTool(BackgroundJobTool backgroundJobTool) {
    return new BackgroundProcessCompatibilityTool(backgroundJobTool);
  }

  @Bean
  Tool backgroundProcessTool(BackgroundJobTool backgroundJobTool) {
    return new CommandAliasTool(
        "backgroundProcess",
        "Alias of background_process.",
        new BackgroundProcessCompatibilityTool(backgroundJobTool));
  }

  @Bean
  Tool webFetchSnakeTool() {
    return new WebFetchTool();
  }

  @Bean
  Tool webFetchTool() {
    return new CommandAliasTool("webFetch", "Alias of web_fetch.", new WebFetchTool());
  }

  @Bean
  Tool webSearchSnakeTool() {
    return new WebSearchTool();
  }

  @Bean
  Tool webSearchTool() {
    return new CommandAliasTool("webSearch", "Alias of web_search.", new WebSearchTool());
  }

  @Bean
  Tool diagnosticsTool(ShellTool shellTool) {
    return new DiagnosticsTool(shellTool);
  }

  @Bean
  Tool astSearchSnakeTool(SearchFilesTool searchFilesTool) {
    return new GrepCompatibilityTool(searchFilesTool) {
      @Override
      public String name() {
        return "ast_search";
      }

      @Override
      public String description() {
        return "Structural search is not wired server-side yet; this falls back to regex content"
            + " search for local-hands/client parity.";
      }
    };
  }

  @Bean
  Tool astSearchTool(SearchFilesTool searchFilesTool) {
    return new CommandAliasTool(
        "astSearch", "Alias of ast_search.", astSearchSnakeTool(searchFilesTool));
  }

  @Bean
  Tool lspTool() {
    return new LspFallbackTool();
  }

  @Bean
  Tool astEditSnakeTool(EditFileTool editFileTool) {
    return new AstEditCompatibilityTool("ast_edit", editFileTool);
  }

  @Bean
  Tool astEditTool(EditFileTool editFileTool) {
    return new AstEditCompatibilityTool("astEdit", editFileTool);
  }

  @Bean
  Tool mcpClientSnakeTool() {
    return new McpClientFallbackTool("mcp_client");
  }

  @Bean
  Tool mcpClientTool() {
    return new McpClientFallbackTool("mcpClient");
  }

  @Bean
  Tool spawnSubagentSnakeTool() {
    return new SpawnSubagentFallbackTool("spawn_subagent");
  }

  @Bean
  Tool spawnSubagentTool() {
    return new SpawnSubagentFallbackTool("spawnSubagent");
  }

  @Bean
  Tool updatePlanSnakeTool() {
    return new PlanStateTool("update_plan");
  }

  @Bean
  Tool updatePlanTool() {
    return new PlanStateTool("updatePlan");
  }

  @Bean
  Tool todoTool() {
    return new PlanStateTool("todo");
  }

  @Bean
  Tool executeCommandTool(ShellTool shellTool) {
    return new CommandAliasTool(
        "executeCommand",
        "Executes a sandboxed terminal command in the workspace. Alias of shell for"
            + " client/local-hands parity.",
        shellTool);
  }

  @Bean
  Tool runCommandTool(ShellTool shellTool) {
    return new CommandAliasTool(
        "runCommand",
        "Runs a sandboxed terminal command in the workspace. Alias of shell for client/local-hands"
            + " parity.",
        shellTool);
  }

  @Bean
  Tool terminalCommandTool(ShellTool shellTool) {
    return new CommandAliasTool(
        "terminalCommand",
        "Runs a sandboxed terminal command in the workspace. Alias of shell for client/local-hands"
            + " parity.",
        shellTool);
  }

  @Bean
  Tool buildTool(BuildProjectTool buildProjectTool) {
    return new BuildAliasTool(
        "build",
        "Builds the project using an explicit command or ExecuteSpec's project build detection."
            + " Alias of buildProject.",
        buildProjectTool);
  }

  @Bean
  Tool runBuildTool(BuildProjectTool buildProjectTool) {
    return new BuildAliasTool(
        "runBuild",
        "Runs the project build using an explicit command or ExecuteSpec's project build detection."
            + " Alias of buildProject.",
        buildProjectTool);
  }

  @Bean
  Tool testProjectTool(ShellTool shellTool) {
    return new ProjectCommandTool(
        "testProject",
        "Runs the project's detected test command, or an explicit command, through the sandboxed"
            + " shell.",
        ProjectCommandTool.Kind.TEST,
        shellTool);
  }

  @Bean
  Tool installDependenciesTool(ShellTool shellTool) {
    return new ProjectCommandTool(
        "installDependencies",
        "Installs project dependencies using lockfile/project metadata detection, or an explicit"
            + " command.",
        ProjectCommandTool.Kind.INSTALL,
        shellTool);
  }

  @Bean
  Tool lintProjectTool(ShellTool shellTool) {
    return new ProjectCommandTool(
        "lintProject",
        "Runs the project's detected lint command, or an explicit command, through the sandboxed"
            + " shell.",
        ProjectCommandTool.Kind.LINT,
        shellTool);
  }

  @Bean
  Tool formatProjectTool(ShellTool shellTool) {
    return new ProjectCommandTool(
        "formatProject",
        "Runs the project's detected formatting command, or an explicit command, through the"
            + " sandboxed shell.",
        ProjectCommandTool.Kind.FORMAT,
        shellTool);
  }
}
