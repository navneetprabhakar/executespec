package com.navneet.executespec.api.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import com.navneet.executespec.api.RunMapper;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.auth.security.RunAccessGuard;
import com.navneet.executespec.run.CustomerRunSubmissionService;
import com.navneet.executespec.state.StateStore;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RunControllerForkProviderOverrideTest {

  @Mock private CustomerRunSubmissionService customerRunSubmissionService;
  @Mock private StateStore stateStore;
  @Mock private RunMapper mapper;
  @Mock private RunAccessGuard guard;
  @InjectMocks private RunController controller;

  @Test
  void providerRetryPreservesExactRepositoryScopeAndDropsCrossProviderModelPin() {
    UUID first = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID second = UUID.fromString("00000000-0000-0000-0000-000000000001");
    PipelineRun source = new PipelineRun();
    source.setRunId("source-run");
    source.setUserRequest("build deterministic endpoint");
    source.setExecutionMode("PRODUCTION");
    source.setAiProvider("ANTHROPIC");
    source.setPlannerModel("claude-sonnet-4-6");
    source.setProjectId("project-1");
    source.setSelectedRepoIds("[\"" + first + "\",\"" + second + "\"]");
    source.setSkipAutoPush(true);
    source.setUserCostCap(1.75);
    AppUserDetails actor =
        new AppUserDetails(AppUser.builder().id("user-1").globalRole("USER").build(), null);

    when(stateStore.findRun("source-run")).thenReturn(Optional.of(source));
    when(customerRunSubmissionService.submit(any(), any()))
        .thenReturn(new CustomerRunSubmissionService.Submission(source, true, false));

    controller.forkRun(
        "source-run", actor, new RunController.ForkOverrides(null, null, "OPENAI"), "retry-key");

    ArgumentCaptor<CustomerRunSubmissionService.Command> command =
        ArgumentCaptor.forClass(CustomerRunSubmissionService.Command.class);
    verify(customerRunSubmissionService).submit(command.capture(), any());
    assertThat(command.getValue().aiProvider()).isEqualTo("OPENAI");
    assertThat(command.getValue().modelName()).isNull();
    assertThat(command.getValue().selectedRepoIds()).containsExactly(first, second);
    assertThat(command.getValue().projectId()).isEqualTo("project-1");
    assertThat(command.getValue().skipAutoPush()).isTrue();
    assertThat(command.getValue().userCostCap()).isEqualTo(1.75);
  }
}
