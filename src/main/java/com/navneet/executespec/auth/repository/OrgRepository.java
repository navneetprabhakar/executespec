package com.navneet.executespec.auth.repository;

import com.navneet.executespec.auth.entity.Org;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrgRepository extends JpaRepository<Org, String> {

    Optional<Org> findByName(String name);

    @Query("SELECT o FROM Org o WHERE o.status != 'DELETED' ORDER BY o.createdAt DESC")
    List<Org> findAllActive();

    /** Pessimistic lock for atomic quota enforcement (prevents TOCTOU race on run count). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Org o WHERE o.id = :id")
    Optional<Org> findByIdForUpdate(@Param("id") String id);
}
