package com.navneet.executespec.billing.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "invoice_sequence")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceSequence {

    @Id
    @Column(name = "financial_year", length = 5)
    private String financialYear;

    @Column(name = "last_number", nullable = false)
    private int lastNumber;
}
