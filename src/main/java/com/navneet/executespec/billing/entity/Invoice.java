package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "invoice")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "invoice_number", nullable = false, unique = true, length = 20)
    private String invoiceNumber;

    @Column(name = "financial_year", nullable = false, length = 5)
    private String financialYear;

    @Column(name = "sequence_in_year", nullable = false)
    private int sequenceInYear;

    // ── Relations ──────────────────────────────────────────────────

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "org_id")
    private String orgId;

    @Column(name = "billing_profile_id")
    private UUID billingProfileId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "topup_id")
    private UUID topupId;

    // ── Type & Status ──────────────────────────────────────────────

    /** SUBSCRIPTION, TOPUP, CREDIT_NOTE */
    @Column(name = "invoice_type", nullable = false, length = 20)
    private String invoiceType;

    /** DRAFT, ISSUED, PAID, CANCELLED, VOID */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "DRAFT";

    // ── Amounts (in paise / smallest currency unit) ────────────────

    @Column(length = 3, nullable = false)
    @Builder.Default
    private String currency = "INR";

    @Column(nullable = false)
    private long subtotal;

    @Column(name = "cgst_amount", nullable = false)
    @Builder.Default
    private long cgstAmount = 0;

    @Column(name = "sgst_amount", nullable = false)
    @Builder.Default
    private long sgstAmount = 0;

    @Column(name = "igst_amount", nullable = false)
    @Builder.Default
    private long igstAmount = 0;

    @Column(name = "total_tax", nullable = false)
    @Builder.Default
    private long totalTax = 0;

    @Column(name = "total_amount", nullable = false)
    private long totalAmount;

    // ── GST Rates ──────────────────────────────────────────────────

    @Column(name = "cgst_rate")
    @Builder.Default
    private BigDecimal cgstRate = BigDecimal.ZERO;

    @Column(name = "sgst_rate")
    @Builder.Default
    private BigDecimal sgstRate = BigDecimal.ZERO;

    @Column(name = "igst_rate")
    @Builder.Default
    private BigDecimal igstRate = BigDecimal.ZERO;

    @Column(name = "place_of_supply", length = 2)
    private String placeOfSupply;

    @Column(name = "is_reverse_charge", nullable = false)
    @Builder.Default
    private boolean reverseCharge = false;

    @Column(name = "is_export", nullable = false)
    @Builder.Default
    private boolean export_ = false;

    @Column(name = "sac_code", length = 10)
    @Builder.Default
    private String sacCode = "998314";

    // ── Seller snapshot ────────────────────────────────────────────

    @Column(name = "seller_gstin", length = 15)
    private String sellerGstin;

    @Column(name = "seller_name")
    private String sellerName;

    @Column(name = "seller_address", columnDefinition = "TEXT")
    private String sellerAddress;

    @Column(name = "seller_state_code", length = 2)
    private String sellerStateCode;

    // ── Buyer snapshot ─────────────────────────────────────────────

    @Column(name = "buyer_gstin", length = 15)
    private String buyerGstin;

    @Column(name = "buyer_name")
    private String buyerName;

    @Column(name = "buyer_address", columnDefinition = "TEXT")
    private String buyerAddress;

    @Column(name = "buyer_state_code", length = 2)
    private String buyerStateCode;

    // ── Payment reference ──────────────────────────────────────────

    @Column(name = "gateway_payment_id", length = 100)
    private String gatewayPaymentId;

    @Column(name = "gateway_order_id", length = 100)
    private String gatewayOrderId;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "paid_at")
    private Instant paidAt;

    // ── PDF ─────────────────────────────────────────────────────────

    @Column(name = "pdf_storage_path", length = 500)
    private String pdfStoragePath;

    @Column(name = "pdf_generated_at")
    private Instant pdfGeneratedAt;

    /** Payment gateway used: "razorpay" or "stripe". */
    @Column(name = "gateway", length = 20)
    @Builder.Default
    private String gateway = "razorpay";

    /** Gateway-specific invoice ID (e.g., Stripe invoice ID). */
    @Column(name = "gateway_invoice_id")
    private String gatewayInvoiceId;

    /** Gateway-hosted PDF URL (Stripe provides this natively). */
    @Column(name = "gateway_pdf_url", columnDefinition = "TEXT")
    private String gatewayPdfUrl;

    // ── Credit note reference ──────────────────────────────────────

    @Column(name = "original_invoice_id")
    private UUID originalInvoiceId;

    @Column(name = "credit_note_reason", columnDefinition = "TEXT")
    private String creditNoteReason;

    // ── Dates ───────────────────────────────────────────────────────

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String notes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
