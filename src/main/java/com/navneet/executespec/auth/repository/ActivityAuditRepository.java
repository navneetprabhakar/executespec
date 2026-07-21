package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import com.navneet.executespec.auth.entity.ActivityAudit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface ActivityAuditRepository
    extends JpaRepository<ActivityAudit, String>, JpaSpecificationExecutor<ActivityAudit> {

  Page<ActivityAudit> findByOrgIdOrderByCreatedAtDesc(String orgId, Pageable pageable);

  /**
   * M-019 — chain tail for the writer to read prev_hash on the next insert. Returns the row with
   * the highest chain_seq (NULL chain_seq excluded).
   */
  @Query("SELECT a FROM ActivityAudit a WHERE a.chainSeq IS NOT NULL " + "ORDER BY a.chainSeq DESC")
  List<ActivityAudit> findChainTail(Pageable pageable);

  default Optional<ActivityAudit> findChainTail() {
    var list = findChainTail(org.springframework.data.domain.PageRequest.of(0, 1));
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /** M-019 — verifier walks chained rows in monotonic order. */
  @Query("SELECT a FROM ActivityAudit a WHERE a.chainSeq IS NOT NULL " + "ORDER BY a.chainSeq ASC")
  List<ActivityAudit> findChainOrdered();

  /**
   * RR-209 — keyset page of the chain used by the streaming verifier, so {@link
   * #findChainOrdered()} no longer loads the whole ledger into one list. Ordered by chain_seq so
   * the caller can carry {@code expectedPrev} across pages; {@code afterSeq} is the last seq
   * already verified.
   */
  @Query(
      "SELECT a FROM ActivityAudit a WHERE a.chainSeq IS NOT NULL AND a.chainSeq > :afterSeq "
          + "ORDER BY a.chainSeq ASC")
  List<ActivityAudit> findChainOrderedAfter(
      @org.springframework.data.repository.query.Param("afterSeq") long afterSeq,
      Pageable pageable);

  /** RR-209 — total chained-row count for the verifier report, without materializing the rows. */
  @Query("SELECT COUNT(a) FROM ActivityAudit a WHERE a.chainSeq IS NOT NULL")
  long countChained();
}
