package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.SubagentExecutionService;
import com.navneet.executespec.agentsession.subagent.SubagentProtocol;
import com.navneet.executespec.auth.entity.AppUser;
import com.navneet.executespec.auth.security.AppUserDetails;
import com.navneet.executespec.config.featureflag.FeatureFlagService;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

class SubagentChatToolsTest {

  private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  private final SubagentExecutionService execution = mock(SubagentExecutionService.class);
  private final ObjectProvider<SubagentExecutionService> provider = mock(ObjectProvider.class);
  private final FeatureFlagService flags = mock(FeatureFlagService.class);
  private final AppUserDetails user =
      new AppUserDetails(
          AppUser.builder().id("user-1").email("u@example.com").status("ACTIVE").build(), null);

  @Test
  void modelToolReturnsEligibilityReasonWithoutExecutingWhenParentIsSaturated() throws Exception {
    when(provider.getObject()).thenReturn(execution);
    when(execution.evaluateDelegation(any(), any(), any()))
        .thenReturn(
            new SubagentProtocol.DelegationDecision(
                "parent",
                SubagentProtocol.Role.SCOUT,
                false,
                "child_capacity_exhausted",
                1,
                4,
                0,
                0,
                0.0,
                null));

    ToolCallback tool = new SubagentChatTools(provider, json).bind(user, "parent", "turn-1");
    JsonNode response = json.readTree(tool.call("{\"role\":\"SCOUT\",\"objective\":\"scan\"}"));

    assertThat(response.path("ok").asBoolean()).isFalse();
    assertThat(response.path("error").asText()).contains("not eligible");
    assertThat(response.path("eligibility").path("reason").asText())
        .isEqualTo("child_capacity_exhausted");
    verify(execution, never()).execute(any(), any(), any());
  }

  @Test
  void modelToolRechecksEligibilityBeforeExecutionAndReturnsEvidence() throws Exception {
    when(provider.getObject()).thenReturn(execution);
    when(execution.evaluateDelegation(any(), any(), any()))
        .thenReturn(
            new SubagentProtocol.DelegationDecision(
                "parent",
                SubagentProtocol.Role.VERIFIER,
                true,
                "eligible",
                1,
                0,
                4,
                0,
                0,
                "child"));
    SubagentProtocol.Result result =
        new SubagentProtocol.Result(
            new SubagentProtocol.Handle(
                "child",
                "parent",
                "turn-1",
                SubagentProtocol.Role.VERIFIER,
                "verify",
                1,
                4,
                0.5,
                "COMPLETED"),
            "verified src/Auth.java:42",
            java.util.List.of(new SubagentProtocol.Evidence("src/Auth.java:42", "guard")),
            1,
            0.01,
            java.time.Instant.now());
    when(execution.execute(any(), any(), any())).thenReturn(result);

    ToolCallback tool = new SubagentChatTools(provider, json).bind(user, "parent", "turn-1");
    JsonNode response =
        json.readTree(
            tool.call(
                "{\"role\":\"VERIFIER\",\"objective\":\"verify\",\"maxTurns\":4,\"maxCostUsd\":0.5}"));

    assertThat(response.path("ok").asBoolean()).isTrue();
    assertThat(response.path("eligibility").path("eligible").asBoolean()).isTrue();
    assertThat(response.path("result").path("evidence").get(0).path("citation").asText())
        .isEqualTo("src/Auth.java:42");
    verify(execution).execute(any(), any(), any());
  }

  @Test
  void enabledCoordinatorLaneUsesAuditedCoordinatorExecution() throws Exception {
    when(provider.getObject()).thenReturn(execution);
    when(flags.isEnabled(SubagentExecutionService.COORDINATOR_AUTO_FLAG)).thenReturn(true);
    when(execution.evaluateDelegation(any(), any(), any()))
        .thenReturn(
            new SubagentProtocol.DelegationDecision(
                "parent", SubagentProtocol.Role.SCOUT, true, "eligible", 1, 0, 4, 0, 0, "child"));
    SubagentProtocol.Result result =
        new SubagentProtocol.Result(
            new SubagentProtocol.Handle(
                "child",
                "parent",
                "turn-1",
                SubagentProtocol.Role.SCOUT,
                "scan",
                1,
                4,
                0.5,
                "COMPLETED"),
            "found src/App.java:10",
            java.util.List.of(),
            1,
            0.01,
            java.time.Instant.now());
    when(execution.executeCoordinator(any(), any(), any())).thenReturn(result);

    ToolCallback tool = new SubagentChatTools(provider, json, flags).bind(user, "parent", "turn-1");
    JsonNode response = json.readTree(tool.call("{\"role\":\"SCOUT\",\"objective\":\"scan\"}"));

    assertThat(response.path("ok").asBoolean()).isTrue();
    verify(execution).executeCoordinator(any(), any(), any());
    verify(execution, never()).execute(any(), any(), any());
  }
}
