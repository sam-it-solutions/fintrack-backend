package com.fintrack.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateConnectionRequest {
  @NotBlank
  private String providerId;

  private String displayName;

  private Map<String, String> config;
}
