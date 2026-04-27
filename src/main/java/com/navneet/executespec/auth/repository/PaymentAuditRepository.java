package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.PaymentAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PaymentAuditRepository extends JpaRepository<PaymentAudit, String>,
        JpaSpecificationExecutor<PaymentAudit> {

    List<PaymentAudit> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    Page<PaymentAudit> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<PaymentAudit> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    Page<PaymentAudit> findByUserIdOrderByCreatedAtDesc(String userId, Pageable pageable);

    /** M-017 (DSAR) — every payment-audit row touching this user OR
     *  their org. orgId may be null (free-tier), in which case the
     *  org branch matches nothing — no false-positives. */
    @Query("SELECT p FROM PaymentAudit p WHERE p.userId = :userId "
            + "OR (:orgId IS NOT NULL AND p.orgId = :orgId) "
            + "ORDER BY p.createdAt DESC")
    List<PaymentAudit> findByUserIdOrOrgId(@Param("userId") String userId,
                                            @Param("orgId") String orgId);
}
