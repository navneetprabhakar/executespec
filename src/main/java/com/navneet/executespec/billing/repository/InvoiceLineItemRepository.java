package com.navneet.executespec.billing.repository;

import java.util.List;
import java.util.UUID;

import com.navneet.executespec.billing.entity.InvoiceLineItem;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, UUID> {

  List<InvoiceLineItem> findByInvoiceIdOrderBySortOrder(UUID invoiceId);
}
