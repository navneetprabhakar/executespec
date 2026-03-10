package com.navneet.executespec.auth.service;

import com.navneet.executespec.auth.entity.PaymentAudit;
import com.navneet.executespec.auth.repository.PaymentAuditRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentAuditService {

    private final PaymentAuditRepository repo;

    public void record(String entityType, String entityId, String eventType,
                       String oldTier, String newTier, String performedBy, String notes) {
        PaymentAudit entry = PaymentAudit.builder()
                .id(UUID.randomUUID().toString())
                .entityType(entityType)
                .entityId(entityId)
                .eventType(eventType)
                .oldTier(oldTier)
                .newTier(newTier)
                .performedBy(performedBy)
                .notes(notes)
                .build();
        repo.save(entry);
        log.info("Payment audit: {} {} {} → {} (by {})", entityType, entityId, eventType, newTier, performedBy);
    }
}
