package com.navneet.executespec.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.api.dto.RunDetail;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RunDetailSseServiceTest {

  private final SseBroadcaster broadcaster = org.mockito.Mockito.mock(SseBroadcaster.class);
  private final StateStore stateStore = org.mockito.Mockito.mock(StateStore.class);
  private final RunMapper runMapper = org.mockito.Mockito.mock(RunMapper.class);
  private final ObjectMapper objectMapper = new ObjectMapper();
  private final RunDetailSseService service =
      new RunDetailSseService(broadcaster, stateStore, runMapper, objectMapper);

  @Test
  void continuationDecisionTriggersRunDetailRefresh() {
    PipelineRun run = PipelineRun.builder().runId("run-1").status("PARTIAL").build();
    RunDetail detail = RunDetail.builder().runId("run-1").status("PARTIAL").tasks(List.of()).build();
    when(stateStore.findRun("run-1")).thenReturn(Optional.of(run));
    when(stateStore.getAllTasks("run-1")).thenReturn(List.of());
    when(runMapper.toDetail(run, List.of())).thenReturn(detail);

    service.onRunEvent(
        RunEvent.continuationDecision(
            "run-1",
            "task-1",
            "Continuation decision: STOP_BLOCKED",
            Map.of("decision", "STOP_BLOCKED")));

    ArgumentCaptor<RunEvent> eventCaptor = ArgumentCaptor.forClass(RunEvent.class);
    verify(broadcaster).emitDirect(eq("run-1"), eventCaptor.capture());
    RunEvent emitted = eventCaptor.getValue();
    assertThat(emitted.type()).isEqualTo("RUN_DETAIL");
    assertThat(emitted.payload()).contains("\"runId\":\"run-1\"");
    assertThat(emitted.payload()).contains("\"status\":\"PARTIAL\"");
  }

  @Test
  void plainLogDoesNotTriggerRunDetailRefresh() {
    service.onRunEvent(RunEvent.log("run-1", "task-1", "plain log"));

    verify(stateStore, never()).findRun(any());
    verify(broadcaster, never()).emitDirect(any(), any());
  }
}
