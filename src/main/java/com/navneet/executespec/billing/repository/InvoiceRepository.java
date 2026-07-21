package com.navneet.executespec.billing.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import com.navneet.executespec.billing.entity.Invoice;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceRepository
    extends JpaRepository<Invoice, UUID>, JpaSpecificationExecutor<Invoice> {

  Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

  Page<Invoice> findByUserIdOrderByInvoiceDateDesc(String userId, Pageable pageable);

  Page<Invoice> findByOrgIdOrderByInvoiceDateDesc(String orgId, Pageable pageable);

  Optional<Invoice> findByGatewayPaymentId(String gatewayPaymentId);

  Optional<Invoice> findTopBySubscriptionIdOrderByInvoiceDateDesc(UUID subscriptionId);

  /**
   * RR-184 — take a row lock on the invoice being credited so concurrent credit-note requests
   * serialize on the same original and cannot each observe the same remaining balance.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT i FROM Invoice i WHERE i.id = :id")
  Optional<Invoice> findByIdForUpdate(@Param("id") UUID id);

  /** RR-184 — every credit note already issued against an original invoice. */
  @Query("SELECT i FROM Invoice i WHERE i.originalInvoiceId = :originalInvoiceId")
  List<Invoice> findCreditNotesFor(@Param("originalInvoiceId") UUID originalInvoiceId);
}
