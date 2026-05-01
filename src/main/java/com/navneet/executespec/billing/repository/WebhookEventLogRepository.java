package com.navneet.executespec.billing.repository;

import java.util.Optional;

import com.navneet.executespec.billing.entity.WebhookEventLog;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventLogRepository extends JpaRepository<WebhookEventLog, Long> {

  Optional<WebhookEventLog> findByGatewayAndEventId(String gateway, String eventId);

  Page<WebhookEventLog> findByGatewayOrderByCreatedAtDesc(String gateway, Pageable pageable);

  Page<WebhookEventLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
