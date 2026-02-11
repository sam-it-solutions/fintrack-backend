package com.fintrack.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SavingsGoalResponse {
  private UUID id;
  private String name;
  private String currency;
  private BigDecimal targetAmount;
  private BigDecimal currentAmount;
  private BigDecimal monthlyContribution;
  private boolean autoEnabled;
  private String lastAppliedMonth;
  private Instant createdAt;
  private Instant updatedAt;
}
