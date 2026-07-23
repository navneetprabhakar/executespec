package com.navneet.executespec.project.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navneet.executespec.prreview.inbox.PrCommentInboxService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

class GitHubWebhookControllerBodyLimitTest {

  private static final String SECRET = "shhh";

  private final PullRequestRecordService prService = mock(PullRequestRecordService.class);
  private final CiFailureAutoFixService ciAutoFix = mock(CiFailureAutoFixService.class);
  private final PrCommentInboxService inbox = mock(PrCommentInboxService.class);
  private GitHubWebhookController controller;

  @BeforeEach
  void setUp() {
    controller = new GitHubWebhookController(prService, ciAutoFix, new ObjectMapper());
    ReflectionTestUtils.setField(controller, "webhookSecret", SECRET);
    ReflectionTestUtils.setField(controller, "maxBodyBytes", 1024);
    ReflectionTestUtils.setField(controller, "prCommentInboxService", inbox);
  }

  private static String sign(byte[] body) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] hash = mac.doFinal(body);
      StringBuilder hex = new StringBuilder();
      for (byte b : hash) hex.append(String.format("%02x", b));
      return "sha256=" + hex;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void rejectsOversizedBodyBeforeHmacOrParse() { // RR-193
    byte[] big = new byte[2048]; // > maxBodyBytes (1024)
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setContent(big);

    ResponseEntity<Void> response = controller.receive(sign(big), "pull_request", req);

    assertThat(response.getStatusCode().value()).isEqualTo(413);
  }

  @Test
  void ingestFailureIsNotAcknowledgedAsSuccess() { // RR-194
    String json =
        "{\"action\":\"created\",\"repository\":{\"full_name\":\"acme/widget\"},"
            + "\"pull_request\":{\"number\":7},"
            + "\"comment\":{\"id\":99,\"body\":\"please fix\",\"path\":\"a.txt\",\"line\":3}}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setContent(body);
    when(inbox.ingest(org.mockito.ArgumentMatchers.any()))
        .thenThrow(new RuntimeException("db down"));

    ResponseEntity<Void> response =
        controller.receive(sign(body), "pull_request_review_comment", req);

    // A transient ingest failure must surface as 500 so GitHub redelivers — never a 204 "success".
    assertThat(response.getStatusCode().value()).isEqualTo(500);
  }

  @Test
  void acceptsAWellFormedSignedEventUnderTheLimit() {
    String json = "{\"action\":\"opened\",\"repository\":{\"full_name\":\"acme/widget\"}}";
    byte[] body = json.getBytes(StandardCharsets.UTF_8);
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setContent(body);

    ResponseEntity<Void> response = controller.receive(sign(body), "pull_request", req);

    assertThat(response.getStatusCode().value()).isEqualTo(204);
  }
}
