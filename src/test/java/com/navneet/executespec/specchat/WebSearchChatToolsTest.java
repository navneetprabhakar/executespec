package com.navneet.executespec.specchat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

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

  private static WebSearchChatTools build(WebSearchChatTools.GuardedFetcher fetcher) {
    ContextSanitizer san = mock(ContextSanitizer.class);
    when(san.sanitizeInput(any(), any())).thenAnswer(i -> i.getArgument(0));
    return new WebSearchChatTools(new ObjectMapper(), san, fetcher);
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
  void webFetchRejectsCredentialBearingUrlsBeforeNetwork() {
    assertThat(
            call(
                "web_fetch",
                "{\"url\":\"https://example.com/?access_token=sk-abcdefghijklmnopqrstuvwxyz\"}"))
        .contains("\"ok\":false")
        .contains("credentials");
  }

  @Test
  void webFetchRejectsLocalDataExfiltrationQueryKeysBeforeNetwork() {
    assertThat(call("web_fetch", "{\"url\":\"https://example.com/?data=approved-local-context\"}"))
        .contains("\"ok\":false")
        .contains("exfiltrate");
    assertThat(call("web_fetch", "{\"url\":\"https://example.com/?payload=run-summary\"}"))
        .contains("\"ok\":false")
        .contains("exfiltrate");
  }

  @Test
  void webFetchRejectsVeryLongQueryStringsBeforeNetwork() {
    String longQuery = "a".repeat(300);
    assertThat(call("web_fetch", "{\"url\":\"https://example.com/?" + longQuery + "\"}"))
        .contains("\"ok\":false")
        .contains("query string is too long");
  }

  @Test
  void webFetchRejectsNonDefaultPortsBeforeNetwork() {
    AtomicReference<URI> called = new AtomicReference<>();
    WebSearchChatTools guarded =
        build(
            (uri, host, address) -> {
              called.set(uri);
              return WebSearchChatTools.FetchOutcome.ok("should not run");
            });
    ToolCallback cb =
        guarded.bind().stream()
            .filter(t -> t.getToolDefinition().name().equals("web_fetch"))
            .findFirst()
            .orElseThrow();

    assertThat(cb.call("{\"url\":\"https://93.184.216.34:8443/\"}"))
        .contains("\"ok\":false")
        .contains("default http/https ports");
    assertThat(called).hasValue(null);
  }

  @Test
  void webFetchPassesValidatedPublicAddressToPinnedFetcher() {
    AtomicReference<InetAddress> capturedAddress = new AtomicReference<>();
    WebSearchChatTools guarded =
        build(
            (uri, host, address) -> {
              capturedAddress.set(address);
              assertThat(uri.toString()).isEqualTo("http://93.184.216.34/docs");
              assertThat(host).isEqualTo("93.184.216.34");
              return WebSearchChatTools.FetchOutcome.ok("public docs");
            });
    ToolCallback cb =
        guarded.bind().stream()
            .filter(t -> t.getToolDefinition().name().equals("web_fetch"))
            .findFirst()
            .orElseThrow();

    assertThat(cb.call("{\"url\":\"http://93.184.216.34/docs\"}"))
        .contains("\"ok\":true")
        .contains("public docs");
    assertThat(capturedAddress.get()).isNotNull();
    assertThat(capturedAddress.get().isLoopbackAddress()).isFalse();
  }

  @Test
  void contentMetadataGuardsRejectLargeAndNonTextResponses() {
    assertThat(WebSearchChatTools.isContentLengthTooLarge("64001")).isTrue();
    assertThat(WebSearchChatTools.isContentLengthTooLarge("64000")).isFalse();
    assertThat(WebSearchChatTools.isContentLengthTooLarge("not-a-number")).isTrue();
    assertThat(WebSearchChatTools.isAllowedContentType("text/html; charset=utf-8")).isTrue();
    assertThat(WebSearchChatTools.isAllowedContentType("application/json")).isTrue();
    assertThat(WebSearchChatTools.isAllowedContentType("application/problem+json")).isTrue();
    assertThat(WebSearchChatTools.isAllowedContentType("image/png")).isFalse();
    assertThat(WebSearchChatTools.isAllowedContentType("application/octet-stream")).isFalse();
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
