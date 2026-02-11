package com.fintrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "account_transactions")
@Getter
@Setter
public class AccountTransaction {
  private static final int DEFAULT_VARCHAR_LIMIT = 255;
  private static final int TRANSACTION_TYPE_LIMIT = 128;
  @Id
  private UUID id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "account_id")
  private FinancialAccount account;

  @Column(nullable = false, precision = 19, scale = 4)
  private BigDecimal amount;

  @Column(nullable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private TransactionDirection direction;

  @Column(columnDefinition = "text")
  private String description;

  @Column
  private LocalDate bookingDate;

  @Column
  private LocalDate valueDate;

  @Column
  private String category;

  @Column
  private String categorySource;

  @Column(precision = 5, scale = 2)
  private java.math.BigDecimal categoryConfidence;

  @Column(columnDefinition = "text")
  private String categoryReason;

  @Column(length = 512)
  private String externalId;

  @Column(length = 512)
  private String providerTransactionId;

  @Column
  private String status;

  @Column(length = 128)
  private String transactionType;

  @Column(length = 512)
  private String merchantName;

  @Column(length = 64)
  private String counterpartyIban;

  @Column(nullable = false)
  private Instant createdAt;

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
    if (createdAt == null) {
      createdAt = Instant.now();
    }
    normalizeLengths();
  }

  @PreUpdate
  void preUpdate() {
    normalizeLengths();
  }

  private void normalizeLengths() {
    description = truncate(description, DEFAULT_VARCHAR_LIMIT);
    categoryReason = truncate(categoryReason, DEFAULT_VARCHAR_LIMIT);
    merchantName = truncate(merchantName, DEFAULT_VARCHAR_LIMIT);
    transactionType = truncate(transactionType, TRANSACTION_TYPE_LIMIT);
  }

  private static String truncate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value;
    }
    return value.substring(0, max);
  }
}
