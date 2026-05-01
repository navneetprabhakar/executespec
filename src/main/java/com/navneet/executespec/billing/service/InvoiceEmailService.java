package com.navneet.executespec.billing.service;

import com.navneet.executespec.auth.service.MailService;
import com.navneet.executespec.billing.entity.Invoice;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceEmailService {

  private final MailService mailService;
  private final InvoicePdfService pdfService;

  @Async
  public void sendInvoice(String email, String firstName, Invoice invoice) {
    try {
      byte[] pdf = pdfService.generatePdf(invoice);
      String filename = invoice.getInvoiceNumber() + ".pdf";
      mailService.sendInvoiceEmail(
          email,
          firstName,
          invoice.getInvoiceNumber(),
          invoice.getSubtotal(),
          invoice.getTotalTax(),
          invoice.getTotalAmount(),
          pdf,
          filename);
    } catch (Exception e) {
      log.warn(
          "Failed to send invoice email for {}: {}", invoice.getInvoiceNumber(), e.getMessage());
    }
  }
}
