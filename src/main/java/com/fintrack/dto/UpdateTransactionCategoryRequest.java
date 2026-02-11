package com.fintrack.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateTransactionCategoryRequest {
  @NotBlank
  private String category;
  private boolean applyToFuture;

  public String getCategory() {
    return category;
  }

  public void setCategory(String category) {
    this.category = category;
  }

  public boolean isApplyToFuture() {
    return applyToFuture;
  }

  public void setApplyToFuture(boolean applyToFuture) {
    this.applyToFuture = applyToFuture;
  }
}
