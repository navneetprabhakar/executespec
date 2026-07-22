package com.navneet.executespec.integration.bitbucket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.IntegrationProperties;

import org.junit.jupiter.api.Test;

/** RR-638 — Bitbucket event-type allowlist + stable per-delivery idempotency id. */
class BitbucketAdapterEventTest {

  private static final String BODY =
      "{\"pullrequest\":{\"id\":7,\"title\":\"add feature\",\"description\":\"do"
          + " it\",\"links\":{\"html\":{\"href\":\"https://bitbucket.org/w/r/pull-requests/7\"}}}}";

  private final ObjectMapper mapper = new ObjectMapper();

  private BitbucketAdapter adapter() {
    return new BitbucketAdapter(
        mock(BitbucketClient.class), new IntegrationProperties.BitbucketConfig(), mapper);
  }

  @Test
  void nonTriggerEventKeyIsIgnored() {
    // an update / approval / merge webhook must NOT trigger a run
    assertThat(adapter().parseEvent(Map.of("x-event-key", "pullrequest:updated"), BODY)).isEmpty();
    assertThat(adapter().parseEvent(Map.of("x-event-key", "pullrequest:approved"), BODY)).isEmpty();
    assertThat(adapter().parseEvent(Map.of("x-event-key", "pullrequest:fulfilled"), BODY))
        .isEmpty();
  }

  @Test
  void createdEventKeyTriggers() {
    assertThat(adapter().parseEvent(Map.of("x-event-key", "pullrequest:created"), BODY))
        .isPresent();
  }

  @Test
  void deliveryUuidIsUsedAsIdempotencyIdSoLaterEventsAreNotCollapsed() {
    String id1 = adapter().extractEventId(Map.of("x-request-uuid", "uuid-aaa"), BODY);
    String id2 = adapter().extractEventId(Map.of("x-request-uuid", "uuid-bbb"), BODY);
    assertThat(id1).isEqualTo("bb-uuid-aaa");
    // distinct deliveries for the SAME PR id now get distinct event ids (old scheme collapsed both)
    assertThat(id2).isNotEqualTo(id1);
  }

  @Test
  void absentHeaderFallsBackToPrIdQualifiedByEventKey() {
    String id = adapter().extractEventId(Map.of("x-event-key", "pullrequest:created"), BODY);
    assertThat(id).isEqualTo("bb-pr-7-pullrequest:created");
  }
}
