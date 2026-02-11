package com.fintrack.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class HouseholdBalanceResponse {
  private UUID householdId;
  private String month;
  private BigDecimal totalExpenses;
  private BigDecimal perMemberShare;
  private List<HouseholdBalanceMember> members;
}
