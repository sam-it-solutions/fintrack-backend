package com.fintrack.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class RuleResponse {
  private UUID id;
  private String matchType;
  private String matchValue;
  private String matchMode;
  private String category;
  private Instant createdAt;
  private Instant updatedAt;
}
