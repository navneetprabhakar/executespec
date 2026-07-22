package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-549 — the generic {@code move}/{@code delete} tools may not erase the workspace root or mutate
 * reserved control-plane paths (.git/**, .agent-context/**). Path containment alone is not enough:
 * a path lexically inside the workspace can still be {@code .git/config} or {@code "."}.
 */
@DisplayName("RR-549 destructive-tool reserved/root guards")
class DestructiveToolReservedPathTest {

  @TempDir Path workspace;

  private final ObjectMapper om = new ObjectMapper();
  private final DeletePathTool delete = new DeletePathTool();
  private final MovePathTool move = new MovePathTool();

  private ExecutionContext ctx() {
    return new ExecutionContext("run-1", "T1", workspace);
  }

  @Test
  void delete_refuses_git_control_directory() throws IOException {
    Files.createDirectories(workspace.resolve(".git"));
    Files.writeString(workspace.resolve(".git/config"), "[core]\n");
    ObjectNode args = om.createObjectNode();
    args.put("path", ".git/config");
    ToolResult r = delete.execute(args, ctx());
    assertThat(r).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) r).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.exists(workspace.resolve(".git/config"))).isTrue();
  }

  @Test
  void delete_refuses_the_workspace_root() throws IOException {
    Files.writeString(workspace.resolve("keep.txt"), "x");
    ObjectNode args = om.createObjectNode();
    args.put("path", ".");
    args.put("recursive", true);
    ToolResult r = delete.execute(args, ctx());
    assertThat(r).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) r).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.exists(workspace.resolve("keep.txt"))).isTrue();
  }

  @Test
  void move_refuses_git_control_endpoint() throws IOException {
    Files.writeString(workspace.resolve("src.txt"), "x");
    ObjectNode args = om.createObjectNode();
    args.put("src", "src.txt");
    args.put("dst", ".git/hooks/pre-commit");
    ToolResult r = move.execute(args, ctx());
    assertThat(r).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) r).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(Files.exists(workspace.resolve(".git/hooks/pre-commit"))).isFalse();
  }
}
