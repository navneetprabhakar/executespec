package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.security.ContextSanitizer;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

/**
 * Pins the SSRF + argument guards on the agent web-research tools. All cases below are rejected
 * before any network call (bad scheme, blocked host, blocked resolved address, or missing arg), so
 * the test is hermetic.
 */
class WebSearchChatToolsTest {

  private final WebSearchChatTools tools = build();

  private static WebSearchChatTools build() {
    ContextSanitizer san = mock(ContextSanitizer.class);
    when(san.sanitizeInput(any(), any())).thenAnswer(i -> i.getArgument(0));
    return new WebSearchChatTools(new ObjectMapper(), san);
  }

  private String call(String name, String argsJson) {
    ToolCallback cb =
        tools.bind().stream()
            .filter(t -> t.getToolDefinition().name().equals(name))
            .findFirst()
            .orElseThrow();
    return cb.call(argsJson);
  }

  @Test
  void exposesWebSearchAndWebFetch() {
    assertThat(tools.bind())
        .extracting(t -> t.getToolDefinition().name())
        .containsExactlyInAnyOrder("web_search", "web_fetch");
  }

  @Test
  void webFetchRejectsNonHttpScheme() {
    assertThat(call("web_fetch", "{\"url\":\"file:///etc/passwd\"}")).contains("\"ok\":false");
  }

  @Test
  void webFetchBlocksLocalhostHostname() {
    assertThat(call("web_fetch", "{\"url\":\"http://localhost/\"}")).contains("\"ok\":false");
  }

  @Test
  void webFetchBlocksInternalHostname() {
    assertThat(call("web_fetch", "{\"url\":\"http://anything.internal/\"}"))
        .contains("\"ok\":false");
  }

  @Test
  void webFetchBlocksLoopbackIp() {
    assertThat(call("web_fetch", "{\"url\":\"http://127.0.0.1/\"}")).contains("\"ok\":false");
  }

  @Test
  void webFetchBlocksCloudMetadataIp() {
    assertThat(call("web_fetch", "{\"url\":\"http://169.254.169.254/latest/meta-data/\"}"))
        .contains("\"ok\":false");
  }

  @Test
  void webFetchRequiresUrl() {
    assertThat(call("web_fetch", "{}")).contains("\"ok\":false");
  }

  @Test
  void webSearchRequiresQuery() {
    assertThat(call("web_search", "{}")).contains("\"ok\":false");
  }
}
