package com.fintrack.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class HouseholdResponse {
  private UUID id;
  private String name;
  private String inviteCode;
  private String role;
}
