package com.navneet.executespec.billing.repository;

import com.navneet.executespec.billing.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, UUID>,
        JpaSpecificationExecutor<Invoice> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Page<Invoice> findByUserIdOrderByInvoiceDateDesc(String userId, Pageable pageable);

    Page<Invoice> findByOrgIdOrderByInvoiceDateDesc(String orgId, Pageable pageable);

    Optional<Invoice> findByGatewayPaymentId(String gatewayPaymentId);
}
