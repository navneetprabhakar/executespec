package com.navneet.executespec.auth.repository;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.auth.entity.Org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrgRepository extends JpaRepository<Org, String> {

  Optional<Org> findByName(String name);

  @Query("SELECT o FROM Org o WHERE o.status != 'DELETED' ORDER BY o.createdAt DESC")
  List<Org> findAllActive();

  /** Pessimistic lock for atomic quota enforcement (prevents TOCTOU race on run count). */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT o FROM Org o WHERE o.id = :id")
  Optional<Org> findByIdForUpdate(@Param("id") String id);

  /**
   * B-08 fix (2026-05-09) — bulk reset of monthly_run_count to 0 for the supplied org IDs.
   * Mirrors {@code AppUserRepository.bulkResetPersonalRunCount}; same N+1 issue, same fix
   * shape. Driver passes the set of org IDs the per-org predicate already approved for reset.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.transaction.annotation.Transactional
  @Query("UPDATE Org o SET o.monthlyRunCount = 0, o.runCountResetAt = :now WHERE o.id IN :ids")
  int bulkResetMonthlyRunCount(
      @Param("ids") List<String> ids, @Param("now") java.time.Instant now);
}
