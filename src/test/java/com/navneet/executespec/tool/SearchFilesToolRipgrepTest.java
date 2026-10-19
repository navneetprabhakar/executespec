package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class SearchFilesToolRipgrepTest {

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void javaFallbackPreservesUnicodeMatches(@TempDir Path workspace) throws Exception {
    Files.writeString(workspace.resolve("test_main.py"), "assert slugify(\"İTEM\") == \"i-tem\"\n");
    var result =
        new SearchFilesFallbackWalk(new com.navneet.executespec.security.ContextSanitizer())
            .collect(
                workspace, null, java.util.regex.Pattern.compile("İTEM"), workspace.toRealPath());
    assertThat(result.matches())
        .singleElement()
        .asString()
        .contains("İTEM")
        .doesNotContain("Ä°TEM");
  }

  @Test
  void exactTextSearchUsesRipgrepBeforeTheWholeRepositoryJavaIndex(@TempDir Path workspace)
      throws Exception {
    assumeRipgrep();
    Files.writeString(workspace.resolve("literal.txt"), "prefix [literal.value] suffix\n");
    SearchFilesTool tool = new SearchFilesTool(new RepositorySearchService());

    ToolResult result =
        tool.execute(
            json.readTree(
                """
                {"pattern":"[literal.value]","mode":"EXACT"}
                """),
            new ExecutionContext("run", "task", workspace));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content())
        .contains("engine=ripgrep")
        .contains("literal.txt:1:prefix [literal.value] suffix");
  }

  @Test
  @Timeout(5)
  void matchCeilingTerminatesRipgrepWithoutBlockingOnItsStdout(@TempDir Path workspace)
      throws Exception {
    assumeRipgrep();
    String line = "needle\n";
    Files.writeString(workspace.resolve("many.txt"), line.repeat(2_000));
    SearchFilesTool tool = new SearchFilesTool(new RepositorySearchService());

    ToolResult result =
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
            Duration.ofSeconds(4),
            () ->
                tool.execute(
                    json.readTree(
                        """
                        {"pattern":"needle","mode":"EXACT","maxResults":2}
                        """),
                    new ExecutionContext("run", "task", workspace)));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content())
        .startsWith("1000+ match(es); engine=ripgrep; showing 1–2");
  }

  @Test
  void searchOmitsCredentialFilesAndRedactsSecretShapedSourcePreviews(@TempDir Path workspace)
      throws Exception {
    assumeRipgrep();
    Files.writeString(workspace.resolve(".env"), "API_KEY=abcdefghijklmnopsecret\n");
    Files.writeString(workspace.resolve("source.txt"), "api_key=abcdefghijklmnopsecret\n");
    SearchFilesTool tool = new SearchFilesTool(new RepositorySearchService());

    ToolResult result =
        tool.execute(
            json.readTree("{\"pattern\":\"api_key\",\"mode\":\"EXACT\"}"),
            new ExecutionContext("run", "task", workspace));

    assertThat(result).isInstanceOf(ToolResult.Ok.class);
    assertThat(((ToolResult.Ok) result).content())
        .contains("source.txt", "[REDACTED]")
        .doesNotContain(".env", "abcdefghijklmnopsecret");
  }

  private static void assumeRipgrep() throws InterruptedException {
    try {
      Process process = new ProcessBuilder("rg", "--version").start();
      Assumptions.assumeTrue(process.waitFor() == 0, "ripgrep is not installed");
    } catch (IOException unavailable) {
      Assumptions.assumeTrue(false, "ripgrep is not installed");
    }
  }
}
