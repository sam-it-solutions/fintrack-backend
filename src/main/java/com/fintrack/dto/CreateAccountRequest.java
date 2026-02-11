package com.fintrack.dto;

import com.fintrack.model.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateAccountRequest {
  @NotNull
  private AccountType type;

  @NotBlank
  private String provider;

  @NotBlank
  private String name;

  private String label;

  @NotBlank
  private String currency;

  private String externalId;

  private String iban;

  private String accountNumber;

  private java.math.BigDecimal openingBalance;
}
