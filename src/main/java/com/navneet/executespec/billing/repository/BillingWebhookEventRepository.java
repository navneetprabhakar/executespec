package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.BillingWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface BillingWebhookEventRepository extends JpaRepository<BillingWebhookEvent, UUID> {

    boolean existsByGatewayAndEventId(String gateway, String eventId);
}
