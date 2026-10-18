package com.navneet.executespec.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Workspace routing for the configured platform credential; never attaches it to unrelated BYOK.
 */
@Component
public final class AnthropicWorkspaceHeaders {
  private final String platformApiKey;
  private final String workspaceId;

  public AnthropicWorkspaceHeaders(
      @Value("${spring.ai.anthropic.api-key:}") String platformApiKey,
      @Value("${spring.ai.anthropic.workspace-id:}") String workspaceId) {
    this.platformApiKey = platformApiKey;
    this.workspaceId = workspaceId == null ? "" : workspaceId.trim();
  }

  RestClient.Builder apply(RestClient.Builder client, String selectedKey) {
    if (!workspaceId.isBlank()
        && platformApiKey != null
        && !platformApiKey.isBlank()
        && platformApiKey.equals(selectedKey)) {
      client.defaultHeader("anthropic-workspace-id", workspaceId);
    }
    return client;
  }
}
