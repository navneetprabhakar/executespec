package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "accounting_entry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AccountingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "journal_ref", nullable = false, length = 50)
    private String journalRef;

    /** REVENUE, GST_OUTPUT, ACCOUNTS_RECEIVABLE, BANK, REFUND */
    @Column(nullable = false, length = 50)
    private String account;

    /** DEBIT or CREDIT */
    @Column(name = "entry_type", nullable = false, length = 6)
    private String entryType;

    @Column(nullable = false)
    private long amount;

    @Column(length = 3, nullable = false)
    @Builder.Default
    private String currency = "INR";

    @Column(name = "invoice_id")
    private UUID invoiceId;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "org_id")
    private String orgId;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String metadata;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }
}
