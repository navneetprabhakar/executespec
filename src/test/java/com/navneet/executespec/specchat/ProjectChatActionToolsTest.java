package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.harness.GateStore;
import com.navneet.executespec.integration.RunLifecycleService;
import com.navneet.executespec.integration.RunOrchestrator;
import com.navneet.executespec.report.ValidationReportService;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pins the governance guard on the chat action tools (design §10 #1). The write/governance tools
 * (approve / reject / cancel) must run {@link RunAccessGuard#requireRunWriteAccess} — NOT the read
 * guard — and must NOT perform the mutation when access is denied. The tool body itself never
 * throws: a denied guard surfaces as an {@code ok:false} envelope so the LLM loop continues rather
 * than 500-ing.
 */
class ProjectChatActionToolsTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final RunOrchestrator runOrchestrator = mock(RunOrchestrator.class);
  private final RunLifecycleService runLifecycleService = mock(RunLifecycleService.class);
  private final PipelineRunRepository pipelineRunRepo = mock(PipelineRunRepository.class);
  private final ValidationReportService validationReportService =
      mock(ValidationReportService.class);
  private final GateStore gateStore = mock(GateStore.class);
  private final RunAccessGuard runAccessGuard = mock(RunAccessGuard.class);
  private final AppUserDetails userDetails = mock(AppUserDetails.class);

  private final ProjectChatActionTools tools =
      new ProjectChatActionTools(
          runOrchestrator,
          runLifecycleService,
          pipelineRunRepo,
          validationReportService,
          gateStore,
          runAccessGuard,
          mapper);

  private ToolCallback tool(String name) {
    return tool(name, "p");
  }

  private ToolCallback tool(String name, String projectId) {
    return tools
        .bind(
            new ProjectChatActionTools.ToolContext(
                userDetails, projectId, "o", "s", "deepseek", null))
        .stream()
        .filter(t -> name.equals(t.getToolDefinition().name()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no tool named " + name));
  }

  private JsonNode env(String json) throws Exception {
    return mapper.readTree(json);
  }

  @Test
  void approvePlan_usesWriteGuard_andWhenDenied_doesNotOpenGate() throws Exception {
    // The hardened write guard rejects (e.g. a cross-org read-only member).
    when(runAccessGuard.requireRunWriteAccess(eq("r1"), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"));

    JsonNode out = env(tool("approve_plan").call("{\"runId\":\"r1\"}"));

    assertThat(out.get("ok").asBoolean()).isFalse();
    // The mutation must NOT happen on a denied write.
    verify(gateStore, never()).setPlanGate(anyString(), anyBoolean());
  }

  @Test
  void approvePlan_whenWriteAccessGranted_opensGate() throws Exception {
    PipelineRun run = mock(PipelineRun.class);
    when(run.getRunId()).thenReturn("r1");
    when(runAccessGuard.requireRunWriteAccess(eq("r1"), any())).thenReturn(run);

    JsonNode out = env(tool("approve_plan").call("{\"runId\":\"r1\"}"));

    assertThat(out.get("ok").asBoolean()).isTrue();
    verify(gateStore).setPlanGate("r1", true);
  }

  @Test
  void cancelRun_usesWriteGuard_andWhenDenied_doesNotCancel() throws Exception {
    when(runAccessGuard.requireRunWriteAccess(eq("r9"), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "forbidden"));

    JsonNode out = env(tool("cancel_run").call("{\"runId\":\"r9\"}"));

    assertThat(out.get("ok").asBoolean()).isFalse();
    verify(runLifecycleService, never()).cancel(anyString());
  }

  @Test
  void listRecentRuns_isReadOnly_returnsRunsForCaller() throws Exception {
    AppUser caller = mock(AppUser.class);
    when(caller.getId()).thenReturn("u1");
    when(userDetails.getUser()).thenReturn(caller);
    PipelineRun r = mock(PipelineRun.class);
    when(r.getRunId()).thenReturn("r1");
    when(r.getStatus()).thenReturn("COMPLETE");
    when(pipelineRunRepo.findByCreatedByUserIdOrderByStartedAtDesc(any(), any()))
        .thenReturn(new PageImpl<>(List.of(r)));

    JsonNode out = env(tool("list_recent_runs", null).call("{\"limit\":10}"));

    assertThat(out.get("ok").asBoolean()).isTrue();
    assertThat(out.get("count").asInt()).isEqualTo(1);
    // A read tool must NOT touch the write guard.
    verify(runAccessGuard, never()).requireRunWriteAccess(anyString(), any());
  }

  @Test
  void submitRun_inheritsSessionProvider_whenToolOmitsProvider() throws Exception {
    AppUser caller = mock(AppUser.class);
    when(caller.getId()).thenReturn("u1");
    when(userDetails.getUser()).thenReturn(caller);
    PipelineRun submitted = mock(PipelineRun.class);
    when(submitted.getRunId()).thenReturn("r-deepseek");
    when(submitted.getStatus()).thenReturn("AWAITING_APPROVAL");
    when(runOrchestrator.submitRun(
            anyString(),
            eq("spec-chat"),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            eq("deepseek"),
            eq("u1"),
            eq("o"),
            eq("p"),
            eq("PRODUCTION"),
            any(),
            any(),
            eq(false),
            any(),
            any(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            nullable(String.class)))
        .thenReturn(submitted);

    JsonNode out = env(tool("submit_run").call("{\"spec\":\"create a codemap\"}"));

    assertThat(out.get("ok").asBoolean()).isTrue();
    verify(runOrchestrator)
        .submitRun(
            eq("create a codemap"),
            eq("spec-chat"),
            any(),
            any(),
            any(),
            any(),
            eq(false),
            eq(false),
            any(),
            eq("deepseek"),
            eq("u1"),
            eq("o"),
            eq("p"),
            eq("PRODUCTION"),
            any(),
            any(),
            eq(false),
            any(),
            any(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            nullable(String.class));
  }

  @Test
  void submitRun_canRequestCheapLowTaskStartWithoutPinningModel() throws Exception {
    AppUser caller = mock(AppUser.class);
    when(caller.getId()).thenReturn("u1");
    when(userDetails.getUser()).thenReturn(caller);
    PipelineRun submitted = mock(PipelineRun.class);
    when(submitted.getRunId()).thenReturn("r-light");
    when(submitted.getStatus()).thenReturn("AWAITING_APPROVAL");
    when(runOrchestrator.submitRun(
            anyString(),
            eq("spec-chat"),
            any(),
            any(),
            any(),
            any(),
            anyBoolean(),
            anyBoolean(),
            any(),
            eq("deepseek"),
            eq("u1"),
            eq("o"),
            eq("p"),
            eq("PRODUCTION"),
            any(),
            any(),
            eq(false),
            any(),
            any(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            eq("LIGHTWEIGHT")))
        .thenReturn(submitted);

    JsonNode out =
        env(tool("submit_run")
            .call("{\"spec\":\"create a codemap\",\"startLowTasksOnCheapTier\":true}"));

    assertThat(out.get("ok").asBoolean()).isTrue();
    verify(runOrchestrator)
        .submitRun(
            eq("create a codemap"),
            eq("spec-chat"),
            any(),
            any(),
            any(),
            any(),
            eq(false),
            eq(false),
            any(),
            eq("deepseek"),
            eq("u1"),
            eq("o"),
            eq("p"),
            eq("PRODUCTION"),
            any(),
            any(),
            eq(false),
            any(),
            any(),
            nullable(Boolean.class),
            nullable(Boolean.class),
            eq("LIGHTWEIGHT"));
  }
}
