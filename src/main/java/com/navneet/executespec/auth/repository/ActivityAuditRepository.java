package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.ActivityAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface ActivityAuditRepository
        extends JpaRepository<ActivityAudit, String>,
                JpaSpecificationExecutor<ActivityAudit> {

    Page<ActivityAudit> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);
}
