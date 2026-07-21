package com.navneet.executespec.billing.config;

import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

import com.navneet.executespec.billing.exception.RazorpayApiException;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Configuration
@ConfigurationProperties(prefix = "razorpay")
@Getter
@Setter
@Slf4j
public class RazorpayConfig {

  private String keyId;
  private String keySecret;
  private String webhookSecret;
  private String baseUrl = "https://api.razorpay.com/v1";

  /**
   * Public-facing URL where Razorpay sends webhook events (e.g.
   * https://executespec.ai/api/webhooks/razorpay)
   */
  private String webhookUrl;

  /**
   * RR-191 — deadlines so a stalled Razorpay endpoint cannot pin a billing thread indefinitely, and
   * a byte ceiling on error bodies so a large/hostile response cannot exhaust heap. The provider
   * error body can carry customer/payment PII, so it is never logged verbatim (only a bounded,
   * value-free summary) and only a bounded slice is carried on the thrown exception.
   */
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

  private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

  static final int MAX_ERROR_BODY_BYTES = 8 * 1024; // 8 KiB

  @Bean("razorpayHttpClient")
  public RestClient razorpayHttpClient() {
    HttpClient jdkClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(jdkClient);
    factory.setReadTimeout(READ_TIMEOUT);

    return RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(factory)
        .defaultHeaders(
            headers -> {
              String creds =
                  Base64.getEncoder()
                      .encodeToString((keyId + ":" + keySecret).getBytes(StandardCharsets.UTF_8));
              headers.set("Authorization", "Basic " + creds);
              headers.setContentType(MediaType.APPLICATION_JSON);
            })
        .defaultStatusHandler(
            status -> status.isError(),
            (req, resp) -> {
              String body = readBounded(resp.getBody(), MAX_ERROR_BODY_BYTES);
              // RR-191: log status + size only — never the raw provider body (PII/token risk).
              log.error(
                  "Razorpay API error: status={} bodyBytes={}",
                  resp.getStatusCode(),
                  body.length());
              throw new RazorpayApiException(resp.getStatusCode().value(), body);
            })
        .build();
  }

  /**
   * Reads at most {@code limit} bytes from the provider error stream (RR-191). Anything beyond the
   * limit is dropped rather than buffered, and a truncation marker is appended so callers know the
   * body was cut.
   */
  static String readBounded(InputStream in, int limit) throws java.io.IOException {
    byte[] buf = new byte[limit];
    int total = 0;
    int n;
    while (total < limit && (n = in.read(buf, total, limit - total)) != -1) {
      total += n;
    }
    boolean truncated = total == limit && in.read() != -1;
    String body = new String(buf, 0, total, StandardCharsets.UTF_8);
    return truncated ? body + "…(truncated)" : body;
  }
}
