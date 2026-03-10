package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.PaymentAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, String> {

    List<PaymentAudit> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);
}
