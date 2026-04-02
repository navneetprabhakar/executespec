package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.PaymentAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, String>,
        JpaSpecificationExecutor<PaymentAudit> {

    List<PaymentAudit> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    Page<PaymentAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PaymentAudit> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    Page<PaymentAudit> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);
}
