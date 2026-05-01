package com.navneet.executespec.integration.slack.model;

/**
 * Controls which pipeline events are forwarded as Slack notifications. Configured per-project
 * channel mapping.
 */
public enum SlackVerbosity {

  /** Run started, completed, failed only. */
  MINIMAL,

  /** + plan/merge approval gates, task completions/failures, review complete, stuck runs. */
  STANDARD,

  /** + every task started, compilation details, model escalations, recovery attempts. */
  VERBOSE;

  public boolean includes(SlackEventType eventType) {
    return eventType.getMinimumVerbosity().ordinal() <= this.ordinal();
  }
}
