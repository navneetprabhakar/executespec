package com.navneet.executespec.codeintel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.project.workspace.ProjectWorkspaceService;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class ProjectBriefServiceTest {

  @Test
  void summariseUsesCanonicalTextRouterForMetadataOnlyCall() {
    ProjectWorkspaceService workspace = mock(ProjectWorkspaceService.class);
    LlmRouter llmRouter = mock(LlmRouter.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    when(modelResolver.resolve("ProjectBriefService", LlmProvider.OPENAI))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.OPENAI)
                .model("gpt-5.4-mini")
                .build());
    when(llmRouter.callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("project-brief-p1"),
            eq("ProjectBriefService"),
            eq("summary")))
        .thenReturn(ok("A concise architecture summary."));

    ProjectBriefService service = new ProjectBriefService(workspace, llmRouter, modelResolver);

    assertThat(service.summarise("p1", "# Architecture\nA service.", LlmProvider.OPENAI))
        .isEqualTo("A concise architecture summary.");
    verify(llmRouter)
        .callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("project-brief-p1"),
            eq("ProjectBriefService"),
            eq("summary"));
  }

  @Test
  void summariseFallsBackToHeadingWhenCanonicalCallFails() {
    ProjectWorkspaceService workspace = mock(ProjectWorkspaceService.class);
    LlmRouter llmRouter = mock(LlmRouter.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    when(modelResolver.resolve("ProjectBriefService", LlmProvider.OPENAI))
        .thenThrow(new IllegalStateException("transport disabled"));

    ProjectBriefService service = new ProjectBriefService(workspace, llmRouter, modelResolver);

    assertThat(service.summarise("p1", "# Architecture\nA service.", null))
        .isEqualTo("# Architecture");
  }

  private static ChatResponse ok(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }
}
