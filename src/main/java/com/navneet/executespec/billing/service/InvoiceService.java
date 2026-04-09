package com.navneet.executespec.billing.service;

import com.navneet.executespec.billing.entity.*;
import com.navneet.executespec.billing.repository.*;
import com.navneet.executespec.billing.service.GstCalculationService.GstBreakdown;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InvoiceService {

    private final InvoiceRepository invoiceRepo;
    private final InvoiceLineItemRepository lineItemRepo;
    private final InvoiceSequenceRepository sequenceRepo;
    private final BillingProfileRepository profileRepo;
    private final SellerConfigRepository sellerRepo;
    private final GstCalculationService gstService;
    private final AccountingService accountingService;

    // ── Public API ────────────────────────────────────────────────────────────

    @Transactional
    public Invoice generateSubscriptionInvoice(BillingSubscription sub, BillingPlan plan,
                                                String paymentId) {
        // Dedup: if an invoice already exists for this payment, return it (prevents double-invoice
        // when both frontend activation and Razorpay webhook fire for the same charge)
        if (paymentId != null && !paymentId.isBlank()) {
            var existing = invoiceRepo.findByGatewayPaymentId(paymentId);
            if (existing.isPresent()) {
                log.info("Invoice already exists for paymentId={}, skipping duplicate generation", paymentId);
                return null;
            }
        }

        SellerConfig seller = sellerRepo.getDefault();
        BillingProfile buyer = resolveBuyerProfile(sub.getUserId(), sub.getOrgId());

        long subtotal = plan.getBaseAmountInr();
        if (sub.getExtraSeats() > 0) {
            subtotal += (long) sub.getExtraSeats() * plan.getPerSeatInr();
        }

        String description = plan.getName() + " subscription" +
                (sub.getExtraSeats() > 0 ? " (" + sub.getExtraSeats() + " extra seats)" : "");

        Invoice invoice = buildInvoice(sub.getUserId(), sub.getOrgId(), buyer, seller,
                subtotal, "SUBSCRIPTION", description, paymentId);
        invoice.setSubscriptionId(sub.getId());
        invoice = invoiceRepo.save(invoice);

        // Line items
        InvoiceLineItem baseLine = InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description(plan.getName() + " - " + plan.getPeriod().toLowerCase() + " subscription")
                .unitPrice(plan.getBaseAmountInr())
                .amount(plan.getBaseAmountInr())
                .sortOrder(0)
                .build();
        lineItemRepo.save(baseLine);

        if (sub.getExtraSeats() > 0) {
            InvoiceLineItem seatLine = InvoiceLineItem.builder()
                    .invoiceId(invoice.getId())
                    .description("Additional seats (" + sub.getExtraSeats() + " x " +
                            formatPaise(plan.getPerSeatInr()) + "/seat)")
                    .quantity(sub.getExtraSeats())
                    .unitPrice(plan.getPerSeatInr())
                    .amount((long) sub.getExtraSeats() * plan.getPerSeatInr())
                    .sortOrder(1)
                    .build();
            lineItemRepo.save(seatLine);
        }

        // Mark as paid
        invoice.setStatus("PAID");
        invoice.setPaidAt(Instant.now());
        invoice = invoiceRepo.save(invoice);

        // Record double-entry accounting
        accountingService.recordRevenueEntry(invoice);

        log.info("Subscription invoice generated: {} for user={}, amount={}",
                invoice.getInvoiceNumber(), sub.getUserId(), formatPaise(invoice.getTotalAmount()));
        return invoice;
    }

    @Transactional
    public Invoice generateTopupInvoice(RunTopup topup, TopupPackage pkg, String paymentId) {
        SellerConfig seller = sellerRepo.getDefault();
        BillingProfile buyer = resolveBuyerProfile(topup.getUserId(), topup.getOrgId());

        long subtotal = topup.getAmountPaidInr();
        String description = (pkg != null ? pkg.getName() : topup.getQuantity() + " runs") + " top-up";

        Invoice invoice = buildInvoice(topup.getUserId(), topup.getOrgId(), buyer, seller,
                subtotal, "TOPUP", description, paymentId);
        invoice.setTopupId(topup.getId());
        invoice = invoiceRepo.save(invoice);

        InvoiceLineItem line = InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description(description + " (" + topup.getQuantity() + " runs)")
                .unitPrice(subtotal)
                .amount(subtotal)
                .sortOrder(0)
                .build();
        lineItemRepo.save(line);

        invoice.setStatus("PAID");
        invoice.setPaidAt(Instant.now());
        invoice = invoiceRepo.save(invoice);

        // Record double-entry accounting
        accountingService.recordRevenueEntry(invoice);

        log.info("Top-up invoice generated: {} for user={}, amount={}",
                invoice.getInvoiceNumber(), topup.getUserId(), formatPaise(invoice.getTotalAmount()));
        return invoice;
    }

    /**
     * Generate an invoice for a Stripe subscription payment.
     * Stores the Stripe invoice ID and PDF URL so the PDF endpoint can redirect.
     */
    @Transactional
    public Invoice generateStripeSubscriptionInvoice(BillingSubscription sub, BillingPlan plan,
                                                       String stripeInvoiceId, String stripePdfUrl) {
        SellerConfig seller = sellerRepo.getDefault();
        BillingProfile buyer = resolveBuyerProfile(sub.getUserId(), sub.getOrgId());

        long subtotal = plan.getBaseAmountInr();
        if (sub.getExtraSeats() > 0) {
            subtotal += (long) sub.getExtraSeats() * plan.getPerSeatInr();
        }

        String description = plan.getName() + " subscription (Stripe)" +
                (sub.getExtraSeats() > 0 ? " (" + sub.getExtraSeats() + " extra seats)" : "");

        Invoice invoice = buildInvoice(sub.getUserId(), sub.getOrgId(), buyer, seller,
                subtotal, "SUBSCRIPTION", description, null);
        invoice.setSubscriptionId(sub.getId());
        invoice.setGateway("stripe");
        invoice.setGatewayInvoiceId(stripeInvoiceId);
        invoice.setGatewayPdfUrl(stripePdfUrl);
        invoice = invoiceRepo.save(invoice);

        // Line items
        InvoiceLineItem baseLine = InvoiceLineItem.builder()
                .invoiceId(invoice.getId())
                .description(plan.getName() + " - " + plan.getPeriod().toLowerCase() + " subscription")
                .unitPrice(plan.getBaseAmountInr())
                .amount(plan.getBaseAmountInr())
                .sortOrder(0)
                .build();
        lineItemRepo.save(baseLine);

        if (sub.getExtraSeats() > 0) {
            InvoiceLineItem seatLine = InvoiceLineItem.builder()
                    .invoiceId(invoice.getId())
                    .description("Additional seats (" + sub.getExtraSeats() + " x " +
                            formatPaise(plan.getPerSeatInr()) + "/seat)")
                    .quantity(sub.getExtraSeats())
                    .unitPrice(plan.getPerSeatInr())
                    .amount((long) sub.getExtraSeats() * plan.getPerSeatInr())
                    .sortOrder(1)
                    .build();
            lineItemRepo.save(seatLine);
        }

        // Mark as paid
        invoice.setStatus("PAID");
        invoice.setPaidAt(Instant.now());
        invoice = invoiceRepo.save(invoice);

        // Record double-entry accounting
        accountingService.recordRevenueEntry(invoice);

        log.info("Stripe subscription invoice generated: {} for user={}, gateway_invoice_id={}, amount={}",
                invoice.getInvoiceNumber(), sub.getUserId(), stripeInvoiceId, formatPaise(invoice.getTotalAmount()));
        return invoice;
    }

    @Transactional
    public Invoice generateCreditNote(UUID originalInvoiceId, long refundAmountPaise, String reason) {
        Invoice original = invoiceRepo.findById(originalInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Original invoice not found"));

        SellerConfig seller = sellerRepo.getDefault();
        BillingProfile buyer = resolveBuyerProfile(original.getUserId(), original.getOrgId());

        Invoice cn = buildInvoice(original.getUserId(), original.getOrgId(), buyer, seller,
                refundAmountPaise, "CREDIT_NOTE", "Credit note against " + original.getInvoiceNumber(), null);
        cn.setOriginalInvoiceId(originalInvoiceId);
        cn.setCreditNoteReason(reason);
        cn.setStatus("ISSUED");
        cn = invoiceRepo.save(cn);

        InvoiceLineItem line = InvoiceLineItem.builder()
                .invoiceId(cn.getId())
                .description("Refund / Credit — " + reason)
                .unitPrice(refundAmountPaise)
                .amount(refundAmountPaise)
                .sortOrder(0)
                .build();
        lineItemRepo.save(line);

        // Record refund accounting entries
        accountingService.recordRefundEntry(cn);

        log.info("Credit note generated: {} against {} for user={}",
                cn.getInvoiceNumber(), original.getInvoiceNumber(), original.getUserId());
        return cn;
    }

    public Optional<Invoice> findLatestBySubscriptionId(UUID subscriptionId) {
        return invoiceRepo.findTopBySubscriptionIdOrderByInvoiceDateDesc(subscriptionId);
    }

    public Optional<Invoice> getInvoice(UUID id) {
        return invoiceRepo.findById(id);
    }

    public List<InvoiceLineItem> getLineItems(UUID invoiceId) {
        return lineItemRepo.findByInvoiceIdOrderBySortOrder(invoiceId);
    }

    public Page<Invoice> listByUser(String userId, Pageable pageable) {
        return invoiceRepo.findByUserIdOrderByInvoiceDateDesc(userId, pageable);
    }

    public Page<Invoice> listByOrg(String orgId, Pageable pageable) {
        return invoiceRepo.findByOrgIdOrderByInvoiceDateDesc(orgId, pageable);
    }

    public Page<Invoice> listAll(Pageable pageable) {
        return invoiceRepo.findAll(pageable);
    }

    @Transactional
    public Invoice voidInvoice(UUID invoiceId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        invoice.setStatus("VOID");
        return invoiceRepo.save(invoice);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private Invoice buildInvoice(String userId, String orgId,
                                  BillingProfile buyer, SellerConfig seller,
                                  long subtotalPaise, String invoiceType,
                                  String description, String paymentId) {

        String buyerState = buyer != null ? buyer.getStateCode() : null;
        String buyerCountry = buyer != null ? buyer.getCountry() : "IN";

        GstBreakdown gst = gstService.calculate(subtotalPaise, buyerState,
                seller.getStateCode(), buyerCountry);

        String invoiceNumber = nextInvoiceNumber();
        String fy = currentFinancialYear();
        int seq = Integer.parseInt(invoiceNumber.substring(invoiceNumber.lastIndexOf('-') + 1));

        return Invoice.builder()
                .invoiceNumber(invoiceNumber)
                .financialYear(fy)
                .sequenceInYear(seq)
                .userId(userId)
                .orgId(orgId)
                .billingProfileId(buyer != null ? buyer.getId() : null)
                .invoiceType(invoiceType)
                .status("DRAFT")
                .currency("INR")
                .subtotal(subtotalPaise)
                .cgstAmount(gst.cgstAmount())
                .sgstAmount(gst.sgstAmount())
                .igstAmount(gst.igstAmount())
                .totalTax(gst.totalTax())
                .totalAmount(subtotalPaise + gst.totalTax())
                .cgstRate(gst.cgstRate())
                .sgstRate(gst.sgstRate())
                .igstRate(gst.igstRate())
                .placeOfSupply(buyerState)
                .reverseCharge(gst.isReverseCharge())
                .export_(gst.isExport())
                .sacCode(GstCalculationService.SAC_CODE)
                // Seller snapshot
                .sellerGstin(seller.getGstin())
                .sellerName(seller.getLegalName())
                .sellerAddress(formatSellerAddress(seller))
                .sellerStateCode(seller.getStateCode())
                // Buyer snapshot
                .buyerGstin(buyer != null ? buyer.getGstin() : null)
                .buyerName(buyer != null ? buyer.getLegalName() : null)
                .buyerAddress(buyer != null ? formatBuyerAddress(buyer) : null)
                .buyerStateCode(buyerState)
                // Payment
                .gatewayPaymentId(paymentId)
                .invoiceDate(LocalDate.now())
                .dueDate(LocalDate.now().plusDays(7))
                .description(description)
                .build();
    }

    @Transactional
    String nextInvoiceNumber() {
        String fy = currentFinancialYear();
        var seq = sequenceRepo.findByFinancialYearForUpdate(fy);

        InvoiceSequence sequence;
        if (seq.isPresent()) {
            sequence = seq.get();
        } else {
            // Auto-create for new FY
            sequence = new InvoiceSequence(fy, 0);
        }

        int next = sequence.getLastNumber() + 1;
        sequence.setLastNumber(next);
        sequenceRepo.save(sequence);

        return String.format("ES-%s-%04d", fy, next);
    }

    static String currentFinancialYear() {
        LocalDate today = LocalDate.now();
        int year = today.getYear();
        // Indian FY: April to March
        if (today.getMonth().compareTo(Month.APRIL) >= 0) {
            return String.format("%02d%02d", year % 100, (year + 1) % 100);
        } else {
            return String.format("%02d%02d", (year - 1) % 100, year % 100);
        }
    }

    private BillingProfile resolveBuyerProfile(String userId, String orgId) {
        if (orgId != null) {
            return profileRepo.findByOrgId(orgId).orElse(null);
        }
        if (userId != null) {
            return profileRepo.findByUserId(userId).orElse(null);
        }
        return null;
    }

    private String formatSellerAddress(SellerConfig s) {
        StringBuilder sb = new StringBuilder();
        if (s.getAddressLine1() != null) sb.append(s.getAddressLine1());
        if (s.getAddressLine2() != null) sb.append(", ").append(s.getAddressLine2());
        if (s.getCity() != null) sb.append(", ").append(s.getCity());
        if (s.getStateName() != null) sb.append(", ").append(s.getStateName());
        if (s.getPostalCode() != null) sb.append(" - ").append(s.getPostalCode());
        return sb.toString();
    }

    private String formatBuyerAddress(BillingProfile p) {
        StringBuilder sb = new StringBuilder();
        if (p.getAddressLine1() != null) sb.append(p.getAddressLine1());
        if (p.getAddressLine2() != null) sb.append(", ").append(p.getAddressLine2());
        if (p.getCity() != null) sb.append(", ").append(p.getCity());
        if (p.getStateName() != null) sb.append(", ").append(p.getStateName());
        if (p.getPostalCode() != null) sb.append(" - ").append(p.getPostalCode());
        return sb.toString();
    }

    private String formatPaise(long paise) {
        return String.format("%.2f", paise / 100.0);
    }
}
