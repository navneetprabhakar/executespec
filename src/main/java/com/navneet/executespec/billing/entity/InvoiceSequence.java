package com.navneet.executespec.billing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
