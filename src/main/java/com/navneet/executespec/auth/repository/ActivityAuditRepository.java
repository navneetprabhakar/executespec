package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.ActivityAudit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface ActivityAuditRepository
        extends JpaRepository<ActivityAudit, String>,
                JpaSpecificationExecutor<ActivityAudit> {

    Page<ActivityAudit> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

    /** M-019 — chain tail for the writer to read prev_hash on the next insert.
     *  Returns the row with the highest chain_seq (NULL chain_seq excluded). */
    @Query("SELECT a FROM ActivityAudit a WHERE a.chainSeq IS NOT NULL "
            + "ORDER BY a.chainSeq DESC")
    List<ActivityAudit> findChainTail(Pageable pageable);

    default Optional<ActivityAudit> findChainTail() {
        var list = findChainTail(org.springframework.data.domain.PageRequest.of(0, 1));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /** M-019 — verifier walks chained rows in monotonic order. */
    @Query("SELECT a FROM ActivityAudit a WHERE a.chainSeq IS NOT NULL "
            + "ORDER BY a.chainSeq ASC")
    List<ActivityAudit> findChainOrdered();
}
