package com.fintrack.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RecurringPaymentResponse {
  private String name;
  private BigDecimal averageAmount;
  private String currency;
  private int occurrences;
  private int months;
  private LocalDate lastDate;
}
