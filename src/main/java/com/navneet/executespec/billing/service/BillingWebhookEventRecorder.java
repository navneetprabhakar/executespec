package com.navneet.executespec.billing.service;

import java.time.Instant;
import java.util.Optional;

import com.navneet.executespec.billing.entity.BillingWebhookEvent;
import com.navneet.executespec.billing.repository.BillingWebhookEventRepository;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class BillingWebhookEventRecorder {

  private final BillingWebhookEventRepository webhookRepo;

  public Optional<BillingWebhookEvent> recordStart(
      String gateway, String eventId, String eventType, String rawPayload) {
    if (webhookRepo.existsByGatewayAndEventId(gateway, eventId)) {
      log.debug("Duplicate {} webhook skipped: {}", gateway, eventId);
      return Optional.empty();
    }
    BillingWebhookEvent event =
        BillingWebhookEvent.builder()
            .gateway(gateway)
            .eventId(eventId)
            .eventType(eventType)
            .rawPayload(rawPayload)
            .processed(false)
            .build();
    webhookRepo.save(event);
    return Optional.of(event);
  }

  public void markProcessed(BillingWebhookEvent event) {
    event.setProcessed(true);
    event.setProcessedAt(Instant.now());
    webhookRepo.save(event);
  }
}
