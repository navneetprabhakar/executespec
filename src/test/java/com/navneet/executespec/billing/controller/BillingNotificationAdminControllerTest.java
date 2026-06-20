package com.navneet.executespec.billing.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;

import com.navneet.executespec.billing.service.BillingNotificationService;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class BillingNotificationAdminControllerTest {

  @Test
  void dispatchesManagedAiEmailSmokeThroughBillingNotificationPath() {
    BillingNotificationService notifications = mock(BillingNotificationService.class);
    BillingNotificationAdminController controller =
        new BillingNotificationAdminController(notifications);

    var response =
        controller.sendManagedAiTestEmail(
            Map.of("to", "operator@example.com", "routingState", "EXHAUSTED"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsEntry("routingState", "EXHAUSTED");
    verify(notifications)
        .sendManagedAiRoutingStateChanged(
            eq("operator@example.com"),
            eq("EXHAUSTED"),
            contains("billing notification Gmail path"));
  }

  @Test
  void rejectsMissingRecipientWithoutDispatch() {
    BillingNotificationService notifications = mock(BillingNotificationService.class);
    BillingNotificationAdminController controller =
        new BillingNotificationAdminController(notifications);

    var response = controller.sendManagedAiTestEmail(Map.of("routingState", "CHEAP_ONLY"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(notifications, never())
        .sendManagedAiRoutingStateChanged(anyString(), anyString(), anyString());
  }

  @Test
  void rejectsUnsupportedRoutingStateWithoutDispatch() {
    BillingNotificationService notifications = mock(BillingNotificationService.class);
    BillingNotificationAdminController controller =
        new BillingNotificationAdminController(notifications);

    var response =
        controller.sendManagedAiTestEmail(
            Map.of("to", "operator@example.com", "routingState", "FULL"));

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).containsKey("error");
    verify(notifications, never())
        .sendManagedAiRoutingStateChanged(anyString(), anyString(), anyString());
  }
}
