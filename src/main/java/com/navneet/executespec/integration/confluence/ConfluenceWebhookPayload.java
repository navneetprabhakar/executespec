package com.navneet.executespec.integration.confluence;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ConfluenceWebhookPayload(
    String event, // "page_created" | "page_updated"
    // RR-581 — Confluence Cloud delivers a top-level epoch-millis `timestamp` and, on the page, a
    // monotonically increasing `version.number`. Either distinguishes successive edits of the same
    // page so idempotency does not collapse every future `page_updated` for one page id into a
    // single event key.
    Long timestamp,
    ConfluencePage page) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConfluencePage(
      String id,
      String title,
      String spaceKey,
      ConfluenceVersion version,
      List<ConfluenceLabel> labels) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConfluenceVersion(Integer number) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ConfluenceLabel(String name, String prefix) {}
}
