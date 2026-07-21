package com.navneet.executespec.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import com.navneet.executespec.agent.PromptAssembler.AssembledPrompt;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PromptAssemblerTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  @Test
  void tracksPromptWhenAuditPersistenceSucceeds() {
    PromptHashAuditRepository repo = mock(PromptHashAuditRepository.class);
    PromptAssembler assembler = new PromptAssembler(loaderReturning("hello"), repo, meterRegistry);

    AssembledPrompt result =
        assembler.assembleAndTrack("some-prompt.md", Map.of(), UUID.randomUUID(), "WorkerAgent");

    assertThat(result.text()).isEqualTo("hello");
    assertThat(result.tracked()).isTrue();
    assertThat(meterRegistry.find("executespec.prompt.audit.failures").counter()).isNull();
  }

  @Test
  void marksPromptDegradedAndMetersWhenAuditPersistenceFails() {
    PromptHashAuditRepository repo = mock(PromptHashAuditRepository.class);
    when(repo.save(any())).thenThrow(new RuntimeException("db down"));
    PromptAssembler assembler = new PromptAssembler(loaderReturning("hello"), repo, meterRegistry);

    AssembledPrompt result =
        assembler.assembleAndTrack("some-prompt.md", Map.of(), UUID.randomUUID(), "WorkerAgent");

    // Execution is not blocked — the prompt text is still returned...
    assertThat(result.text()).isEqualTo("hello");
    // ...but it is DEGRADED so downstream cannot treat its provenance as recorded (RR-521).
    assertThat(result.tracked()).isFalse();
    // ...and the failure is observable, not silently discarded.
    assertThat(
            meterRegistry
                .find("executespec.prompt.audit.failures")
                .tag("agent_type", "WorkerAgent")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  private static ResourceLoader loaderReturning(String content) {
    ResourceLoader loader = mock(ResourceLoader.class);
    Resource resource = mock(Resource.class);
    try {
      when(resource.getContentAsString(StandardCharsets.UTF_8)).thenReturn(content);
      when(resource.getInputStream())
          .thenReturn(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    when(loader.getResource(anyString())).thenReturn(resource);
    return loader;
  }
}
