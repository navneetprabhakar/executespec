package com.navneet.executespec.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.integration.bitbucket.BitbucketAdapter;
import com.navneet.executespec.integration.bitbucket.BitbucketClient;
import com.navneet.executespec.integration.pagerduty.PagerDutyAdapter;
import com.navneet.executespec.integration.pagerduty.PagerDutyClient;
import com.navneet.executespec.integration.servicenow.ServiceNowAdapter;
import com.navneet.executespec.integration.servicenow.ServiceNowClient;

import org.junit.jupiter.api.Test;

/**
 * RR-637 — every inbound integration adapter must fail CLOSED when no webhook secret is configured.
 * "No secret" means "reject every request", never "skip verification".
 */
class InboundWebhookSignatureTest {

  private static final String BODY = "{\"pullrequest\":{\"id\":7}}";
  private static final String SECRET = "s3cr3t-webhook-key";

  private final ObjectMapper mapper = new ObjectMapper();

  private static String hmacHex(String secret, String body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private BitbucketAdapter bitbucket(String secret) {
    IntegrationProperties.BitbucketConfig config = new IntegrationProperties.BitbucketConfig();
    config.setWebhookSecret(secret);
    return new BitbucketAdapter(mock(BitbucketClient.class), config, mapper);
  }

  private ServiceNowAdapter serviceNow(String secret) {
    IntegrationProperties.ServiceNowConfig config = new IntegrationProperties.ServiceNowConfig();
    config.setWebhookSecret(secret);
    return new ServiceNowAdapter(mock(ServiceNowClient.class), config, mapper);
  }

  private PagerDutyAdapter pagerDuty(String secret) {
    IntegrationProperties.PagerDutyConfig config = new IntegrationProperties.PagerDutyConfig();
    config.setWebhookSecret(secret);
    return new PagerDutyAdapter(mock(PagerDutyClient.class), config, mapper);
  }

  // ── unsigned traffic with NO secret configured must be rejected ───────────────

  @Test
  void bitbucketRejectsUnsignedRequestWhenNoSecretConfigured() {
    assertThatThrownBy(() -> bitbucket(null).verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(() -> bitbucket("  ").verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void serviceNowRejectsUnsignedRequestWhenNoSecretConfigured() {
    // The pre-fix bug: omitting the header AND leaving the secret unset returned successfully.
    assertThatThrownBy(() -> serviceNow(null).verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(() -> serviceNow("").verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void pagerDutyRejectsUnsignedRequestWhenNoSecretConfigured() {
    assertThatThrownBy(() -> pagerDuty(null).verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
  }

  /** A configured-secret deployment must not be rescued by simply dropping the header either. */
  @Test
  void configuredSecretStillRequiresASignatureHeader() {
    assertThatThrownBy(() -> bitbucket(SECRET).verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(() -> serviceNow(SECRET).verifySignature(Map.of(), BODY))
        .isInstanceOf(WebhookSignatureException.class);
  }

  // ── wrong digest rejected, right digest accepted ─────────────────────────────

  @Test
  void wrongDigestIsRejected() {
    String wrong = hmacHex("other-key", BODY);
    assertThatThrownBy(
            () ->
                bitbucket(SECRET)
                    .verifySignature(Map.of("x-hub-signature", "sha256=" + wrong), BODY))
        .isInstanceOf(WebhookSignatureException.class);
    assertThatThrownBy(
            () ->
                serviceNow(SECRET)
                    .verifySignature(Map.of("x-servicenow-signature", "sha256=" + wrong), BODY))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void validDigestIsAccepted() {
    String good = hmacHex(SECRET, BODY);
    assertThatCode(
            () ->
                bitbucket(SECRET)
                    .verifySignature(Map.of("x-hub-signature", "sha256=" + good), BODY))
        .doesNotThrowAnyException();
    assertThatCode(
            () ->
                serviceNow(SECRET)
                    .verifySignature(Map.of("x-servicenow-signature", "sha256=" + good), BODY))
        .doesNotThrowAnyException();
    // bare-hex form is also supported
    assertThatCode(() -> bitbucket(SECRET).verifySignature(Map.of("x-hub-signature", good), BODY))
        .doesNotThrowAnyException();
  }

  @Test
  void malformedSchemeIsRejected() {
    String good = hmacHex(SECRET, BODY);
    assertThatThrownBy(
            () ->
                bitbucket(SECRET).verifySignature(Map.of("x-hub-signature", "sha1=" + good), BODY))
        .isInstanceOf(WebhookSignatureException.class);
  }

  @Test
  void rejectionMessagePointsAtTheConfigurationKey() {
    assertThatThrownBy(() -> bitbucket(null).verifySignature(Map.of(), BODY))
        .hasMessageContaining("agent.integrations.bitbucket.webhook-secret");
    assertThatThrownBy(() -> serviceNow(null).verifySignature(Map.of(), BODY))
        .hasMessageContaining("agent.integrations.servicenow.webhook-secret");
  }

  @Test
  void defaultConfigurationHasNoSecretSoInboundIsClosedByDefault() {
    assertThat(new IntegrationProperties.BitbucketConfig().getWebhookSecret()).isNull();
    assertThat(new IntegrationProperties.ServiceNowConfig().getWebhookSecret()).isNull();
  }
}
