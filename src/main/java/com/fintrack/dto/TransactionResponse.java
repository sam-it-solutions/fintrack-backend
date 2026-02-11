package com.fintrack.dto;

import com.fintrack.model.TransactionDirection;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class TransactionResponse {
  private UUID id;
  private UUID accountId;
  private BigDecimal amount;
  private String currency;
  private TransactionDirection direction;
  private String description;
  private LocalDate bookingDate;
  private LocalDate valueDate;
  private String category;
  private String categorySource;
  private java.math.BigDecimal categoryConfidence;
  private String categoryReason;
  private String status;
  private String transactionType;
  private String merchantName;
  private String counterpartyIban;
}
