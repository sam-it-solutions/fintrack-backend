package com.fintrack.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class CategoryResponse {
  private UUID id;
  private String name;
  private Instant createdAt;
  private Instant updatedAt;
}
