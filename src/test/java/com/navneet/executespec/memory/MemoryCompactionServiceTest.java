package com.navneet.executespec.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agent.LlmRouter;
import com.navneet.executespec.memory.entity.ProjectRunLedger;
import com.navneet.executespec.memory.repository.ProjectMemorySnapshotRepository;
import com.navneet.executespec.memory.repository.ProjectRunLedgerRepository;
import com.navneet.executespec.router.LlmProvider;
import com.navneet.executespec.router.ModelResolver;
import com.navneet.executespec.router.ResolvedModel;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

class MemoryCompactionServiceTest {

  @Test
  void compactionUsesCanonicalTextRouterForMetadataOnlySnapshot() {
    LlmRouter llmRouter = mock(LlmRouter.class);
    ModelResolver modelResolver = mock(ModelResolver.class);
    ProjectRunLedgerRepository ledgerRepo = mock(ProjectRunLedgerRepository.class);
    ProjectMemorySnapshotRepository snapshotRepo = mock(ProjectMemorySnapshotRepository.class);
    when(ledgerRepo.findUncompactedBeforeRunNumber("project-1", Integer.MAX_VALUE))
        .thenReturn(
            List.of(
                ProjectRunLedger.builder()
                    .projectId("project-1")
                    .runId("run-1")
                    .runNumber(1)
                    .entry("Implemented the API")
                    .build()));
    when(snapshotRepo.findTopByProjectIdOrderByVersionDesc("project-1"))
        .thenReturn(Optional.empty());
    when(modelResolver.resolve("RunLedgerAgent", LlmProvider.OPENAI))
        .thenReturn(
            ResolvedModel.builder()
                .actualProvider(LlmProvider.OPENAI)
                .model("gpt-5.4-mini")
                .build());
    when(llmRouter.callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("compact-project-1"),
            eq("memory-compaction"),
            eq("compact")))
        .thenReturn(ok("API work and test coverage."));

    MemoryCompactionService service =
        new MemoryCompactionService(llmRouter, modelResolver, ledgerRepo, snapshotRepo);

    assertThat(service.compactNow("project-1", "org-1")).isEqualTo(1);
    verify(llmRouter)
        .callText(
            eq("openai"),
            eq("gpt-5.4-mini"),
            anyList(),
            eq("compact-project-1"),
            eq("memory-compaction"),
            eq("compact"));
  }

  private static ChatResponse ok(String text) {
    return new ChatResponse(
        List.of(new Generation(AssistantMessage.builder().content(text).build())));
  }
}
