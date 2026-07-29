package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.agentsession.entity.AgentTurn;
import com.navneet.executespec.agentsession.repository.AgentActionEventRepository;
import com.navneet.executespec.agentsession.repository.AgentTurnRepository;
import com.navneet.executespec.agentsession.repository.AgentUsageLedgerRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AgentTranscriptDurabilityTest {

  @Mock AgentTurnRepository turnRepository;
  @Mock AgentActionEventRepository eventRepository;
  @Mock AgentUsageLedgerRepository usageRepository;

  @ParameterizedTest
  @ValueSource(ints = {50, 125, 150, 250, 500})
  void completeStreamTurnReadbackPreservesExactCharactersBytesHashAndLines(int lineCount)
      throws Exception {
    String response = responseWithLines(lineCount);
    AgentTurn turn = streamingTurn();
    when(turnRepository.findById("turn-1")).thenReturn(Optional.of(turn));
    when(eventRepository.findByTurnIdOrderByCreatedAtAsc("turn-1")).thenReturn(java.util.List.of());

    service().completeStreamTurn(context(), response, null, "OK");
    var readback = new AgentTurnReadModel(eventRepository, new ObjectMapper()).toResponse(turn);

    byte[] exactBytes = response.getBytes(StandardCharsets.UTF_8);
    String exactHash =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exactBytes));
    assertThat(readback.assistantSummary()).isEqualTo(response);
    assertThat(readback.assistantCharCount()).isEqualTo(response.length());
    assertThat(readback.assistantByteCount()).isEqualTo(exactBytes.length);
    assertThat(readback.assistantLineCount()).isEqualTo(lineCount);
    assertThat(readback.assistantSha256()).isEqualTo(exactHash);
    if (lineCount == 500) {
      assertThat(exactBytes.length).isGreaterThan(60 * 1024);
    }
  }

  @Test
  void responseAbove60KbIsDurableWhileEventPreviewIsExplicitlyBounded() {
    String response = responseWithLines(900);
    assertThat(response.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(60 * 1024);

    var preview = AgentTranscriptContent.eventPreview(response);

    assertThat((String) preview.get("text"))
        .hasSize(AgentTranscriptContent.EVENT_PREVIEW_CHARS)
        .isEqualTo(response.substring(0, AgentTranscriptContent.EVENT_PREVIEW_CHARS));
    assertThat(preview.get("truncated")).isEqualTo(true);
    assertThat(preview.get("originalChars")).isEqualTo(response.length());
    assertThat(preview.get("originalBytes"))
        .isEqualTo(response.getBytes(StandardCharsets.UTF_8).length);
    assertThat(preview.get("originalLines")).isEqualTo(900);
    assertThat(preview.get("sha256")).isEqualTo(AgentTranscriptContent.metadata(response).sha256());
  }

  private AgentSessionService service() {
    AgentStreamTurnLifecycle lifecycle =
        new AgentStreamTurnLifecycle(
            turnRepository,
            new AgentEventRecorder(eventRepository, new ObjectMapper()),
            usageRepository);
    return new AgentSessionService(
        null, null, null, null, null, null, null, null, null, null, lifecycle, null);
  }

  private static String responseWithLines(int lines) {
    return IntStream.range(0, lines)
        .mapToObj(index -> "line-%04d π %s".formatted(index, "x".repeat(140)))
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private static AgentTurn streamingTurn() {
    return AgentTurn.builder()
        .id("turn-1")
        .sessionId("session-1")
        .orgId("org-1")
        .userId("user-1")
        .sequence(1)
        .status("STREAMING")
        .build();
  }

  private static AgentSessionService.StreamTurnContext context() {
    return new AgentSessionService.StreamTurnContext(
        "session-1",
        "turn-1",
        1,
        null,
        null,
        "org-1",
        "user-1",
        "ANTHROPIC",
        "claude-sonnet-4-6",
        null);
  }
}
