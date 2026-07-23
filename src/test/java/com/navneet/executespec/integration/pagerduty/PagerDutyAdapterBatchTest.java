package com.navneet.executespec.integration.pagerduty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.IntegrationEvent;
import com.navneet.executespec.integration.IntegrationProperties;

import org.junit.jupiter.api.Test;

/** RR-639 — a batched PagerDuty delivery must not process only its first message. */
class PagerDutyAdapterBatchTest {

  private final ObjectMapper mapper = new ObjectMapper();

  private PagerDutyAdapter adapter() {
    IntegrationProperties.PagerDutyConfig config = new IntegrationProperties.PagerDutyConfig();
    // default triggerTypes = ["incident.trigger"]
    return new PagerDutyAdapter(mock(PagerDutyClient.class), config, mapper);
  }

  private static String batch(String... typeIdPairs) {
    StringBuilder sb = new StringBuilder("{\"messages\":[");
    for (int i = 0; i < typeIdPairs.length; i += 2) {
      if (i > 0) sb.append(",");
      sb.append(
          String.format(
              "{\"id\":\"%s\",\"type\":\"%s\",\"incident\":{\"id\":\"P1\",\"incident_number\":\"42\",\"title\":\"boom\",\"status\":\"triggered\",\"html_url\":\"https://x/y\"}}",
              typeIdPairs[i + 1], typeIdPairs[i]));
    }
    return sb.append("]}").toString();
  }

  @Test
  void nonTriggerFirstMessageDoesNotBuryATriggerMessageLaterInTheBatch() {
    // messages[0] is an ignored type; messages[1] is the real trigger.
    String body = batch("incident.acknowledge", "m0", "incident.trigger", "m1");
    Optional<IntegrationEvent> event = adapter().parseEvent(body);
    assertThat(event).isPresent();
    // and the idempotency id is the message actually acted on, not messages[0]
    assertThat(adapter().extractEventId(body)).isEqualTo("m1");
  }

  @Test
  void allIgnoredBatchProducesNoEventButStillDedupesOnFirstMessage() {
    String body = batch("incident.acknowledge", "m0", "incident.resolve", "m1");
    assertThat(adapter().parseEvent(body)).isEmpty();
    assertThat(adapter().extractEventId(body)).isEqualTo("m0");
  }

  @Test
  void firstTriggerMessageIsSelected() {
    String body = batch("incident.trigger", "m0", "incident.trigger", "m1");
    assertThat(adapter().extractEventId(body)).isEqualTo("m0");
    assertThat(adapter().parseEvent(body)).isPresent();
  }
}
