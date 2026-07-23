package com.navneet.executespec.integration.servicenow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.navneet.executespec.integration.IntegrationProperties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/** RR-647 — incident-controlled KB article text must not be injected into the ServiceNow query. */
class ServiceNowClientKbInjectionTest {

  private IntegrationProperties.ServiceNowConfig config() {
    IntegrationProperties.ServiceNowConfig c = new IntegrationProperties.ServiceNowConfig();
    c.setBaseUrl("https://example.service-now.com");
    c.setUsername("u");
    c.setPassword("p");
    return c;
  }

  @Test
  void rejectsNonKbNumberWithoutCallingProvider() {
    RestTemplate rt = mock(RestTemplate.class);
    ServiceNowClient client = new ServiceNowClient(rt, config());

    assertThat(client.fetchKbArticle("KB1^ORnumberISNOTEMPTY")).isEmpty();
    assertThat(client.fetchKbArticle("../secret")).isEmpty();
    assertThat(client.fetchKbArticle(null)).isEmpty();

    verify(rt, never()).exchange(any(String.class), any(), any(), eq(Map.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void validKbNumberBuildsEncodedQueryAndVerifiesReturnedNumber() {
    RestTemplate rt = mock(RestTemplate.class);
    ResponseEntity<Map> resp =
        ResponseEntity.ok(
            Map.of("result", List.of(Map.of("number", "KB0010001", "text", "runbook body"))));
    when(rt.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(Map.class))).thenReturn(resp);

    ServiceNowClient client = new ServiceNowClient(rt, config());
    String out = client.fetchKbArticle("KB0010001");
    assertThat(out).isEqualTo("runbook body");

    ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
    verify(rt).exchange(url.capture(), eq(HttpMethod.GET), any(), eq(Map.class));
    // the value must be percent-encoded exactly once (space/operators cannot appear raw)
    assertThat(url.getValue()).contains("sysparm_query=number%3DKB0010001");
  }

  @Test
  @SuppressWarnings("unchecked")
  void discardsWhenReturnedNumberDoesNotMatch() {
    RestTemplate rt = mock(RestTemplate.class);
    ResponseEntity<Map> resp =
        ResponseEntity.ok(
            Map.of("result", List.of(Map.of("number", "KB9999999", "text", "different row"))));
    when(rt.exchange(any(String.class), eq(HttpMethod.GET), any(), eq(Map.class))).thenReturn(resp);

    ServiceNowClient client = new ServiceNowClient(rt, config());
    assertThat(client.fetchKbArticle("KB0010001")).isEmpty();
  }
}
