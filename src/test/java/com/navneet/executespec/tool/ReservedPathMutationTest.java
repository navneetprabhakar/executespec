package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.security.CodeAbuseScanner;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1018 — end-to-end proof that the generic mutation tools refuse Git and agent control-plane
 * paths at the DEFAULT guardrail level (LOW), where {@link FileSandboxPolicy} is a no-op.
 */
@DisplayName("Reserved control-plane paths — writeFile / editFile")
class ReservedPathMutationTest {

  @TempDir Path workspace;

  private final ObjectMapper om = new ObjectMapper();
  private WriteFileTool writeTool;
  private EditFileTool editTool;
  private ReadFileStateStore readState;
  private ExecutionContext ctx;

  @BeforeEach
  void setUp() {
    readState = new ReadFileStateStore();
    writeTool =
        new WriteFileTool(
            new FileSandboxPolicy(),
            new CodeAbuseScanner(),
            new GeneratedSecretScanner(),
            mock(SseBroadcaster.class),
            readState,
            null);
    editTool =
        new EditFileTool(
            new FileSandboxPolicy(),
            new CodeAbuseScanner(),
            new GeneratedSecretScanner(),
            mock(SseBroadcaster.class),
            readState,
            null);
    ctx = new ExecutionContext("run-1", "T1", workspace);
  }

  @Test
  @DisplayName("writeFile cannot plant a Git hook")
  void writeFileRefusesGitHook() {
    ObjectNode args = om.createObjectNode();
    args.put("path", ".git/hooks/pre-commit");
    args.put("content", "#!/bin/sh\necho pwned\n");

    ToolResult result = writeTool.execute(args, ctx);

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.exists(workspace.resolve(".git/hooks/pre-commit"))).isFalse();
  }

  @Test
  @DisplayName("editFile cannot rewrite an existing Git config, even after a legitimate read")
  void editFileRefusesGitConfig() throws IOException {
    Path config = workspace.resolve(".git/config");
    Files.createDirectories(config.getParent());
    Files.writeString(config, "[core]\n\tbare = false\n");
    readState.recordReadFromDisk(ctx.runId(), config);

    ObjectNode args = om.createObjectNode();
    args.put("path", ".git/config");
    ObjectNode edit = args.putArray("edits").addObject();
    edit.put("oldStr", "bare = false");
    edit.put("newStr", "bare = false\n\tsshCommand = /tmp/exfil.sh");

    ToolResult result = editTool.execute(args, ctx);

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.readString(config)).doesNotContain("sshCommand");
  }

  @Test
  @DisplayName("run evidence under .agent-context is not worker-writable")
  void writeFileRefusesAgentContext() {
    ObjectNode args = om.createObjectNode();
    args.put("path", ".agent-context/tool-output/build-1.log");
    args.put("content", "BUILD SUCCESS\n");

    assertThat(writeTool.execute(args, ctx)).isInstanceOf(ToolResult.Err.class);
  }

  @Test
  @DisplayName("ordinary source files are unaffected")
  void ordinarySourceWriteStillWorks() {
    ObjectNode args = om.createObjectNode();
    args.put("path", "src/main/java/App.java");
    args.put("content", "class App {}\n");

    assertThat(writeTool.execute(args, ctx)).isInstanceOf(ToolResult.Ok.class);
  }
}
