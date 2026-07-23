package com.navneet.executespec.integration.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.IntegrationProperties;

import org.junit.jupiter.api.Test;

class ConfluenceAdapterEventIdTest {

  private final ConfluenceAdapter adapter =
      new ConfluenceAdapter(
          mock(ConfluenceClient.class),
          new IntegrationProperties.ConfluenceConfig(),
          new ObjectMapper());

  @Test
  void successiveVersionsOfOnePageProduceDistinctEventIds() {
    // RR-581 — `<event>-<pageId>` alone made every update after the first look like a replay. The
    // id
    // now folds in the page version so two edits of the same page are two distinct events.
    String v1 =
        adapter.extractEventId(
            "{\"event\":\"page_updated\",\"page\":{\"id\":\"123\",\"version\":{\"number\":1}}}");
    String v2 =
        adapter.extractEventId(
            "{\"event\":\"page_updated\",\"page\":{\"id\":\"123\",\"version\":{\"number\":2}}}");
    assertThat(v1).isEqualTo("page_updated-123-v1");
    assertThat(v2).isEqualTo("page_updated-123-v2");
    assertThat(v1).isNotEqualTo(v2);
  }

  @Test
  void timestampDisambiguatesWhenVersionAbsent() {
    // RR-581 — Confluence Cloud also carries a top-level delivery timestamp; use it when no version
    // number is present so successive edits still differ.
    String t1 =
        adapter.extractEventId(
            "{\"event\":\"page_updated\",\"timestamp\":1000,\"page\":{\"id\":\"9\"}}");
    String t2 =
        adapter.extractEventId(
            "{\"event\":\"page_updated\",\"timestamp\":2000,\"page\":{\"id\":\"9\"}}");
    assertThat(t1).isEqualTo("page_updated-9-t1000");
    assertThat(t2).isEqualTo("page_updated-9-t2000");
  }

  @Test
  void noDiscriminatorFallsBackToAcceptOnceNotAStableDuplicateKey() {
    // RR-581 — with neither version nor timestamp we cannot prove a duplicate, so accept it (unique
    // id) rather than emit a stable key that would false-dedupe every future update.
    String a = adapter.extractEventId("{\"event\":\"page_updated\",\"page\":{\"id\":\"5\"}}");
    String b = adapter.extractEventId("{\"event\":\"page_updated\",\"page\":{\"id\":\"5\"}}");
    assertThat(a).startsWith("page_updated-5-");
    assertThat(a).isNotEqualTo(b);
  }
}
