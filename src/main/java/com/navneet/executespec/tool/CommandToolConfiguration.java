package com.navneet.executespec.tool;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CommandToolConfiguration {

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
