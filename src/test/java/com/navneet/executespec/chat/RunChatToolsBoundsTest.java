package com.navneet.executespec.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.deployment.WorkspaceFileService;
import com.navneet.executespec.tool.ExecutionContext;
import com.navneet.executespec.tool.SearchFilesTool;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

class RunChatToolsBoundsTest {

  @Test
  void listRunFilesReportsTotalButBoundsProviderVisibleManifest() throws Exception {
    WorkspaceFileService files = mock(WorkspaceFileService.class);
    List<String> manifest =
        IntStream.range(0, RunChatTools.MAX_FILE_LIST_ENTRIES + 200)
            .mapToObj(i -> "src/generated/feature-" + i + "/VeryLongGeneratedFileName.java")
            .toList();
    when(files.listFiles("run-1")).thenReturn(manifest);
    RunChatTools tools = new RunChatTools(files, mock(SearchFilesTool.class), new ObjectMapper());
    ToolCallback callback =
        tools
            .bind(
                new RunChatTools.ToolContext(
                    "run-1",
                    new ExecutionContext(
                        "run-1", "chat", Path.of(".").toAbsolutePath().normalize())))
            .stream()
            .filter(tool -> "list_run_files".equals(tool.getToolDefinition().name()))
            .findFirst()
            .orElseThrow();

    JsonNode result = new ObjectMapper().readTree(callback.call("{}"));

    assertThat(result.get("count").asInt()).isEqualTo(manifest.size());
    assertThat(result.get("returnedCount").asInt())
        .isLessThanOrEqualTo(RunChatTools.MAX_FILE_LIST_ENTRIES);
    assertThat(result.get("truncated").asBoolean()).isTrue();
    assertThat(result.get("files").toString().length())
        .isLessThan(RunChatTools.MAX_FILE_LIST_CHARS + 5_000);
  }
}
