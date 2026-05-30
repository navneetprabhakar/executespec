package com.navneet.executespec.auth.service;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.auth.entity.PaymentAudit;
import com.navneet.executespec.auth.repository.PaymentAuditRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentAuditService {

  private final PaymentAuditRepository repo;

  /** Legacy record method (backward compat). */
  public void record(
      String entityType,
      String entityId,
      String eventType,
      String oldTier,
      String newTier,
      String performedBy,
      String notes) {
    record(
        entityType,
        entityId,
        eventType,
        oldTier,
        newTier,
        performedBy,
        notes,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** Full audit record with gateway and payment details. */
  public void record(
      String entityType,
      String entityId,
      String eventType,
      String oldTier,
      String newTier,
      String performedBy,
      String notes,
      String gatewayProvider,
      String gatewayEventId,
      String gatewayPaymentId,
      String currency,
      Long amountMinor,
      String userId,
      String orgId) {
    String idempotencyKey =
        gatewayProvider != null && gatewayEventId != null
            ? gatewayProvider + ":" + gatewayEventId
            : null;

    PaymentAudit entry =
        PaymentAudit.builder()
            .id(UUID.randomUUID().toString())
            .entityType(entityType)
            .entityId(entityId)
            .eventType(eventType)
            .oldTier(oldTier)
            .newTier(newTier)
            .performedBy(performedBy)
            .notes(notes)
            .gatewayProvider(gatewayProvider)
            .gatewayEventId(gatewayEventId)
            .gatewayPaymentId(gatewayPaymentId)
            .currency(currency)
            .amountMinor(amountMinor)
            .idempotencyKey(idempotencyKey)
            .userId(userId)
            .orgId(orgId)
            .build();
    repo.save(entry);
    log.info(
        "Payment audit: {} {} {} → {} gateway={} (by {})",
        entityType,
        entityId,
        eventType,
        newTier,
        gatewayProvider,
        performedBy);
  }

  public Page<PaymentAudit> findAll(Pageable pageable) {
    return repo.findAllByOrderByCreatedAtDesc(pageable);
  }

  public Page<PaymentAudit> findByOrg(String orgId, Pageable pageable) {
    return repo.findByOrgIdOrderByCreatedAtDesc(orgId, pageable);
  }

  public Page<PaymentAudit> findByUser(String userId, Pageable pageable) {
    return repo.findByUserIdOrderByCreatedAtDesc(userId, pageable);
  }

  public List<PaymentAudit> findByEntity(String entityType, String entityId) {
    return repo.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(entityType, entityId);
  }

  public String exportCsv(List<PaymentAudit> records) {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "id,entity_type,entity_id,event_type,old_tier,new_tier,gateway,currency,amount_minor,performed_by,notes,created_at\n");
    for (PaymentAudit r : records) {
      sb.append(csvField(r.getId()))
          .append(',')
          .append(csvField(r.getEntityType()))
          .append(',')
          .append(csvField(r.getEntityId()))
          .append(',')
          .append(csvField(r.getEventType()))
          .append(',')
          .append(csvField(r.getOldTier()))
          .append(',')
          .append(csvField(r.getNewTier()))
          .append(',')
          .append(csvField(r.getGatewayProvider()))
          .append(',')
          .append(csvField(r.getCurrency()))
          .append(',')
          .append(r.getAmountMinor() != null ? r.getAmountMinor() : "")
          .append(',')
          .append(csvField(r.getPerformedBy()))
          .append(',')
          .append(csvField(r.getNotes()))
          .append(',')
          .append(r.getCreatedAt())
          .append('\n');
    }
    return sb.toString();
  }

  private String csvField(String val) {
    if (val == null) return "";
    if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
      return "\"" + val.replace("\"", "\"\"") + "\"";
    }
    return val;
  }
}
