package com.navneet.executespec.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.agentsession.entity.AgentSession;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentSessionRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.report.ValidationReportRepository;
import com.navneet.executespec.sse.SseBroadcaster;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;
import com.navneet.executespec.testgen.repository.TestPlanRepository;
import com.navneet.executespec.testgen.repository.TestResultRepository;
import com.navneet.executespec.testgen.repository.TestTaskRepository;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;

class WebQaFixtureControllerTest {

  @Test
  void cleanupDeletesSeededAgentGraphForTheAuthenticatedUser() throws Exception {
    AgentSession session = AgentSession.builder().id("web-qa-session").build();
    AgentSessionRepository sessions = mock(AgentSessionRepository.class);
    PipelineRunRepository runs = mock(PipelineRunRepository.class);
    PipelineRun run = PipelineRun.builder().runId("web-qa-run").build();
    AgentTaskRepository tasks = mock(AgentTaskRepository.class);
    when(tasks.findByRunId("web-qa-run")).thenReturn(List.of());
    ValidationReportRepository reports = mock(ValidationReportRepository.class);
    TestPlanRepository plans = mock(TestPlanRepository.class);
    when(reports.findByRunId("web-qa-run")).thenReturn(java.util.Optional.empty());
    when(plans.findByRunId("web-qa-run")).thenReturn(java.util.Optional.empty());
    when(runs.findByCreatedByUserIdOrderByStartedAtDesc(eq("user-1"), any()))
        .thenReturn(new PageImpl<>(List.of(run)));
    when(sessions.findByUserIdAndIdStartingWith("user-1", "web-qa-")).thenReturn(List.of(session));
    AgentActionEventRepository events = mock(AgentActionEventRepository.class);
    AgentTurnRepository turns = mock(AgentTurnRepository.class);
    AppUser user =
        AppUser.builder()
            .id("user-1")
            .email("admin@example.com")
            .firstName("Admin")
            .globalRole("SUPER_ADMIN")
            .orgId("org-1")
            .status("ACTIVE")
            .build();
    WebQaFixtureController controller =
        new WebQaFixtureController(
            runs,
            tasks,
            reports,
            plans,
            mock(TestTaskRepository.class),
            mock(TestResultRepository.class),
            sessions,
            turns,
            events,
            mock(SseBroadcaster.class));

    Map<String, Object> result = controller.cleanup(new AppUserDetails(user, null));

    assertThat(result).containsEntry("deletedAgentSessions", 1);
    assertThat(result).containsEntry("deletedRuns", 1);
    verify(runs).delete(run);
    verify(events).deleteBySessionId("web-qa-session");
    verify(turns).deleteBySessionId("web-qa-session");
    verify(sessions).delete(session);
  }
}
