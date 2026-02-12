package com.fintrack.dto;

import com.fintrack.model.AccountType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AccountResponse {
  private UUID id;
  private UUID connectionId;
  private AccountType type;
  private String provider;
  private String name;
  private String label;
  private String currency;
  private String externalId;
  private String iban;
  private String accountNumber;
  private BigDecimal currentBalance;
  private BigDecimal openingBalance;
  private BigDecimal currentFiatValue;
  private String fiatCurrency;
  private Instant lastSyncedAt;
  private BigDecimal priceChange24hPct;
}
