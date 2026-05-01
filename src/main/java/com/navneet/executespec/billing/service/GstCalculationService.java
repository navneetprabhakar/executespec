package com.navneet.executespec.billing.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * GST calculation for Indian SaaS invoicing.
 *
 * <ul>
 *   <li>Standard rate: 18% (SAC 998314 — IT software services)
 *   <li>Intra-state (same state as seller): CGST 9% + SGST 9%
 *   <li>Inter-state (different state): IGST 18%
 *   <li>Export (country ≠ IN): Zero-rated under LUT
 * </ul>
 */
@Service
@Slf4j
public class GstCalculationService {

  public static final BigDecimal GST_RATE = new BigDecimal("18.00");
  public static final BigDecimal CGST_RATE = new BigDecimal("9.00");
  public static final BigDecimal SGST_RATE = new BigDecimal("9.00");
  public static final BigDecimal IGST_RATE = new BigDecimal("18.00");
  public static final String SAC_CODE = "998314";

  public record GstBreakdown(
      BigDecimal cgstRate,
      BigDecimal sgstRate,
      BigDecimal igstRate,
      long cgstAmount,
      long sgstAmount,
      long igstAmount,
      long totalTax,
      boolean isExport,
      boolean isReverseCharge) {}

  /**
   * Calculate GST breakdown for a given subtotal.
   *
   * @param subtotalPaise taxable amount in paise
   * @param buyerStateCode buyer's 2-digit state code (nullable)
   * @param sellerStateCode seller's 2-digit state code
   * @param buyerCountry buyer's ISO country code (nullable, defaults to IN)
   */
  public GstBreakdown calculate(
      long subtotalPaise, String buyerStateCode, String sellerStateCode, String buyerCountry) {

    // Export — zero-rated under LUT
    if (buyerCountry != null && !"IN".equalsIgnoreCase(buyerCountry)) {
      return new GstBreakdown(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, 0, 0, true, false);
    }

    // No buyer state info — default to IGST (safe fallback)
    if (buyerStateCode == null || buyerStateCode.isBlank()) {
      long igst = Math.round(subtotalPaise * 18.0 / 100.0);
      return new GstBreakdown(
          BigDecimal.ZERO, BigDecimal.ZERO, IGST_RATE, 0, 0, igst, igst, false, false);
    }

    // Intra-state — CGST + SGST
    if (sellerStateCode.equals(buyerStateCode)) {
      long cgst = Math.round(subtotalPaise * 9.0 / 100.0);
      long sgst = Math.round(subtotalPaise * 9.0 / 100.0);
      return new GstBreakdown(
          CGST_RATE, SGST_RATE, BigDecimal.ZERO, cgst, sgst, 0, cgst + sgst, false, false);
    }

    // Inter-state — IGST
    long igst = Math.round(subtotalPaise * 18.0 / 100.0);
    return new GstBreakdown(
        BigDecimal.ZERO, BigDecimal.ZERO, IGST_RATE, 0, 0, igst, igst, false, false);
  }
}
