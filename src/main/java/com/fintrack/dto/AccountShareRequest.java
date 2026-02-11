package com.fintrack.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AccountShareRequest {
  @NotNull
  private UUID householdId;
}
