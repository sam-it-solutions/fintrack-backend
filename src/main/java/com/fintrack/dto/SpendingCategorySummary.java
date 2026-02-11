package com.fintrack.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SpendingCategorySummary {
  private String category;
  private String currency;
  private BigDecimal total;
}
