package com.fintrack.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HouseholdJoinRequest {
  @NotBlank
  private String inviteCode;
}
