package com.fintrack.dto;

public class RuleRequest {
  private String matchType;
  private String matchValue;
  private String matchMode;
  private String category;
  private Boolean applyToHistory;

  public String getMatchType() {
    return matchType;
  }

  public void setMatchType(String matchType) {
    this.matchType = matchType;
  }

  public String getMatchValue() {
    return matchValue;
  }

  public void setMatchValue(String matchValue) {
    this.matchValue = matchValue;
  }

  public String getMatchMode() {
    return matchMode;
  }

  public void setMatchMode(String matchMode) {
    this.matchMode = matchMode;
  }

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public Boolean isApplyToHistory() {
    return applyToHistory;
  }

  public void setApplyToHistory(Boolean applyToHistory) {
    this.applyToHistory = applyToHistory;
  }
}
