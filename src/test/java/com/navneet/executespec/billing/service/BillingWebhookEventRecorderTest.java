package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navneet.executespec.billing.entity.BillingWebhookEvent;
import com.navneet.executespec.billing.repository.BillingWebhookEventRepository;

import org.junit.jupiter.api.Test;

class BillingWebhookEventRecorderTest {

  private final BillingWebhookEventRepository webhookRepo =
      mock(BillingWebhookEventRepository.class);
  private final BillingWebhookEventRecorder recorder = new BillingWebhookEventRecorder(webhookRepo);

  @Test
  void recordStartSkipsDuplicateWebhookEvents() {
    when(webhookRepo.existsByGatewayAndEventId("STRIPE", "evt_1")).thenReturn(true);

    assertThat(recorder.recordStart("STRIPE", "evt_1", "invoice.paid", "{}")).isEmpty();

    verify(webhookRepo, never()).save(any());
  }

  @Test
  void recordStartPersistsUnprocessedWebhookEvent() {
    when(webhookRepo.existsByGatewayAndEventId("RAZORPAY", "payment_1")).thenReturn(false);
    when(webhookRepo.save(any(BillingWebhookEvent.class))).thenAnswer(inv -> inv.getArgument(0));

    var event = recorder.recordStart("RAZORPAY", "payment_1", "payment.captured", "{}");

    assertThat(event).isPresent();
    assertThat(event.get().getGateway()).isEqualTo("RAZORPAY");
    assertThat(event.get().getEventId()).isEqualTo("payment_1");
    assertThat(event.get().getEventType()).isEqualTo("payment.captured");
    assertThat(event.get().getRawPayload()).isEqualTo("{}");
    assertThat(event.get().isProcessed()).isFalse();
  }

  @Test
  void markProcessedPersistsProcessedTimestamp() {
    BillingWebhookEvent event = BillingWebhookEvent.builder().processed(false).build();

    recorder.markProcessed(event);

    assertThat(event.isProcessed()).isTrue();
    assertThat(event.getProcessedAt()).isNotNull();
    verify(webhookRepo).save(event);
  }
}
