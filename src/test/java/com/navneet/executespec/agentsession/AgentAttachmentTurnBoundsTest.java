package com.navneet.executespec.agentsession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import com.navneet.executespec.agentsession.AgentAttachmentStore.StoredAttachment;
import com.navneet.executespec.auth.service.ActivityAuditService;
import com.navneet.executespec.project.rag.DocumentExtractor;
import com.navneet.executespec.security.ContextSanitizer;
import com.navneet.executespec.security.InputSanitizer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * RR-490. One turn could previously repeat a single valid attachment id an unbounded number of
 * times: the text path appended every occurrence and the media path base64-encoded every occurrence
 * into a fresh payload map, so a 10 MB image repeated a few thousand times expanded far past the
 * nominal upload cap before any provider call. These pin dedupe plus the per-turn count, char and
 * byte ceilings.
 */
class AgentAttachmentTurnBoundsTest {

  private AgentAttachmentStore store;
  private AgentAttachmentService service;

  @BeforeEach
  void setUp() {
    store = mock(AgentAttachmentStore.class);
    service =
        new AgentAttachmentService(
            store,
            mock(DocumentExtractor.class),
            mock(InputSanitizer.class),
            mock(ContextSanitizer.class),
            mock(ActivityAuditService.class));
  }

  private static StoredAttachment text(String id, String body) {
    return StoredAttachment.of(id, "s1", id + ".txt", "text/plain", body.length(), body);
  }

  private static StoredAttachment image(String id, int bytes) {
    return StoredAttachment.image(id, "s1", id + ".png", "image/png", bytes, new byte[bytes]);
  }

  // ------------------------------------------------------------------------ dedupe

  @Test
  void repeatedIdIsExpandedOnlyOnceInTheTextBlock() {
    when(store.get("s1", "a")).thenReturn(Optional.of(text("a", "hello world")));

    String block = service.injectionBlock("s1", List.of("a", "a", "a", "a"));

    assertThat(block).contains("The user attached 1 file(s).");
    assertThat(block.split("BEGIN UPLOADED FILE", -1)).hasSize(2); // exactly one occurrence
  }

  @Test
  void repeatedIdIsLoadedOnlyOnceForMedia() {
    when(store.get("s1", "img")).thenReturn(Optional.of(image("img", 1024)));

    List<StoredAttachment> media =
        service.mediaAttachments("s1", List.of("img", "img", "img", "img", "img"));

    assertThat(media).hasSize(1);
    verify(store, times(1)).get("s1", "img");
  }

  // ------------------------------------------------------------------- count ceiling

  @Test
  void rejectsATurnAskingForMoreThanTheDistinctAttachmentCap() {
    List<String> tooMany =
        java.util.stream.IntStream.range(0, AgentAttachmentService.MAX_ATTACHMENTS_PER_TURN + 1)
            .mapToObj(i -> "id-" + i)
            .toList();

    assertThatThrownBy(() -> service.injectionBlock("s1", tooMany))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("413");
    assertThatThrownBy(() -> service.mediaAttachments("s1", tooMany))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void anAmplifiedListOfOneRepeatedIdIsNotRejected() {
    // 5,000 repetitions of ONE id collapse to a single distinct attachment, so the honest
    // single-file turn still succeeds instead of erroring on raw list length.
    when(store.get("s1", "a")).thenReturn(Optional.of(text("a", "hello world")));
    List<String> amplified = Collections.nCopies(5_000, "a");

    assertThat(service.injectionBlock("s1", amplified)).contains("attached 1 file(s)");
  }

  // ------------------------------------------------------------ aggregate byte/char caps

  @Test
  void stopsIncludingTextOnceTheTurnCharBudgetIsExhausted() {
    String big = "x".repeat(60_000);
    for (int i = 0; i < 5; i++) {
      when(store.get("s1", "t" + i)).thenReturn(Optional.of(text("t" + i, big)));
    }

    String block = service.injectionBlock("s1", List.of("t0", "t1", "t2", "t3", "t4"));

    // 150k budget / 60k each ⇒ two files fit, the rest are dropped rather than concatenated.
    assertThat(block).contains("The user attached 2 file(s).");
    assertThat(block.length()).isLessThan(AgentAttachmentService.MAX_TURN_TEXT_CHARS + 2_000);
  }

  @Test
  void stopsIncludingMediaOnceTheTurnByteBudgetIsExhausted() {
    int fiveMb = 5 * 1024 * 1024;
    for (int i = 0; i < 5; i++) {
      when(store.get("s1", "m" + i)).thenReturn(Optional.of(image("m" + i, fiveMb)));
    }

    List<StoredAttachment> media =
        service.mediaAttachments("s1", List.of("m0", "m1", "m2", "m3", "m4"));

    // 12 MB budget / 5 MB each ⇒ two admitted; the previously unbounded path would have taken all.
    assertThat(media).hasSize(2);
    assertThat(media.stream().mapToLong(a -> a.rawBytes().length).sum())
        .isLessThanOrEqualTo(AgentAttachmentService.MAX_TURN_MEDIA_BYTES);
  }

  @Test
  void nullAndBlankIdsAreIgnored() {
    when(store.get(anyString(), anyString())).thenReturn(Optional.empty());
    assertThat(AgentAttachmentService.normalizeTurnIds(null)).isEmpty();
    assertThat(AgentAttachmentService.normalizeTurnIds(java.util.Arrays.asList(null, "", "  ")))
        .isEmpty();
  }
}
