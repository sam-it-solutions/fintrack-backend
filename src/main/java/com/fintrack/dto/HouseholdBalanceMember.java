package com.fintrack.dto;

import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class HouseholdBalanceMember {
  private UUID userId;
  private String email;
  private BigDecimal paid;
  private BigDecimal share;
  private BigDecimal balance;
}
