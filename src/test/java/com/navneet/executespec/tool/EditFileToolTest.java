package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.navneet.executespec.sse.SseBroadcaster;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test for {@link EditFileTool} — drives the real tool through the {@link
 * FileSandboxPolicy} (LOW = allow) + the read-before-write / staleness gate ({@link
 * ReadFileStateStore}) + the {@link StringEditMatcher} cascade, against a real {@link TempDir}.
 * Complements {@link StringEditMatcherTest}, which covers the pure matching algorithm.
 * (RL-2026-05-062)
 */
@DisplayName("EditFileTool end-to-end")
class EditFileToolTest {

  @TempDir Path workspace;

  private final ObjectMapper om = new ObjectMapper();
  private EditFileTool tool;
  private ReadFileStateStore readState;
  private ExecutionContext ctx;

  @BeforeEach
  void setUp() {
    readState = new ReadFileStateStore();
    tool = new EditFileTool(new FileSandboxPolicy(), mock(SseBroadcaster.class), readState, null);
    ctx = new ExecutionContext("run-1", "T1", workspace);
  }

  private Path writeAndRead(String relPath, String content) throws IOException {
    Path resolved = PathSafety.safeResolve(workspace, relPath);
    Files.createDirectories(resolved.getParent());
    Files.writeString(resolved, content);
    readState.recordReadFromDisk(ctx.runId(), resolved); // satisfy read-before-edit
    return resolved;
  }

  private JsonNode args(String path, String[]... edits) {
    ObjectNode node = om.createObjectNode();
    node.put("path", path);
    ArrayNode arr = node.putArray("edits");
    for (String[] e : edits) {
      ObjectNode en = arr.addObject();
      en.put("oldStr", e[0]);
      en.put("newStr", e[1]);
    }
    return node;
  }

  @Test
  void applies_an_exact_edit() throws IOException {
    Path f = writeAndRead("a.txt", "line1\nTARGET\nline3\n");
    ToolResult r = tool.execute(args("a.txt", new String[] {"TARGET", "CHANGED"}), ctx);
    assertThat(r).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.readString(f)).isEqualTo("line1\nCHANGED\nline3\n");
  }

  @Test
  void whitespace_drift_is_tolerated_through_the_tool() throws IOException {
    Path f = writeAndRead("b.py", "def f():\n    return 1\n");
    // oldStr carries trailing spaces the file doesn't have -> exact fails, fuzzy applies.
    ToolResult r =
        tool.execute(args("b.py", new String[] {"    return 1   ", "    return 2"}), ctx);
    assertThat(r).isInstanceOf(ToolResult.Ok.class);
    assertThat(Files.readString(f)).isEqualTo("def f():\n    return 2\n");
  }

  @Test
  void read_before_edit_gate_blocks_an_unread_file() throws IOException {
    Path resolved = PathSafety.safeResolve(workspace, "c.txt");
    Files.writeString(resolved, "hello\n"); // written but NOT recorded as read
    ToolResult r = tool.execute(args("c.txt", new String[] {"hello", "bye"}), ctx);
    assertThat(r).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) r).message()).contains("read-before-edit");
    assertThat(Files.readString(resolved)).isEqualTo("hello\n"); // untouched
  }

  @Test
  void missing_file_is_not_found() {
    ToolResult r = tool.execute(args("nope.txt", new String[] {"x", "y"}), ctx);
    assertThat(r).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) r).kind()).isEqualTo(ToolResult.ErrorKind.NOT_FOUND);
  }

  @Test
  void no_partial_application_when_a_later_edit_fails() throws IOException {
    Path f = writeAndRead("d.txt", "alpha\nbeta\n");
    // first edit valid, second edit's oldStr absent -> whole batch rejected, file unchanged.
    ToolResult r =
        tool.execute(
            args("d.txt", new String[] {"alpha", "ALPHA"}, new String[] {"ZZZ", "zzz"}), ctx);
    assertThat(r).isInstanceOf(ToolResult.Err.class);
    assertThat(Files.readString(f)).isEqualTo("alpha\nbeta\n"); // nothing written
  }
}
