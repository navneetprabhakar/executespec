package com.navneet.executespec.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.codeintel.entity.ProjectArchitectureDoc;
import com.navneet.executespec.codeintel.repository.ProjectArchitectureDocRepository;
import com.navneet.executespec.codeintel.repository.ProjectCodeMapRepository;
import com.navneet.executespec.project.repository.ProjectRepoLinkRepository;
import com.navneet.executespec.project.repository.ProjectRepoRepository;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.security.GeneratedSecretScanner;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class ReadFileToolSecretBoundaryTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @TempDir Path workspaceRoot;

  @Test
  void projectDatabaseFallbackCannotReturnCredentialContent() throws Exception {
    Fixture fixture = fixture();
    String credential = "sk-ant-abcdefghijklmnopqrstuvwxyz1234567890";
    when(fixture.workspace.readFile("project-1", "architecture.md")).thenReturn(Optional.empty());
    when(fixture.architecture.findFirstByProjectIdOrderByVersionDesc("project-1"))
        .thenReturn(
            Optional.of(
                ProjectArchitectureDoc.builder()
                    .projectId("project-1")
                    .content("deployment key: " + credential)
                    .build()));

    ToolResult result =
        fixture.tool.execute(JSON.readTree("{\"path\":\"project:architecture.md\"}"), context());

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(((ToolResult.Err) result).message())
        .contains("credential content")
        .doesNotContain(credential);
  }

  @Test
  void projectReadFailureDoesNotExposeExceptionMessageOrStack() throws Exception {
    Fixture fixture = fixture();
    String secret = "jdbc:postgresql://user:password@private-db/customer";
    when(fixture.workspace.readFile("project-1", "architecture.md"))
        .thenThrow(new IllegalStateException(secret));
    Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(ReadFileTool.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    ToolResult result;
    try {
      result =
          fixture.tool.execute(JSON.readTree("{\"path\":\"project:architecture.md\"}"), context());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }

    assertThat(result).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) result).message()).isEqualTo("project file read failed");
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .allSatisfy(message -> assertThat(message).doesNotContain(secret));
    assertThat(appender.list).extracting(ILoggingEvent::getThrowableProxy).containsOnlyNulls();
  }

  private Fixture fixture() {
    ProjectWorkspaceService workspace = mock(ProjectWorkspaceService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    ProjectArchitectureDocRepository architecture = mock(ProjectArchitectureDocRepository.class);
    when(runs.findById("run-1"))
        .thenReturn(
            Optional.of(PipelineRun.builder().runId("run-1").projectId("project-1").build()));
    ReadFileTool tool =
        new ReadFileTool(
            workspace,
            runs,
            architecture,
            mock(ProjectCodeMapRepository.class),
            mock(ProjectRepoLinkRepository.class),
            mock(ProjectRepoRepository.class),
            new ReadFileStateStore(),
            new GeneratedSecretScanner());
    return new Fixture(tool, workspace, architecture);
  }

  private ExecutionContext context() {
    return new ExecutionContext("run-1", "task-1", workspaceRoot);
  }

  private record Fixture(
      ReadFileTool tool,
      ProjectWorkspaceService workspace,
      ProjectArchitectureDocRepository architecture) {}
}
