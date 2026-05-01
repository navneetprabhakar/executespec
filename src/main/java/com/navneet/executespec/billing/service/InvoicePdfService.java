package com.navneet.executespec.billing.service;

import java.io.ByteArrayOutputStream;
import java.util.List;

import com.navneet.executespec.billing.entity.Invoice;
import com.navneet.executespec.billing.entity.InvoiceLineItem;
import com.navneet.executespec.billing.entity.SellerConfig;
import com.navneet.executespec.billing.repository.InvoiceLineItemRepository;
import com.navneet.executespec.billing.repository.SellerConfigRepository;

import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoicePdfService {

  private final TemplateEngine templateEngine;
  private final InvoiceLineItemRepository lineItemRepo;
  private final SellerConfigRepository sellerRepo;

  public byte[] generatePdf(Invoice invoice) {
    SellerConfig seller = sellerRepo.getDefault();
    List<InvoiceLineItem> lineItems = lineItemRepo.findByInvoiceIdOrderBySortOrder(invoice.getId());

    Context ctx = new Context();
    ctx.setVariable("invoice", invoice);
    ctx.setVariable("seller", seller);
    ctx.setVariable("lineItems", lineItems);
    ctx.setVariable("subtotalFormatted", formatPaise(invoice.getSubtotal()));
    ctx.setVariable("totalTaxFormatted", formatPaise(invoice.getTotalTax()));
    ctx.setVariable("totalFormatted", formatPaise(invoice.getTotalAmount()));
    ctx.setVariable("totalInWords", amountInWords(invoice.getTotalAmount()));

    // GST breakdown
    if (invoice.getCgstAmount() > 0) {
      ctx.setVariable("showCgstSgst", true);
      ctx.setVariable("cgstFormatted", formatPaise(invoice.getCgstAmount()));
      ctx.setVariable("sgstFormatted", formatPaise(invoice.getSgstAmount()));
      ctx.setVariable("cgstRateFormatted", formatRate(invoice.getCgstRate()));
      ctx.setVariable("sgstRateFormatted", formatRate(invoice.getSgstRate()));
    } else {
      ctx.setVariable("showCgstSgst", false);
    }
    if (invoice.getIgstAmount() > 0) {
      ctx.setVariable("showIgst", true);
      ctx.setVariable("igstFormatted", formatPaise(invoice.getIgstAmount()));
      ctx.setVariable("igstRateFormatted", formatRate(invoice.getIgstRate()));
    } else {
      ctx.setVariable("showIgst", false);
    }

    // Line item formatted amounts
    ctx.setVariable(
        "lineItemAmounts", lineItems.stream().map(li -> formatPaise(li.getAmount())).toList());

    String html = templateEngine.process("invoice/invoice-pdf", ctx);

    try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
      ITextRenderer renderer = new ITextRenderer();
      renderer.setDocumentFromString(html);
      renderer.layout();
      renderer.createPDF(os);
      return os.toByteArray();
    } catch (Exception e) {
      log.error(
          "Failed to generate PDF for invoice {}: {}",
          invoice.getInvoiceNumber(),
          e.getMessage(),
          e);
      throw new RuntimeException("PDF generation failed", e);
    }
  }

  private String formatPaise(long paise) {
    long rupees = paise / 100;
    long remainder = paise % 100;
    return String.format("₹%s.%02d", formatIndian(rupees), remainder);
  }

  private String formatRate(java.math.BigDecimal rate) {
    if (rate == null) return "0%";
    return rate.stripTrailingZeros().toPlainString() + "%";
  }

  /** Format number in Indian numbering (1,23,456). */
  private String formatIndian(long n) {
    if (n < 0) return "-" + formatIndian(-n);
    String s = String.valueOf(n);
    if (s.length() <= 3) return s;

    StringBuilder sb = new StringBuilder();
    sb.append(s.substring(s.length() - 3));
    int i = s.length() - 3;
    while (i > 0) {
      int take = Math.min(2, i);
      sb.insert(0, ",");
      sb.insert(0, s.substring(i - take, i));
      i -= take;
    }
    return sb.toString();
  }

  /** Convert paise to words (Indian style: lakhs, crores). */
  static String amountInWords(long paise) {
    long rupees = paise / 100;
    long paiseRem = paise % 100;

    StringBuilder sb = new StringBuilder("Rupees ");
    sb.append(numberToWords(rupees));
    if (paiseRem > 0) {
      sb.append(" and ").append(numberToWords(paiseRem)).append(" Paise");
    }
    sb.append(" Only");
    return sb.toString();
  }

  private static final String[] ONES = {
    "",
    "One",
    "Two",
    "Three",
    "Four",
    "Five",
    "Six",
    "Seven",
    "Eight",
    "Nine",
    "Ten",
    "Eleven",
    "Twelve",
    "Thirteen",
    "Fourteen",
    "Fifteen",
    "Sixteen",
    "Seventeen",
    "Eighteen",
    "Nineteen"
  };
  private static final String[] TENS = {
    "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
  };

  private static String numberToWords(long n) {
    if (n == 0) return "Zero";
    if (n < 0) return "Minus " + numberToWords(-n);

    StringBuilder sb = new StringBuilder();
    if (n / 10000000 > 0) {
      sb.append(numberToWords(n / 10000000)).append(" Crore ");
      n %= 10000000;
    }
    if (n / 100000 > 0) {
      sb.append(numberToWords(n / 100000)).append(" Lakh ");
      n %= 100000;
    }
    if (n / 1000 > 0) {
      sb.append(numberToWords(n / 1000)).append(" Thousand ");
      n %= 1000;
    }
    if (n / 100 > 0) {
      sb.append(ONES[(int) (n / 100)]).append(" Hundred ");
      n %= 100;
    }
    if (n > 0) {
      if (sb.length() > 0) sb.append("and ");
      if (n < 20) {
        sb.append(ONES[(int) n]);
      } else {
        sb.append(TENS[(int) (n / 10)]);
        if (n % 10 > 0) sb.append(" ").append(ONES[(int) (n % 10)]);
      }
    }
    return sb.toString().trim();
  }
}
