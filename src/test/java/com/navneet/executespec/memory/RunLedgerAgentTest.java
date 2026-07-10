package com.navneet.executespec.memory;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.agent.PromptAssembler;
import com.navneet.executespec.config.AgentProperties;
import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;
import com.navneet.executespec.state.entity.PipelineRun;
import com.navneet.executespec.state.repository.AgentTaskRepository;
import com.navneet.executespec.state.repository.PipelineRunRepository;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class RunLedgerAgentTest {

  @Test
  void ledgerGenerationUsesCanonicalTextRouterWithStableAttribution() {
    LlmRouter llmRouter = mock(LlmRouter.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    ProjectRunLedgerRepository ledgerRepo = mock(ProjectRunLedgerRepository.class);
    PipelineRunRepository runRepo = mock(PipelineRunRepository.class);
    AgentTaskRepository taskRepo = mock(AgentTaskRepository.class);
    MemoryCompactionService compactionService = mock(MemoryCompactionService.class);
    AgentProperties agentProperties = new AgentProperties();
    PromptAssembler promptAssembler = mock(PromptAssembler.class);
    when(promptAssembler.assemble(eq("run-ledger.txt"), any())).thenReturn("ledger prompt");

    PipelineRun run =
        PipelineRun.builder()
            .runId("run-1")
            .projectId("project-1")
            .orgId("org-1")
            .aiProvider("OPENAI")
            .userRequest("Add the API")
            .runSummary("Implemented the API")
            .build();
    when(ledgerRepo.existsByRunId("run-1")).thenReturn(false);
    when(runRepo.findById("run-1")).thenReturn(Optional.of(run));
    when(taskRepo.findByRunId("run-1")).thenReturn(List.of());
    when(ledgerRepo.countByProjectId("project-1")).thenReturn(0);
    when(modelResolver.resolve(eq("RunLedgerAgent"), any()))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(com.navneet.executespec.router.LlmProvider.OPENAI)
                .model("gpt-5.4-mini")
                .build());
    when(llmRouter.callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("run-1"),
            eq("run-ledger"),
            eq("generate")))
        .thenReturn(ok("API work and test coverage."));

    RunLedgerAgent agent =
        new RunLedgerAgent(
            llmRouter,
            modelResolver,
            ledgerRepo,
            runRepo,
            taskRepo,
            compactionService,
            agentProperties,
            promptAssembler);

    agent.generateLedgerEntry("run-1");

    verify(llmRouter)
        .callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("run-1"),
            eq("run-ledger"),
            eq("generate"));
    verify(ledgerRepo).save(any(ProjectRunLedger.class));
  }

  private static ChatResponse ok(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }
}
