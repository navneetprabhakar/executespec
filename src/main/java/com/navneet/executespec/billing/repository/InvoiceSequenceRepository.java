package com.navneet.executespec.billing.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.billing.entity.InvoiceSequence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceSequenceRepository extends JpaRepository<InvoiceSequence, String> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM InvoiceSequence s WHERE s.financialYear = :fy")
  Optional<InvoiceSequence> findByFinancialYearForUpdate(@Param("fy") String financialYear);
}
