package com.fintrack.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CurrencySummary {
  private String currency;
  private BigDecimal totalBalance;
  private BigDecimal totalIncomeMonth;
  private BigDecimal totalExpenseMonth;
}
