package com.fintrack.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "financial_accounts")
@Getter
@Setter
public class FinancialAccount {
  @Id
  private UUID id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "user_id")
  private User user;

  @ManyToOne
  @JoinColumn(name = "household_id")
  private Household household;

  @ManyToOne
  @JoinColumn(name = "connection_id")
  private Connection connection;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private AccountType type;

  @Column(nullable = false)
  private String provider;

  @Column(nullable = false)
  private String name;

  @Column
  private String label;

  @Column(nullable = false)
  private String currency;

  @Column
  private String externalId;

  @Column
  private String iban;

  @Column
  private String accountNumber;

  @Column(precision = 19, scale = 4)
  private BigDecimal currentBalance;

  @Column(precision = 19, scale = 4)
  private BigDecimal openingBalance;

  @Column(precision = 19, scale = 4)
  private BigDecimal currentFiatValue;

  @Column
  private String fiatCurrency;

  @Column
  private Instant lastSyncedAt;

  @PrePersist
  void prePersist() {
    if (id == null) {
      id = UUID.randomUUID();
    }
  }
}
