package com.fintrack.dto;

import com.fintrack.model.TransactionDirection;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateTransactionRequest {
  @NotNull
  private UUID accountId;

  @NotNull
  private BigDecimal amount;

  @NotNull
  private String currency;

  @NotNull
  private TransactionDirection direction;

  private String description;
  private LocalDate bookingDate;
  private LocalDate valueDate;
  private String category;
}
