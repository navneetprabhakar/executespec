package com.navneet.executespec.billing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.repository.AccountingEntryRepository;
import com.navneet.executespec.billing.repository.InvoiceRepository;

import org.junit.jupiter.api.Test;

class AccountingServiceTest {

  private final InvoiceRepository invoiceRepo = mock(InvoiceRepository.class);
  private final AccountingService service =
      new AccountingService(mock(AccountingEntryRepository.class), invoiceRepo);

  @Test
  void exportGstr1HandlesLegacyInvoicesWithNullGstRates() {
    Invoice invoice =
        Invoice.builder()
            .invoiceNumber("INV-1")
            .invoiceDate(LocalDate.of(2026, 4, 10))
            .invoiceType("SUBSCRIPTION")
            .buyerName("Buyer")
            .placeOfSupply("KA")
            .subtotal(10000)
            .cgstAmount(0)
            .sgstAmount(0)
            .igstAmount(0)
            .totalTax(0)
            .totalAmount(10000)
            .sacCode("998314")
            .status("PAID")
            .build();
    invoice.setCgstRate(null);
    invoice.setSgstRate(null);
    invoice.setIgstRate(null);
    when(invoiceRepo.findAll()).thenReturn(List.of(invoice));

    String csv = new String(service.exportGstr1("2627", 1), StandardCharsets.UTF_8);

    assertThat(csv).contains("INV-1");
    assertThat(csv).contains(",0.00,0.00,0.00,");
  }
}
