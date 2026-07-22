package com.navneet.executespec.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import com.navneet.executespec.state.entity.PromptAuditEntry;
import com.navneet.executespec.state.repository.PromptAuditRepository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** RR-1304 — prompt rollback returns a typed outcome; a no-op / failure is not a false success. */
@ExtendWith(MockitoExtension.class)
class PromptVersioningServiceRollbackTest {

  @Mock private PromptAuditRepository auditRepository;

  private PromptVersioningService service() {
    return new PromptVersioningService(auditRepository, new SimpleMeterRegistry());
  }

  // RR-1304 — no audit history means NO_HISTORY (endpoint maps to 404), not a claimed rollback.
  @Test
  void noAuditHistory_returnsNoHistory() {
    when(auditRepository.findTopByPromptFileOrderByVersionDesc("missing.txt"))
        .thenReturn(Optional.empty());
    assertThat(service().rollback("missing.txt"))
        .isEqualTo(PromptVersioningService.RollbackOutcome.NO_HISTORY);
  }

  // RR-1304 — a real restore atomically writes the previous content and proves it by re-read+hash.
  @Test
  void restore_atomicallyWritesAndVerifiesContent() throws Exception {
    String fileName = "rr1304-rollback-" + java.util.UUID.randomUUID() + ".txt";
    Path promptDir = Path.of("src/main/resources/prompts");
    Files.createDirectories(promptDir);
    Path livePrompt = promptDir.resolve(fileName);
    Files.writeString(livePrompt, "OPTIMISED CONTENT THAT REGRESSED", StandardCharsets.UTF_8);
    try {
      PromptAuditEntry entry =
          PromptAuditEntry.builder()
              .promptFile(fileName)
              .version(3)
              .previousContent("KNOWN GOOD PRIOR CONTENT\n")
              .build();
      when(auditRepository.findTopByPromptFileOrderByVersionDesc(fileName))
          .thenReturn(Optional.of(entry));

      assertThat(service().rollback(fileName))
          .isEqualTo(PromptVersioningService.RollbackOutcome.ROLLED_BACK);
      assertThat(Files.readString(livePrompt, StandardCharsets.UTF_8))
          .isEqualTo("KNOWN GOOD PRIOR CONTENT\n");
    } finally {
      Files.deleteIfExists(livePrompt);
    }
  }
}
