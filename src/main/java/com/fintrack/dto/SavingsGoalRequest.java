package com.fintrack.dto;

import java.math.BigDecimal;

public class SavingsGoalRequest {
  private String name;
  private String currency;
  private BigDecimal targetAmount;
  private BigDecimal currentAmount;
  private BigDecimal monthlyContribution;
  private Boolean autoEnabled;

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public BigDecimal getTargetAmount() {
    return targetAmount;
  }

  public void setTargetAmount(BigDecimal targetAmount) {
    this.targetAmount = targetAmount;
  }

  public BigDecimal getCurrentAmount() {
    return currentAmount;
  }

  public void setCurrentAmount(BigDecimal currentAmount) {
    this.currentAmount = currentAmount;
  }

  public BigDecimal getMonthlyContribution() {
    return monthlyContribution;
  }

  public void setMonthlyContribution(BigDecimal monthlyContribution) {
    this.monthlyContribution = monthlyContribution;
  }

  public Boolean isAutoEnabled() {
    return autoEnabled;
  }

  public void setAutoEnabled(Boolean autoEnabled) {
    this.autoEnabled = autoEnabled;
  }
}
