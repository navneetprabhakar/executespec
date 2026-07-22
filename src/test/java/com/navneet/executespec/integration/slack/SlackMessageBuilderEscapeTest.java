package com.navneet.executespec.integration.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.integration.slack.model.SlackEventType;
import com.navneet.executespec.state.entity.PipelineRun;

import org.junit.jupiter.api.Test;

/** RR-583 — untrusted run text must not become active Slack mrkdwn (links / broadcast mentions). */
class SlackMessageBuilderEscapeTest {

  private final SlackMessageBuilder builder = new SlackMessageBuilder("https://app.executespec.ai");

  @SuppressWarnings("unchecked")
  private static String flatten(Map<String, Object> message) {
    // Serialize the whole block structure to a string so we can assert on the rendered mrkdwn text.
    return String.valueOf(message);
  }

  @Test
  void specTextWithBroadcastMentionIsNeutralized() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-12345678");
    run.setUserRequest("Please <!channel> ping everyone and <http://evil.tld|click>");
    Map<String, Object> msg = builder.build(SlackEventType.RUN_STARTED, run, null);
    String rendered = flatten(msg);
    // the raw broadcast/link syntax must be escaped, not passed through verbatim
    assertThat(rendered).doesNotContain("<!channel>");
    assertThat(rendered).doesNotContain("<http://evil.tld|click>");
    assertThat(rendered).contains("&lt;!channel&gt;");
  }

  @Test
  void failureReasonIsEscaped() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-abcdef01");
    run.setFailureReason("boom <!here> & <https://x|y>");
    Map<String, Object> msg = builder.build(SlackEventType.RUN_FAILED, run, null);
    String rendered = flatten(msg);
    assertThat(rendered).doesNotContain("<!here>");
    assertThat(rendered).contains("&lt;!here&gt;");
  }

  @Test
  void serverOwnedRunLinkStaysActive() {
    // The run link is built from typed fields and must remain a working Slack link.
    PipelineRun run = new PipelineRun();
    run.setRunId("run-abcdef01");
    Map<String, Object> msg = builder.build(SlackEventType.RUN_COMPLETE, run, null);
    String rendered = flatten(msg);
    assertThat(rendered).contains("<https://app.executespec.ai/runs/run-abcdef01|");
  }

  @Test
  void reviewSummaryPayloadIsEscaped() {
    PipelineRun run = new PipelineRun();
    run.setRunId("run-abcdef01");
    Map<String, Object> msg =
        builder.build(SlackEventType.REVIEW_COMPLETE, run, "APPROVED|see <!everyone>");
    assertThat(List.of(flatten(msg)).toString()).contains("&lt;!everyone&gt;");
  }
}
