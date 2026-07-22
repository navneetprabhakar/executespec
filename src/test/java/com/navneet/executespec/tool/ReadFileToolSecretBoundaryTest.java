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

  @Test
  void forbiddenWorkspaceReadDoesNotGroundALaterEdit() throws Exception {
    // RR-1024 — a read the secret scanner FORBID must not record the read-before-write ledger
    // entry; otherwise a worker could authorize a full-file edit/overwrite of a file it never
    // successfully observed. A clean read still grounds the edit.
    ProjectWorkspaceService workspace = mock(ProjectWorkspaceService.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    when(runs.findById("run-1")).thenReturn(Optional.empty());
    ReadFileStateStore readState = new ReadFileStateStore();
    ReadFileTool tool =
        new ReadFileTool(
            workspace,
            runs,
            mock(ProjectArchitectureDocRepository.class),
            mock(ProjectCodeMapRepository.class),
            mock(ProjectRepoLinkRepository.class),
            mock(ProjectRepoRepository.class),
            readState,
            new GeneratedSecretScanner());

    String credential = "sk-ant-abcdefghijklmnopqrstuvwxyz1234567890";
    // Resolve exactly as the tool does (real path), so the ledger key matches on every platform.
    Path secretFile = PathSafety.safeResolve(workspaceRoot, "secret.txt");
    java.nio.file.Files.writeString(secretFile, "token = " + credential + "\n");
    Path cleanFile = PathSafety.safeResolve(workspaceRoot, "clean.txt");
    java.nio.file.Files.writeString(cleanFile, "hello world\n");

    ToolResult forbidden = tool.execute(JSON.readTree("{\"path\":\"secret.txt\"}"), context());
    assertThat(forbidden).isInstanceOf(ToolResult.Err.class);
    assertThat(((ToolResult.Err) forbidden).kind()).isEqualTo(ToolResult.ErrorKind.FORBIDDEN);
    assertThat(readState.check("run-1", secretFile, "token = " + credential + "\n"))
        .isEqualTo(ReadFileStateStore.Status.NOT_READ);

    ToolResult ok = tool.execute(JSON.readTree("{\"path\":\"clean.txt\"}"), context());
    assertThat(ok).isInstanceOf(ToolResult.Ok.class);
    assertThat(readState.check("run-1", cleanFile, "hello world\n"))
        .isEqualTo(ReadFileStateStore.Status.OK);
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
