package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * RR-1547 — the credential-path policy governs ENUMERATION, not only reads. Listing the names alone
 * hands the model an exact inventory of credential files and can disclose infrastructure.
 */
class ListFilesToolSensitivePathTest {

  private final ListFilesTool tool = new ListFilesTool();
  private final ObjectMapper json = new ObjectMapper();

  @TempDir Path workspace;

  private String list(boolean recursive) {
    var args = json.createObjectNode();
    args.put("path", ".");
    args.put("recursive", recursive);
    ToolResult result = tool.execute(args, new ExecutionContext("run-1", "task-1", workspace));
    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    return ((ToolResult.Ok) result).content();
  }

  @Test
  void credentialFilesAreOmittedFromFlatAndRecursiveListings() throws Exception {
    Files.writeString(workspace.resolve(".env"), "SECRET=1");
    Files.writeString(workspace.resolve(".env.example"), "SECRET=");
    Files.writeString(workspace.resolve("id_rsa"), "key");
    Files.writeString(workspace.resolve("app.js"), "// code");
    Files.createDirectories(workspace.resolve("certs"));
    Files.writeString(workspace.resolve("certs/server.pem"), "cert");

    for (boolean recursive : new boolean[] {false, true}) {
      String body = list(recursive);
      assertThat(body).as("recursive=%s", recursive).contains("app.js", ".env.example");
      assertThat(body).as("recursive=%s", recursive).doesNotContain("id_rsa");
      assertThat(body).as("recursive=%s", recursive).doesNotContain("server.pem");
      // `.env` must be gone while `.env.example` remains — check the exact entry line.
      assertThat(body.lines().map(String::strip).toList())
          .as("recursive=%s", recursive)
          .doesNotContain(".env");
    }
  }

  @Test
  void symlinkToDirectoryIsNotAdvertisedAsADirectory() throws Exception {
    Path outside = Files.createTempDirectory("outside-target");
    try {
      Files.createSymbolicLink(workspace.resolve("link"), outside);
    } catch (UnsupportedOperationException | java.io.IOException e) {
      return; // symlinks unavailable on this platform — nothing to assert
    }

    String body = list(false);

    assertThat(body).contains("link");
    assertThat(body).doesNotContain("link/");
    assertThat(body).contains("(symlink)");
  }
}
